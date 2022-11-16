/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.app;

import static android.content.Intent.ACTION_PACKAGE_ADDED;
import static android.content.Intent.ACTION_PACKAGE_REMOVED;
import static android.content.Intent.EXTRA_REPLACING;

import static com.android.internal.R.styleable.GameModeConfig_allowGameAngleDriver;
import static com.android.internal.R.styleable.GameModeConfig_allowGameDownscaling;
import static com.android.internal.R.styleable.GameModeConfig_allowGameFpsOverride;
import static com.android.internal.R.styleable.GameModeConfig_supportsBatteryGameMode;
import static com.android.internal.R.styleable.GameModeConfig_supportsPerformanceGameMode;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.GameManager;
import android.app.GameManager.GameMode;
import android.app.GameManagerInternal;
import android.app.GameModeInfo;
import android.app.GameState;
import android.app.IGameManagerService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.hardware.power.Mode;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManagerInternal;
import android.os.Process;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.provider.DeviceConfig.Properties;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.AttributeSet;
import android.util.KeyValueListParser;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.SystemService;
import com.android.server.SystemService.TargetUser;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Service to manage game related features.
 *
 * <p>Game service is a core service that monitors, coordinates game related features,
 * as well as collect metrics.</p>
 *
 * @hide
 */
public final class GameManagerService extends IGameManagerService.Stub {
    public static final String TAG = "GameManagerService";

    private static final boolean DEBUG = false;

    static final int WRITE_SETTINGS = 1;
    static final int REMOVE_SETTINGS = 2;
    static final int POPULATE_GAME_MODE_SETTINGS = 3;
    static final int SET_GAME_STATE = 4;
    static final int CANCEL_GAME_LOADING_MODE = 5;
    static final int WRITE_GAME_MODE_INTERVENTION_LIST_FILE = 6;
    static final int WRITE_DELAY_MILLIS = 10 * 1000;  // 10 seconds
    static final int LOADING_BOOST_MAX_DURATION = 5 * 1000;  // 5 seconds

    private static final String PACKAGE_NAME_MSG_KEY = "packageName";
    private static final String USER_ID_MSG_KEY = "userId";
    private static final String GAME_MODE_INTERVENTION_LIST_FILE_NAME =
            "game_mode_intervention.list";

    private final Context mContext;
    private final Object mLock = new Object();
    private final Object mDeviceConfigLock = new Object();
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    final Handler mHandler;
    private final PackageManager mPackageManager;
    private final UserManager mUserManager;
    private final PowerManagerInternal mPowerManagerInternal;
    private final File mSystemDir;
    @VisibleForTesting
    final AtomicFile mGameModeInterventionListFile;
    private DeviceConfigListener mDeviceConfigListener;
    @GuardedBy("mLock")
    private final ArrayMap<Integer, GameManagerSettings> mSettings = new ArrayMap<>();
    @GuardedBy("mDeviceConfigLock")
    private final ArrayMap<String, GamePackageConfiguration> mConfigs = new ArrayMap<>();
    @Nullable
    private final GameServiceController mGameServiceController;

    public GameManagerService(Context context) {
        this(context, createServiceThread().getLooper());
    }

    GameManagerService(Context context, Looper looper) {
        mContext = context;
        mHandler = new SettingsHandler(looper);
        mPackageManager = mContext.getPackageManager();
        mUserManager = mContext.getSystemService(UserManager.class);
        mPowerManagerInternal = LocalServices.getService(PowerManagerInternal.class);
        mSystemDir = new File(Environment.getDataDirectory(), "system");
        mSystemDir.mkdirs();
        FileUtils.setPermissions(mSystemDir.toString(),
                FileUtils.S_IRWXU | FileUtils.S_IRWXG | FileUtils.S_IROTH | FileUtils.S_IXOTH,
                -1, -1);
        mGameModeInterventionListFile = new AtomicFile(new File(mSystemDir,
                                                     GAME_MODE_INTERVENTION_LIST_FILE_NAME));
        FileUtils.setPermissions(mGameModeInterventionListFile.getBaseFile().getAbsolutePath(),
                FileUtils.S_IRUSR | FileUtils.S_IWUSR
                        | FileUtils.S_IRGRP | FileUtils.S_IWGRP,
                -1, -1);
        if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_GAME_SERVICE)) {
            mGameServiceController = new GameServiceController(
                    context, BackgroundThread.getExecutor(),
                    new GameServiceProviderSelectorImpl(
                            context.getResources(),
                            context.getPackageManager()),
                    new GameServiceProviderInstanceFactoryImpl(context));
        } else {
            mGameServiceController = null;
        }
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    GameManagerService(Context context, Looper looper, File dataDir) {
        mContext = context;
        mHandler = new SettingsHandler(looper);
        mPackageManager = mContext.getPackageManager();
        mUserManager = mContext.getSystemService(UserManager.class);
        mPowerManagerInternal = LocalServices.getService(PowerManagerInternal.class);
        mSystemDir = new File(dataDir, "system");
        mSystemDir.mkdirs();
        FileUtils.setPermissions(mSystemDir.toString(),
                FileUtils.S_IRWXU | FileUtils.S_IRWXG | FileUtils.S_IROTH | FileUtils.S_IXOTH,
                -1, -1);
        mGameModeInterventionListFile = new AtomicFile(new File(mSystemDir,
                GAME_MODE_INTERVENTION_LIST_FILE_NAME));
        FileUtils.setPermissions(mGameModeInterventionListFile.getBaseFile().getAbsolutePath(),
                FileUtils.S_IRUSR | FileUtils.S_IWUSR
                        | FileUtils.S_IRGRP | FileUtils.S_IWGRP,
                -1, -1);
        if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_GAME_SERVICE)) {
            mGameServiceController = new GameServiceController(
                    context, BackgroundThread.getExecutor(),
                    new GameServiceProviderSelectorImpl(
                            context.getResources(),
                            context.getPackageManager()),
                    new GameServiceProviderInstanceFactoryImpl(context));
        } else {
            mGameServiceController = null;
        }
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver result) {
        new GameManagerShellCommand().exec(this, in, out, err, args, callback, result);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            writer.println("Permission Denial: can't dump GameManagerService from from pid="
                    + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                    + " without permission " + android.Manifest.permission.DUMP);
            return;
        }
        if (args == null || args.length == 0) {
            writer.println("*Dump GameManagerService*");
            dumpAllGameConfigs(writer);
        }
    }

    private void dumpAllGameConfigs(PrintWriter pw) {
        final int userId = ActivityManager.getCurrentUser();
        String[] packageList = getInstalledGamePackageNames(userId);
        for (final String packageName : packageList) {
            pw.println(getInterventionList(packageName, userId));
        }
    }

    class SettingsHandler extends Handler {

        SettingsHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            doHandleMessage(msg);
        }

        void doHandleMessage(Message msg) {
            switch (msg.what) {
                case WRITE_SETTINGS: {
                    final int userId = (int) msg.obj;
                    if (userId < 0) {
                        Slog.wtf(TAG, "Attempt to write settings for invalid user: " + userId);
                        synchronized (mLock) {
                            removeEqualMessages(WRITE_SETTINGS, msg.obj);
                        }
                        break;
                    }
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
                    synchronized (mLock) {
                        removeEqualMessages(WRITE_SETTINGS, msg.obj);
                        if (mSettings.containsKey(userId)) {
                            GameManagerSettings userSettings = mSettings.get(userId);
                            userSettings.writePersistentDataLocked();
                        }
                    }
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    break;
                }
                case REMOVE_SETTINGS: {
                    final int userId = (int) msg.obj;
                    if (userId < 0) {
                        Slog.wtf(TAG, "Attempt to write settings for invalid user: " + userId);
                        synchronized (mLock) {
                            removeEqualMessages(WRITE_SETTINGS, msg.obj);
                            removeEqualMessages(REMOVE_SETTINGS, msg.obj);
                        }
                        break;
                    }

                    synchronized (mLock) {
                        // Since the user was removed, ignore previous write message
                        // and do write here.
                        removeEqualMessages(WRITE_SETTINGS, msg.obj);
                        removeEqualMessages(REMOVE_SETTINGS, msg.obj);
                        if (mSettings.containsKey(userId)) {
                            final GameManagerSettings userSettings = mSettings.get(userId);
                            mSettings.remove(userId);
                            userSettings.writePersistentDataLocked();
                        }
                    }
                    break;
                }
                case POPULATE_GAME_MODE_SETTINGS: {
                    removeEqualMessages(POPULATE_GAME_MODE_SETTINGS, msg.obj);
                    final int userId = (int) msg.obj;
                    final String[] packageNames = getInstalledGamePackageNames(userId);
                    updateConfigsForUser(userId, false /*checkGamePackage*/, packageNames);
                    break;
                }
                case SET_GAME_STATE: {
                    final GameState gameState = (GameState) msg.obj;
                    final boolean isLoading = gameState.isLoading();
                    final Bundle data = msg.getData();
                    final String packageName = data.getString(PACKAGE_NAME_MSG_KEY);
                    final int userId = data.getInt(USER_ID_MSG_KEY);

                    // Restrict to games only. Requires performance mode to be enabled.
                    final boolean boostEnabled =
                            getGameMode(packageName, userId) == GameManager.GAME_MODE_PERFORMANCE;
                    int uid;
                    try {
                        uid = mPackageManager.getPackageUidAsUser(packageName, userId);
                    } catch (NameNotFoundException e) {
                        Slog.v(TAG, "Failed to get package metadata");
                        uid = -1;
                    }
                    FrameworkStatsLog.write(FrameworkStatsLog.GAME_STATE_CHANGED, packageName, uid,
                            boostEnabled, gameStateModeToStatsdGameState(gameState.getMode()),
                            isLoading, gameState.getLabel(), gameState.getQuality());

                    if (boostEnabled) {
                        if (mPowerManagerInternal == null) {
                            Slog.d(TAG, "Error setting loading mode for package " + packageName
                                    + " and userId " + userId);
                            break;
                        }
                        mPowerManagerInternal.setPowerMode(Mode.GAME_LOADING, isLoading);
                    }
                    break;
                }
                case CANCEL_GAME_LOADING_MODE: {
                    mPowerManagerInternal.setPowerMode(Mode.GAME_LOADING, false);
                    break;
                }
                case WRITE_GAME_MODE_INTERVENTION_LIST_FILE: {
                    final int userId = (int) msg.obj;
                    if (userId < 0) {
                        Slog.wtf(TAG, "Attempt to write setting for invalid user: " + userId);
                        synchronized (mLock) {
                            removeEqualMessages(WRITE_GAME_MODE_INTERVENTION_LIST_FILE, msg.obj);
                        }
                        break;
                    }

                    Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
                    removeEqualMessages(WRITE_GAME_MODE_INTERVENTION_LIST_FILE, msg.obj);
                    writeGameModeInterventionsToFile(userId);
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    break;
                }
            }
        }
    }

    private class DeviceConfigListener implements DeviceConfig.OnPropertiesChangedListener {

        DeviceConfigListener() {
            super();
            DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_GAME_OVERLAY,
                    mContext.getMainExecutor(), this);
        }

        @Override
        public void onPropertiesChanged(Properties properties) {
            final String[] packageNames = properties.getKeyset().toArray(new String[0]);
            updateConfigsForUser(ActivityManager.getCurrentUser(), true /*checkGamePackage*/,
                    packageNames);
        }

        @Override
        public void finalize() {
            DeviceConfig.removeOnPropertiesChangedListener(this);
        }
    }

    public enum FrameRate {
        FPS_DEFAULT(0),
        FPS_30(30),
        FPS_40(40),
        FPS_45(45),
        FPS_60(60),
        FPS_90(90),
        FPS_120(120),
        FPS_INVALID(-1);

        public final int fps;

        FrameRate(int fps) {
            this.fps = fps;
        }
    }

    // Turn the raw string to the corresponding fps int.
    // Return 0 when disabling, -1 for invalid fps.
    static int getFpsInt(String raw) {
        switch (raw) {
            case "30":
                return FrameRate.FPS_30.fps;
            case "40":
                return FrameRate.FPS_40.fps;
            case "45":
                return FrameRate.FPS_45.fps;
            case "60":
                return FrameRate.FPS_60.fps;
            case "90":
                return FrameRate.FPS_90.fps;
            case "120":
                return FrameRate.FPS_120.fps;
            case "disable":
            case "":
                return FrameRate.FPS_DEFAULT.fps;
        }
        return FrameRate.FPS_INVALID.fps;
    }

    /**
     * Called by games to communicate the current state to the platform.
     *
     * @param packageName The client package name.
     * @param gameState   An object set to the current state.
     * @param userId      The user associated with this state.
     */
    public void setGameState(String packageName, @NonNull GameState gameState,
            @UserIdInt int userId) {
        if (!isPackageGame(packageName, userId)) {
            // Restrict to games only.
            return;
        }
        final Message msg = mHandler.obtainMessage(SET_GAME_STATE);
        final Bundle data = new Bundle();
        data.putString(PACKAGE_NAME_MSG_KEY, packageName);
        data.putInt(USER_ID_MSG_KEY, userId);
        msg.setData(data);
        msg.obj = gameState;
        mHandler.sendMessage(msg);
    }

    /**
     * GamePackageConfiguration manages all game mode config details for its associated package.
     */
    public static class GamePackageConfiguration {
        public static final String TAG = "GameManagerService_GamePackageConfiguration";

        /**
         * Metadata that can be included in the app manifest to allow/disallow any window manager
         * downscaling interventions. Default value is TRUE.
         */
        public static final String METADATA_WM_ALLOW_DOWNSCALE =
                "com.android.graphics.intervention.wm.allowDownscale";

        /**
         * Metadata that can be included in the app manifest to allow/disallow any ANGLE
         * interventions. Default value is TRUE.
         */
        public static final String METADATA_ANGLE_ALLOW_ANGLE =
                "com.android.graphics.intervention.angle.allowAngle";

        /**
         * Metadata that needs to be included in the app manifest to OPT-IN to PERFORMANCE mode.
         * This means the app will assume full responsibility for the experience provided by this
         * mode and the system will enable no window manager downscaling.
         * Default value is FALSE
         */
        public static final String METADATA_PERFORMANCE_MODE_ENABLE =
                "com.android.app.gamemode.performance.enabled";

        /**
         * Metadata that needs to be included in the app manifest to OPT-IN to BATTERY mode.
         * This means the app will assume full responsibility for the experience provided by this
         * mode and the system will enable no window manager downscaling.
         * Default value is FALSE
         */
        public static final String METADATA_BATTERY_MODE_ENABLE =
                "com.android.app.gamemode.battery.enabled";

        /**
         * Metadata that allows a game to specify all intervention information with an XML file in
         * the application field.
         */
        public static final String METADATA_GAME_MODE_CONFIG = "android.game_mode_config";

        private static final String GAME_MODE_CONFIG_NODE_NAME = "game-mode-config";
        private final String mPackageName;
        private final Object mModeConfigLock = new Object();
        @GuardedBy("mModeConfigLock")
        private final ArrayMap<Integer, GameModeConfiguration> mModeConfigs = new ArrayMap<>();
        // if adding new properties or make any of the below overridable, the method
        // copyAndApplyOverride should be updated accordingly
        private boolean mPerfModeOptedIn = false;
        private boolean mBatteryModeOptedIn = false;
        private boolean mAllowDownscale = true;
        private boolean mAllowAngle = true;
        private boolean mAllowFpsOverride = true;

        GamePackageConfiguration(String packageName) {
            mPackageName = packageName;
        }

        GamePackageConfiguration(PackageManager packageManager, String packageName, int userId) {
            mPackageName = packageName;

            try {
                final ApplicationInfo ai = packageManager.getApplicationInfoAsUser(packageName,
                        PackageManager.GET_META_DATA, userId);
                if (!parseInterventionFromXml(packageManager, ai, packageName)
                            && ai.metaData != null) {
                    mPerfModeOptedIn = ai.metaData.getBoolean(METADATA_PERFORMANCE_MODE_ENABLE);
                    mBatteryModeOptedIn = ai.metaData.getBoolean(METADATA_BATTERY_MODE_ENABLE);
                    mAllowDownscale = ai.metaData.getBoolean(METADATA_WM_ALLOW_DOWNSCALE, true);
                    mAllowAngle = ai.metaData.getBoolean(METADATA_ANGLE_ALLOW_ANGLE, true);
                }
            } catch (NameNotFoundException e) {
                // Not all packages are installed, hence ignore those that are not installed yet.
                Slog.v(TAG, "Failed to get package metadata");
            }
            final String configString = DeviceConfig.getProperty(
                    DeviceConfig.NAMESPACE_GAME_OVERLAY, packageName);
            if (configString != null) {
                final String[] gameModeConfigStrings = configString.split(":");
                for (String gameModeConfigString : gameModeConfigStrings) {
                    try {
                        final KeyValueListParser parser = new KeyValueListParser(',');
                        parser.setString(gameModeConfigString);
                        addModeConfig(new GameModeConfiguration(parser));
                    } catch (IllegalArgumentException e) {
                        Slog.e(TAG, "Invalid config string");
                    }
                }
            }
        }

        private boolean parseInterventionFromXml(PackageManager packageManager, ApplicationInfo ai,
                String packageName) {
            boolean xmlFound = false;
            try (XmlResourceParser parser = ai.loadXmlMetaData(packageManager,
                    METADATA_GAME_MODE_CONFIG)) {
                if (parser == null) {
                    Slog.v(TAG, "No " + METADATA_GAME_MODE_CONFIG
                            + " meta-data found for package " + mPackageName);
                } else {
                    xmlFound = true;
                    final Resources resources = packageManager.getResourcesForApplication(
                            packageName);
                    final AttributeSet attributeSet = Xml.asAttributeSet(parser);
                    int type;
                    while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                            && type != XmlPullParser.START_TAG) {
                        // Do nothing
                    }

                    boolean isStartingTagGameModeConfig =
                            GAME_MODE_CONFIG_NODE_NAME.equals(parser.getName());
                    if (!isStartingTagGameModeConfig) {
                        Slog.w(TAG, "Meta-data does not start with "
                                + GAME_MODE_CONFIG_NODE_NAME
                                + " tag");
                    } else {
                        final TypedArray array = resources.obtainAttributes(attributeSet,
                                com.android.internal.R.styleable.GameModeConfig);
                        mPerfModeOptedIn = array.getBoolean(
                                GameModeConfig_supportsPerformanceGameMode, false);
                        mBatteryModeOptedIn = array.getBoolean(
                                GameModeConfig_supportsBatteryGameMode,
                                false);
                        mAllowDownscale = array.getBoolean(GameModeConfig_allowGameDownscaling,
                                true);
                        mAllowAngle = array.getBoolean(GameModeConfig_allowGameAngleDriver, true);
                        mAllowFpsOverride = array.getBoolean(GameModeConfig_allowGameFpsOverride,
                                true);
                        array.recycle();
                    }
                }
            } catch (NameNotFoundException | XmlPullParserException | IOException ex) {
                // set flag back to default values when parsing fails
                mPerfModeOptedIn = false;
                mBatteryModeOptedIn = false;
                mAllowDownscale = true;
                mAllowAngle = true;
                mAllowFpsOverride = true;
                Slog.e(TAG, "Error while parsing XML meta-data for "
                        + METADATA_GAME_MODE_CONFIG);
            }
            return xmlFound;
        }

        GameModeConfiguration getOrAddDefaultGameModeConfiguration(int gameMode) {
            synchronized (mModeConfigLock) {
                mModeConfigs.putIfAbsent(gameMode, new GameModeConfiguration(gameMode));
                return mModeConfigs.get(gameMode);
            }
        }

        /**
         * GameModeConfiguration contains all the values for all the interventions associated with
         * a game mode.
         */
        public class GameModeConfiguration {
            public static final String TAG = "GameManagerService_GameModeConfiguration";
            public static final String MODE_KEY = "mode";
            public static final String SCALING_KEY = "downscaleFactor";
            public static final String FPS_KEY = "fps";
            public static final String ANGLE_KEY = "useAngle";
            public static final String LOADING_BOOST_KEY = "loadingBoost";

            public static final float DEFAULT_SCALING = -1f;
            public static final String DEFAULT_FPS = "";
            public static final boolean DEFAULT_USE_ANGLE = false;
            public static final int DEFAULT_LOADING_BOOST_DURATION = -1;

            private final @GameMode int mGameMode;
            private float mScaling = DEFAULT_SCALING;
            private String mFps = DEFAULT_FPS;
            private boolean mUseAngle;
            private int mLoadingBoostDuration;

            GameModeConfiguration(int gameMode) {
                mGameMode = gameMode;
                mUseAngle = DEFAULT_USE_ANGLE;
                mLoadingBoostDuration = DEFAULT_LOADING_BOOST_DURATION;
            }

            GameModeConfiguration(KeyValueListParser parser) {
                mGameMode = parser.getInt(MODE_KEY, GameManager.GAME_MODE_UNSUPPORTED);
                // isGameModeOptedIn() returns if an app will handle all of the changes necessary
                // for a particular game mode. If so, the Android framework (i.e.
                // GameManagerService) will not do anything for the app (like window scaling or
                // using ANGLE).
                mScaling = !mAllowDownscale || willGamePerformOptimizations(mGameMode)
                        ? DEFAULT_SCALING : parser.getFloat(SCALING_KEY, DEFAULT_SCALING);

                mFps = mAllowFpsOverride && !willGamePerformOptimizations(mGameMode)
                        ? parser.getString(FPS_KEY, DEFAULT_FPS) : DEFAULT_FPS;
                // We only want to use ANGLE if:
                // - We're allowed to use ANGLE (the app hasn't opted out via the manifest) AND
                // - The app has not opted in to performing the work itself AND
                // - The Phenotype config has enabled it.
                mUseAngle = mAllowAngle && !willGamePerformOptimizations(mGameMode)
                        && parser.getBoolean(ANGLE_KEY, DEFAULT_USE_ANGLE);

                mLoadingBoostDuration = willGamePerformOptimizations(mGameMode)
                        ? DEFAULT_LOADING_BOOST_DURATION
                        : parser.getInt(LOADING_BOOST_KEY, DEFAULT_LOADING_BOOST_DURATION);
            }

            public int getGameMode() {
                return mGameMode;
            }

            public synchronized float getScaling() {
                return mScaling;
            }

            public synchronized int getFps() {
                return GameManagerService.getFpsInt(mFps);
            }

            synchronized String getFpsStr() {
                return mFps;
            }

            public synchronized boolean getUseAngle() {
                return mUseAngle;
            }

            public synchronized int getLoadingBoostDuration() {
                return mLoadingBoostDuration;
            }

            public synchronized void setScaling(float scaling) {
                mScaling = scaling;
            }

            public synchronized void setFpsStr(String fpsStr) {
                mFps = fpsStr;
            }

            public synchronized void setUseAngle(boolean useAngle) {
                mUseAngle = useAngle;
            }

            public synchronized void setLoadingBoostDuration(int loadingBoostDuration) {
                mLoadingBoostDuration = loadingBoostDuration;
            }

            public boolean isActive() {
                return (mGameMode == GameManager.GAME_MODE_STANDARD
                        || mGameMode == GameManager.GAME_MODE_PERFORMANCE
                        || mGameMode == GameManager.GAME_MODE_BATTERY)
                        && !willGamePerformOptimizations(mGameMode);
            }

            /**
             * @hide
             */
            public String toString() {
                return "[Game Mode:" + mGameMode + ",Scaling:" + mScaling + ",Use Angle:"
                        + mUseAngle + ",Fps:" + mFps + ",Loading Boost Duration:"
                        + mLoadingBoostDuration + "]";
            }
        }

        public String getPackageName() {
            return mPackageName;
        }

        /**
         * Returns if the app will assume full responsibility for the experience provided by this
         * mode. If True, the system will not perform any interventions for the app.
         *
         * @return True if the app package has specified in its metadata either:
         * "com.android.app.gamemode.performance.enabled" or
         * "com.android.app.gamemode.battery.enabled" with a value of "true"
         */
        public boolean willGamePerformOptimizations(@GameMode int gameMode) {
            return (mBatteryModeOptedIn && gameMode == GameManager.GAME_MODE_BATTERY)
                    || (mPerfModeOptedIn && gameMode == GameManager.GAME_MODE_PERFORMANCE);
        }

        private int getAvailableGameModesBitfield() {
            int field = 0;
            synchronized (mModeConfigLock) {
                for (final int mode : mModeConfigs.keySet()) {
                    field |= modeToBitmask(mode);
                }
            }
            if (mBatteryModeOptedIn) {
                field |= modeToBitmask(GameManager.GAME_MODE_BATTERY);
            }
            if (mPerfModeOptedIn) {
                field |= modeToBitmask(GameManager.GAME_MODE_PERFORMANCE);
            }
            // The lowest bit is reserved for UNSUPPORTED, STANDARD is supported if we support any
            // other mode.
            if (field > 1) {
                field |= modeToBitmask(GameManager.GAME_MODE_STANDARD);
            } else {
                field |= modeToBitmask(GameManager.GAME_MODE_UNSUPPORTED);
            }
            return field;
        }

        /**
         * Get an array of a package's available game modes.
         */
        public @GameMode int[] getAvailableGameModes() {
            final int modesBitfield = getAvailableGameModesBitfield();
            int[] modes = new int[Integer.bitCount(modesBitfield)];
            int i = 0;
            final int gameModeInHighestBit =
                    Integer.numberOfTrailingZeros(Integer.highestOneBit(modesBitfield));
            for (int mode = 0; mode <= gameModeInHighestBit; ++mode) {
                if (((modesBitfield >> mode) & 1) != 0) {
                    modes[i++] = mode;
                }
            }
            return modes;
        }

        /**
         * Get a GameModeConfiguration for a given game mode.
         *
         * @return The package's GameModeConfiguration for the provided mode or null if absent
         */
        public GameModeConfiguration getGameModeConfiguration(@GameMode int gameMode) {
            synchronized (mModeConfigLock) {
                return mModeConfigs.get(gameMode);
            }
        }

        /**
         * Inserts a new GameModeConfiguration.
         */
        public void addModeConfig(GameModeConfiguration config) {
            if (config.isActive()) {
                synchronized (mModeConfigLock) {
                    mModeConfigs.put(config.getGameMode(), config);
                }
            } else {
                Slog.w(TAG, "Attempt to add inactive game mode config for "
                        + mPackageName + ":" + config.toString());
            }
        }

        /**
         * Removes the GameModeConfiguration.
         */
        public void removeModeConfig(int mode) {
            synchronized (mModeConfigLock) {
                mModeConfigs.remove(mode);
            }
        }

        public boolean isActive() {
            synchronized (mModeConfigLock) {
                return mModeConfigs.size() > 0 || mBatteryModeOptedIn || mPerfModeOptedIn;
            }
        }

        GamePackageConfiguration copyAndApplyOverride(GamePackageConfiguration overrideConfig) {
            GamePackageConfiguration copy = new GamePackageConfiguration(mPackageName);
            // if a game mode is overridden, we treat it with the highest priority and reset any
            // opt-in game modes so that interventions are always executed.
            copy.mPerfModeOptedIn = mPerfModeOptedIn && !(overrideConfig != null
                    && overrideConfig.getGameModeConfiguration(GameManager.GAME_MODE_PERFORMANCE)
                    != null);
            copy.mBatteryModeOptedIn = mBatteryModeOptedIn && !(overrideConfig != null
                    && overrideConfig.getGameModeConfiguration(GameManager.GAME_MODE_BATTERY)
                    != null);

            // if any game mode is overridden, we will consider all interventions forced-active,
            // this can be done more granular by checking if a specific intervention is
            // overridden under each game mode override, but only if necessary.
            copy.mAllowDownscale = mAllowDownscale || overrideConfig != null;
            copy.mAllowAngle = mAllowAngle || overrideConfig != null;
            copy.mAllowFpsOverride = mAllowFpsOverride || overrideConfig != null;
            if (overrideConfig != null) {
                synchronized (copy.mModeConfigLock) {
                    synchronized (mModeConfigLock) {
                        for (Map.Entry<Integer, GameModeConfiguration> entry :
                                mModeConfigs.entrySet()) {
                            copy.mModeConfigs.put(entry.getKey(), entry.getValue());
                        }
                    }
                    synchronized (overrideConfig.mModeConfigLock) {
                        for (Map.Entry<Integer, GameModeConfiguration> entry :
                                overrideConfig.mModeConfigs.entrySet()) {
                            copy.mModeConfigs.put(entry.getKey(), entry.getValue());
                        }
                    }
                }
            }
            return copy;
        }

        public String toString() {
            synchronized (mModeConfigLock) {
                return "[Name:" + mPackageName + " Modes: " + mModeConfigs.toString() + "]";
            }
        }
    }

    private final class LocalService extends GameManagerInternal {
        @Override
        public float getResolutionScalingFactor(String packageName, int userId) {
            final int gameMode = getGameModeFromSettings(packageName, userId);
            return getResolutionScalingFactorInternal(packageName, gameMode, userId);
        }
    }

    /**
     * SystemService lifecycle for GameService.
     *
     * @hide
     */
    public static class Lifecycle extends SystemService {
        private GameManagerService mService;

        public Lifecycle(Context context) {
            super(context);
            mService = new GameManagerService(context);
        }

        @Override
        public void onStart() {
            publishBinderService(Context.GAME_SERVICE, mService);
            mService.publishLocalService();
            mService.registerDeviceConfigListener();
            mService.registerPackageReceiver();
        }

        @Override
        public void onBootPhase(int phase) {
            if (phase == PHASE_BOOT_COMPLETED) {
                mService.onBootCompleted();
            }
        }

        @Override
        public void onUserStarting(@NonNull TargetUser user) {
            Slog.d(TAG, "Starting user " + user.getUserIdentifier());
            mService.onUserStarting(user,
                    Environment.getDataSystemDeDirectory(user.getUserIdentifier()));
        }

        @Override
        public void onUserUnlocking(@NonNull TargetUser user) {
            mService.onUserUnlocking(user);
        }

        @Override
        public void onUserStopping(@NonNull TargetUser user) {
            mService.onUserStopping(user);
        }

        @Override
        public void onUserSwitching(@Nullable TargetUser from, @NonNull TargetUser to) {
            mService.onUserSwitching(from, to);
        }
    }

    private boolean isValidPackageName(String packageName, int userId) {
        try {
            return mPackageManager.getPackageUidAsUser(packageName, userId)
                    == Binder.getCallingUid();
        } catch (NameNotFoundException e) {
            return false;
        }
    }

    private void checkPermission(String permission) throws SecurityException {
        if (mContext.checkCallingOrSelfPermission(permission)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Access denied to process: " + Binder.getCallingPid()
                    + ", must have permission " + permission);
        }
    }

    private @GameMode int[] getAvailableGameModesUnchecked(String packageName) {
        final GamePackageConfiguration config;
        synchronized (mDeviceConfigLock) {
            config = mConfigs.get(packageName);
        }
        if (config == null) {
            return new int[]{};
        }
        return config.getAvailableGameModes();
    }

    private boolean isPackageGame(String packageName, @UserIdInt int userId) {
        try {
            final ApplicationInfo applicationInfo = mPackageManager
                    .getApplicationInfoAsUser(packageName, PackageManager.MATCH_ALL, userId);
            return applicationInfo.category == ApplicationInfo.CATEGORY_GAME;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Get an array of game modes available for a given package.
     * Checks that the caller has {@link android.Manifest.permission#MANAGE_GAME_MODE}.
     */
    @Override
    @RequiresPermission(Manifest.permission.MANAGE_GAME_MODE)
    public @GameMode int[] getAvailableGameModes(String packageName) throws SecurityException {
        checkPermission(Manifest.permission.MANAGE_GAME_MODE);
        return getAvailableGameModesUnchecked(packageName);
    }

    private @GameMode int getGameModeFromSettings(String packageName, @UserIdInt int userId) {
        synchronized (mLock) {
            if (!mSettings.containsKey(userId)) {
                Slog.d(TAG, "User ID '" + userId + "' does not have a Game Mode"
                            + " selected for package: '" + packageName + "'");
                return GameManager.GAME_MODE_UNSUPPORTED;
            }

            return mSettings.get(userId).getGameModeLocked(packageName);
        }
    }

    /**
     * Get the Game Mode for the package name.
     * Verifies that the calling process is for the matching package UID or has
     * {@link android.Manifest.permission#MANAGE_GAME_MODE}.
     */
    @Override
    public @GameMode int getGameMode(@NonNull String packageName, @UserIdInt int userId)
            throws SecurityException {
        userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), userId, false, true, "getGameMode",
                "com.android.server.app.GameManagerService");

        // Restrict to games only.
        if (!isPackageGame(packageName, userId)) {
            // The game mode for applications that are not identified as game is always
            // UNSUPPORTED. See {@link PackageManager#setApplicationCategoryHint(String, int)}
            return GameManager.GAME_MODE_UNSUPPORTED;
        }

        // This function handles two types of queries:
        // 1) A normal, non-privileged app querying its own Game Mode.
        // 2) A privileged system service querying the Game Mode of another package.
        // The least privileged case is a normal app performing a query, so check that first and
        // return a value if the package name is valid. Next, check if the caller has the necessary
        // permission and return a value. Do this check last, since it can throw an exception.
        if (isValidPackageName(packageName, userId)) {
            return getGameModeFromSettings(packageName, userId);
        }

        // Since the package name doesn't match, check the caller has the necessary permission.
        checkPermission(Manifest.permission.MANAGE_GAME_MODE);
        return getGameModeFromSettings(packageName, userId);
    }

    /**
     * Get the GameModeInfo for the package name.
     * Verifies that the calling process is for the matching package UID or has
     * {@link android.Manifest.permission#MANAGE_GAME_MODE}. If the package is not a game,
     * null is always returned.
     */
    @Override
    @RequiresPermission(Manifest.permission.MANAGE_GAME_MODE)
    @Nullable
    public GameModeInfo getGameModeInfo(@NonNull String packageName, @UserIdInt int userId) {
        userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), userId, false, true, "getGameModeInfo",
                "com.android.server.app.GameManagerService");

        // Check the caller has the necessary permission.
        checkPermission(Manifest.permission.MANAGE_GAME_MODE);

        // Restrict to games only.
        if (!isPackageGame(packageName, userId)) {
            return null;
        }

        final @GameMode int activeGameMode = getGameModeFromSettings(packageName, userId);
        final @GameMode int[] availableGameModes = getAvailableGameModesUnchecked(packageName);

        return new GameModeInfo(activeGameMode, availableGameModes);
    }

    /**
     * Sets the Game Mode for the package name.
     * Verifies that the calling process has {@link android.Manifest.permission#MANAGE_GAME_MODE}.
     */
    @Override
    @RequiresPermission(Manifest.permission.MANAGE_GAME_MODE)
    public void setGameMode(String packageName, @GameMode int gameMode, int userId)
            throws SecurityException {
        checkPermission(Manifest.permission.MANAGE_GAME_MODE);

        if (!isPackageGame(packageName, userId)) {
            // Restrict to games only.
            return;
        }

        synchronized (mLock) {
            userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, false, true, "setGameMode",
                    "com.android.server.app.GameManagerService");

            if (!mSettings.containsKey(userId)) {
                Slog.d(TAG, "Failed to set game mode for package " + packageName
                        + " as user " + userId + " is not started");
                return;
            }
            GameManagerSettings userSettings = mSettings.get(userId);
            userSettings.setGameModeLocked(packageName, gameMode);
        }
        updateInterventions(packageName, gameMode, userId);
        sendUserMessage(userId, WRITE_SETTINGS, "SET_GAME_MODE", WRITE_DELAY_MILLIS);
        sendUserMessage(userId, WRITE_GAME_MODE_INTERVENTION_LIST_FILE,
                "SET_GAME_MODE", 0 /*delayMillis*/);
    }

    /**
     * Get if ANGLE is enabled for the package for the currently enabled game mode.
     * Checks that the caller has {@link android.Manifest.permission#MANAGE_GAME_MODE}.
     */
    @Override
    @RequiresPermission(Manifest.permission.MANAGE_GAME_MODE)
    public @GameMode boolean isAngleEnabled(String packageName, int userId)
            throws SecurityException {
        final int gameMode = getGameMode(packageName, userId);
        if (gameMode == GameManager.GAME_MODE_UNSUPPORTED) {
            return false;
        }
        final GamePackageConfiguration config;
        synchronized (mDeviceConfigLock) {
            config = mConfigs.get(packageName);
            if (config == null) {
                return false;
            }
        }
        GamePackageConfiguration.GameModeConfiguration gameModeConfiguration =
                config.getGameModeConfiguration(gameMode);
        if (gameModeConfiguration == null) {
            return false;
        }
        return gameModeConfiguration.getUseAngle();
    }

    /**
     * If loading boost is applicable for the package for the currently enabled game mode, return
     * the boost duration. If no configuration is available for the selected package or mode, the
     * default is returned.
     */
    public int getLoadingBoostDuration(String packageName, int userId)
            throws SecurityException {
        final int gameMode = getGameMode(packageName, userId);
        if (gameMode == GameManager.GAME_MODE_UNSUPPORTED) {
            return -1;
        }
        final GamePackageConfiguration config;
        synchronized (mDeviceConfigLock) {
            config = mConfigs.get(packageName);
        }
        if (config == null) {
            return -1;
        }
        GamePackageConfiguration.GameModeConfiguration gameModeConfiguration =
                config.getGameModeConfiguration(gameMode);
        if (gameModeConfiguration == null) {
            return -1;
        }
        return gameModeConfiguration.getLoadingBoostDuration();
    }

    /**
     * If loading boost is enabled, invoke it.
     */
    @Override
    @RequiresPermission(Manifest.permission.MANAGE_GAME_MODE)
    @GameMode public void notifyGraphicsEnvironmentSetup(String packageName, int userId)
            throws SecurityException {
        userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), userId, false, true, "notifyGraphicsEnvironmentSetup",
                "com.android.server.app.GameManagerService");

        // Restrict to games only.
        if (!isPackageGame(packageName, userId)) {
            return;
        }

        if (!isValidPackageName(packageName, userId)) {
            return;
        }

        final int gameMode = getGameMode(packageName, userId);
        if (gameMode == GameManager.GAME_MODE_UNSUPPORTED) {
            return;
        }
        int loadingBoostDuration = getLoadingBoostDuration(packageName, userId);
        if (loadingBoostDuration != -1) {
            if (loadingBoostDuration == 0 || loadingBoostDuration > LOADING_BOOST_MAX_DURATION) {
                loadingBoostDuration = LOADING_BOOST_MAX_DURATION;
            }
            if (mHandler.hasMessages(CANCEL_GAME_LOADING_MODE)) {
                // The loading mode has already been set and is waiting to be unset. It is not
                // required to set the mode again and we should replace the queued cancel
                // instruction.
                mHandler.removeMessages(CANCEL_GAME_LOADING_MODE);
            } else {
                mPowerManagerInternal.setPowerMode(Mode.GAME_LOADING, true);
            }

            mHandler.sendMessageDelayed(
                    mHandler.obtainMessage(CANCEL_GAME_LOADING_MODE), loadingBoostDuration);
        }
    }

    /**
     * Sets the game service provider to a given package, meant for testing.
     *
     * <p>This setting persists until the next call or until the next reboot.
     *
     * <p>Checks that the caller has {@link android.Manifest.permission#SET_GAME_SERVICE}.
     */
    @Override
    @RequiresPermission(Manifest.permission.SET_GAME_SERVICE)
    public void setGameServiceProvider(@Nullable String packageName) throws SecurityException {
        checkPermission(Manifest.permission.SET_GAME_SERVICE);

        if (mGameServiceController == null) {
            return;
        }

        mGameServiceController.setGameServiceProvider(packageName);
    }


    /**
     * Updates the resolution scaling factor for the package's target game mode and activates it.
     *
     * @param scalingFactor enable scaling override over any other compat scaling if positive,
     *                      or disable the override otherwise
     * @throws SecurityException        if caller doesn't have
     *                                  {@link android.Manifest.permission#MANAGE_GAME_MODE}
     *                                  permission.
     * @throws IllegalArgumentException if the user ID provided doesn't exist.
     */
    @Override
    @RequiresPermission(Manifest.permission.MANAGE_GAME_MODE)
    public void updateResolutionScalingFactor(String packageName, int gameMode, float scalingFactor,
            int userId) throws SecurityException, IllegalArgumentException {
        checkPermission(Manifest.permission.MANAGE_GAME_MODE);
        synchronized (mLock) {
            if (!mSettings.containsKey(userId)) {
                throw new IllegalArgumentException("User " + userId + " wasn't started");
            }
        }
        setGameModeConfigOverride(packageName, userId, gameMode, null /*fpsStr*/,
                Float.toString(scalingFactor));
    }

    /**
     * Gets the resolution scaling factor for the package's target game mode.
     *
     * @return scaling factor for the game mode if exists or negative value otherwise.
     * @throws SecurityException        if caller doesn't have
     *                                  {@link android.Manifest.permission#MANAGE_GAME_MODE}
     *                                  permission.
     * @throws IllegalArgumentException if the user ID provided doesn't exist.
     */
    @Override
    @RequiresPermission(Manifest.permission.MANAGE_GAME_MODE)
    public float getResolutionScalingFactor(String packageName, int gameMode, int userId)
            throws SecurityException, IllegalArgumentException {
        checkPermission(Manifest.permission.MANAGE_GAME_MODE);
        synchronized (mLock) {
            if (!mSettings.containsKey(userId)) {
                throw new IllegalArgumentException("User " + userId + " wasn't started");
            }
        }
        return getResolutionScalingFactorInternal(packageName, gameMode, userId);
    }

    float getResolutionScalingFactorInternal(String packageName, int gameMode, int userId) {
        final GamePackageConfiguration packageConfig = getConfig(packageName, userId);
        if (packageConfig == null) {
            return GamePackageConfiguration.GameModeConfiguration.DEFAULT_SCALING;
        }
        final GamePackageConfiguration.GameModeConfiguration modeConfig =
                packageConfig.getGameModeConfiguration(gameMode);
        if (modeConfig != null) {
            return modeConfig.getScaling();
        }
        return GamePackageConfiguration.GameModeConfiguration.DEFAULT_SCALING;
    }

    /**
     * Notified when boot is completed.
     */
    @VisibleForTesting
    void onBootCompleted() {
        Slog.d(TAG, "onBootCompleted");
        if (mGameServiceController != null) {
            mGameServiceController.onBootComplete();
        }
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SHUTDOWN.equals(intent.getAction())) {
                    synchronized (mLock) {
                        // Note that the max wait time of broadcast is 10s (see
                        // {@ShutdownThread#MAX_BROADCAST_TIMEMAX_BROADCAST_TIME}) currently so
                        // this can be optional only if we have message delay plus processing
                        // time significant smaller to prevent data loss.
                        for (Map.Entry<Integer, GameManagerSettings> entry : mSettings.entrySet()) {
                            final int userId = entry.getKey();
                            sendUserMessage(userId, WRITE_SETTINGS,
                                    Intent.ACTION_SHUTDOWN, 0 /*delayMillis*/);
                            sendUserMessage(userId,
                                    WRITE_GAME_MODE_INTERVENTION_LIST_FILE, Intent.ACTION_SHUTDOWN,
                                    0 /*delayMillis*/);
                        }
                    }
                }
            }
        }, new IntentFilter(Intent.ACTION_SHUTDOWN));
    }

    private void sendUserMessage(int userId, int what, String eventForLog, int delayMillis) {
        Message msg = mHandler.obtainMessage(what, userId);
        if (!mHandler.sendMessageDelayed(msg, delayMillis)) {
            Slog.e(TAG, "Failed to send user message " + what + " on " + eventForLog);
        }
    }

    void onUserStarting(@NonNull TargetUser user, File settingDataDir) {
        final int userId = user.getUserIdentifier();
        synchronized (mLock) {
            if (!mSettings.containsKey(userId)) {
                GameManagerSettings userSettings = new GameManagerSettings(settingDataDir);
                mSettings.put(userId, userSettings);
                userSettings.readPersistentDataLocked();
            }
        }
        sendUserMessage(userId, POPULATE_GAME_MODE_SETTINGS, "ON_USER_STARTING", 0 /*delayMillis*/);

        if (mGameServiceController != null) {
            mGameServiceController.notifyUserStarted(user);
        }
    }

    void onUserUnlocking(@NonNull TargetUser user) {
        if (mGameServiceController != null) {
            mGameServiceController.notifyUserUnlocking(user);
        }
    }

    void onUserStopping(TargetUser user) {
        final int userId = user.getUserIdentifier();

        synchronized (mLock) {
            if (!mSettings.containsKey(userId)) {
                return;
            }
            sendUserMessage(userId, REMOVE_SETTINGS, "ON_USER_STOPPING", 0 /*delayMillis*/);
        }

        if (mGameServiceController != null) {
            mGameServiceController.notifyUserStopped(user);
        }
    }

    void onUserSwitching(TargetUser from, TargetUser to) {
        final int toUserId = to.getUserIdentifier();
        // we want to re-populate the setting when switching user as the device config may have
        // changed, which will only update for the previous user, see
        // DeviceConfigListener#onPropertiesChanged.
        sendUserMessage(toUserId, POPULATE_GAME_MODE_SETTINGS, "ON_USER_SWITCHING",
                0 /*delayMillis*/);

        if (mGameServiceController != null) {
            mGameServiceController.notifyNewForegroundUser(to);
        }
    }

    /**
     * Remove frame rate override due to mode switch
     */
    private void resetFps(String packageName, @UserIdInt int userId) {
        try {
            final float fps = 0.0f;
            final int uid = mPackageManager.getPackageUidAsUser(packageName, userId);
            setOverrideFrameRate(uid, fps);
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }
    }

    private static int modeToBitmask(@GameMode int gameMode) {
        return (1 << gameMode);
    }

    private boolean bitFieldContainsModeBitmask(int bitField, @GameMode int gameMode) {
        return (bitField & modeToBitmask(gameMode)) != 0;
    }

    @RequiresPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
    private void updateUseAngle(String packageName, @GameMode int gameMode) {
        // TODO (b/188475576): Nothing to do yet. Remove if it's still empty when we're ready to
        // ship.
    }


    private void updateFps(GamePackageConfiguration packageConfig, String packageName,
            @GameMode int gameMode, @UserIdInt int userId) {
        final GamePackageConfiguration.GameModeConfiguration modeConfig =
                packageConfig.getGameModeConfiguration(gameMode);
        if (modeConfig == null) {
            Slog.d(TAG, "Game mode " + gameMode + " not found for " + packageName);
            return;
        }
        try {
            final float fps = modeConfig.getFps();
            final int uid = mPackageManager.getPackageUidAsUser(packageName, userId);
            setOverrideFrameRate(uid, fps);
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }
    }


    private void updateInterventions(String packageName,
            @GameMode int gameMode, @UserIdInt int userId) {
        final GamePackageConfiguration packageConfig = getConfig(packageName, userId);
        if (gameMode == GameManager.GAME_MODE_STANDARD
                || gameMode == GameManager.GAME_MODE_UNSUPPORTED || packageConfig == null
                || packageConfig.willGamePerformOptimizations(gameMode)) {
            resetFps(packageName, userId);
            // resolution scaling does not need to be reset as it's now read dynamically on game
            // restart, see #getResolutionScalingFactor and CompatModePackages#getCompatScale.
            // TODO: reset Angle intervention here once implemented
            if (packageConfig == null) {
                Slog.v(TAG, "Package configuration not found for " + packageName);
                return;
            }
        } else {
            updateFps(packageConfig, packageName, gameMode, userId);
        }
        updateUseAngle(packageName, gameMode);
    }

    /**
     * Set the Game Mode Configuration override.
     * Update the config if exists, create one if not.
     */
    @VisibleForTesting
    @RequiresPermission(Manifest.permission.MANAGE_GAME_MODE)
    public void setGameModeConfigOverride(String packageName, @UserIdInt int userId,
            @GameMode int gameMode, String fpsStr, String scaling) throws SecurityException {
        checkPermission(Manifest.permission.MANAGE_GAME_MODE);
        // Adding game mode config override of the given package name
        GamePackageConfiguration configOverride;
        synchronized (mLock) {
            if (!mSettings.containsKey(userId)) {
                return;
            }
            final GameManagerSettings settings = mSettings.get(userId);
            // look for the existing GamePackageConfiguration override
            configOverride = settings.getConfigOverride(packageName);
            if (configOverride == null) {
                configOverride = new GamePackageConfiguration(packageName);
                settings.setConfigOverride(packageName, configOverride);
            }
        }
        // modify GameModeConfiguration intervention settings
        GamePackageConfiguration.GameModeConfiguration modeConfigOverride =
                configOverride.getOrAddDefaultGameModeConfiguration(gameMode);

        if (fpsStr != null) {
            modeConfigOverride.setFpsStr(fpsStr);
        } else {
            modeConfigOverride.setFpsStr(
                    GamePackageConfiguration.GameModeConfiguration.DEFAULT_FPS);
        }
        if (scaling != null) {
            modeConfigOverride.setScaling(Float.parseFloat(scaling));
        }
        Slog.i(TAG, "Package Name: " + packageName
                + " FPS: " + String.valueOf(modeConfigOverride.getFps())
                + " Scaling: " + modeConfigOverride.getScaling());
        setGameMode(packageName, gameMode, userId);
    }

    /**
     * Reset the overridden gameModeConfiguration of the given mode.
     * Remove the config override if game mode is not specified.
     */
    @VisibleForTesting
    @RequiresPermission(Manifest.permission.MANAGE_GAME_MODE)
    public void resetGameModeConfigOverride(String packageName, @UserIdInt int userId,
            @GameMode int gameModeToReset) throws SecurityException {
        checkPermission(Manifest.permission.MANAGE_GAME_MODE);
        final GamePackageConfiguration deviceConfig;
        synchronized (mDeviceConfigLock) {
            deviceConfig = mConfigs.get(packageName);
        }

        // resets GamePackageConfiguration of a given packageName.
        // If a gameMode is specified, only reset the GameModeConfiguration of the gameMode.
        synchronized (mLock) {
            if (!mSettings.containsKey(userId)) {
                return;
            }
            final GameManagerSettings settings = mSettings.get(userId);
            if (gameModeToReset != -1) {
                final GamePackageConfiguration configOverride = settings.getConfigOverride(
                        packageName);
                if (configOverride == null) {
                    return;
                }
                final int modesBitfield = configOverride.getAvailableGameModesBitfield();
                if (!bitFieldContainsModeBitmask(modesBitfield, gameModeToReset)) {
                    return;
                }
                // if the game mode to reset is the only mode other than standard mode or there
                // is device config, the entire package config override is removed.
                if (Integer.bitCount(modesBitfield) <= 2 || deviceConfig == null) {
                    settings.removeConfigOverride(packageName);
                } else {
                    // otherwise we reset the mode by removing the game mode config override
                    configOverride.removeModeConfig(gameModeToReset);
                }
            } else {
                settings.removeConfigOverride(packageName);
            }
        }

        // Make sure after resetting the game mode is still supported.
        // If not, set the game mode to standard
        int gameMode = getGameMode(packageName, userId);

        final GamePackageConfiguration config = getConfig(packageName, userId);
        final int newGameMode = getNewGameMode(gameMode, config);
        if (gameMode != newGameMode) {
            setGameMode(packageName, GameManager.GAME_MODE_STANDARD, userId);
            return;
        }
        setGameMode(packageName, gameMode, userId);
    }

    private int getNewGameMode(int gameMode, GamePackageConfiguration config) {
        int newGameMode = gameMode;
        if (config != null) {
            int modesBitfield = config.getAvailableGameModesBitfield();
            // Remove UNSUPPORTED to simplify the logic here, since we really just
            // want to check if we support selectable game modes
            modesBitfield &= ~modeToBitmask(GameManager.GAME_MODE_UNSUPPORTED);
            if (!bitFieldContainsModeBitmask(modesBitfield, gameMode)) {
                if (bitFieldContainsModeBitmask(modesBitfield,
                        GameManager.GAME_MODE_STANDARD)) {
                    // If the current set mode isn't supported,
                    // but we support STANDARD, then set the mode to STANDARD.
                    newGameMode = GameManager.GAME_MODE_STANDARD;
                } else {
                    // If we don't support any game modes, then set to UNSUPPORTED
                    newGameMode = GameManager.GAME_MODE_UNSUPPORTED;
                }
            }
        } else if (gameMode != GameManager.GAME_MODE_UNSUPPORTED) {
            // If we have no config for the package, but the configured mode is not
            // UNSUPPORTED, then set to UNSUPPORTED
            newGameMode = GameManager.GAME_MODE_UNSUPPORTED;
        }
        return newGameMode;
    }

    /**
     * Returns the string listing all the interventions currently set to a game.
     */
    public String getInterventionList(String packageName, int userId) {
        final GamePackageConfiguration packageConfig = getConfig(packageName, userId);
        final StringBuilder listStrSb = new StringBuilder();
        if (packageConfig == null) {
            listStrSb.append("\n No intervention found for package ")
                    .append(packageName);
            return listStrSb.toString();
        }
        listStrSb.append("\n")
                .append(packageConfig.toString());
        return listStrSb.toString();
    }

    /**
     * @hide
     */
    @VisibleForTesting
    void updateConfigsForUser(@UserIdInt int userId, boolean checkGamePackage,
            String... packageNames) {
        if (checkGamePackage) {
            packageNames = Arrays.stream(packageNames).filter(
                    p -> isPackageGame(p, userId)).toArray(String[]::new);
        }
        try {
            synchronized (mDeviceConfigLock) {
                for (final String packageName : packageNames) {
                    final GamePackageConfiguration config =
                            new GamePackageConfiguration(mPackageManager, packageName, userId);
                    if (config.isActive()) {
                        if (DEBUG) {
                            Slog.i(TAG, "Adding config: " + config.toString());
                        }
                        mConfigs.put(packageName, config);
                    } else {
                        if (DEBUG) {
                            Slog.w(TAG, "Inactive package config for "
                                    + config.getPackageName() + ":" + config.toString());
                        }
                        mConfigs.remove(packageName);
                    }
                }
            }
            synchronized (mLock) {
                if (!mSettings.containsKey(userId)) {
                    return;
                }
            }
            for (final String packageName : packageNames) {
                int gameMode = getGameMode(packageName, userId);
                // Make sure the user settings and package configs don't conflict.
                // I.e. the user setting is set to a mode that no longer available due to
                // config/manifest changes.
                // Most of the time we won't have to change anything.
                GamePackageConfiguration config = null;
                synchronized (mDeviceConfigLock) {
                    config = mConfigs.get(packageName);
                }
                final int newGameMode = getNewGameMode(gameMode, config);
                if (newGameMode != gameMode) {
                    setGameMode(packageName, newGameMode, userId);
                } else {
                    // Make sure we handle the case when the interventions are changed while
                    // the game mode remains the same. We call only updateInterventions() here.
                    updateInterventions(packageName, gameMode, userId);
                }
            }
            sendUserMessage(userId, WRITE_GAME_MODE_INTERVENTION_LIST_FILE,
                    "UPDATE_CONFIGS_FOR_USERS", 0 /*delayMillis*/);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to update configs for user " + userId + ": " + e);
        }
    }

    /*
     Write the interventions and mode of each game to file /system/data/game_mode_intervention.list
     Each line will contain the information of each game, separated by tab.
     The format of the output is:
     <package name> <UID> <current mode> <game mode 1> <interventions> <game mode 2> <interventions>
     For example:
     com.android.app1   1425    1   2   angle=0,scaling=1.0,fps=60  3   angle=1,scaling=0.5,fps=30
     */
    private void writeGameModeInterventionsToFile(@UserIdInt int userId) {
        FileOutputStream fileOutputStream = null;
        BufferedWriter bufferedWriter;
        try {
            fileOutputStream = mGameModeInterventionListFile.startWrite();
            bufferedWriter = new BufferedWriter(new OutputStreamWriter(fileOutputStream,
                    Charset.defaultCharset()));

            final StringBuilder sb = new StringBuilder();
            final List<String> installedGamesList = getInstalledGamePackageNamesByAllUsers(userId);
            for (final String packageName : installedGamesList) {
                GamePackageConfiguration packageConfig = getConfig(packageName, userId);
                if (packageConfig == null) {
                    continue;
                }
                sb.append(packageName);
                sb.append("\t");
                sb.append(mPackageManager.getPackageUidAsUser(packageName, userId));
                sb.append("\t");
                sb.append(getGameMode(packageName, userId));
                sb.append("\t");
                final int[] modes = packageConfig.getAvailableGameModes();
                for (int mode : modes) {
                    final GamePackageConfiguration.GameModeConfiguration gameModeConfiguration =
                            packageConfig.getGameModeConfiguration(mode);
                    if (gameModeConfiguration == null) {
                        continue;
                    }
                    sb.append(mode);
                    sb.append("\t");
                    final int useAngle = gameModeConfiguration.getUseAngle() ? 1 : 0;
                    sb.append(TextUtils.formatSimple("angle=%d", useAngle));
                    sb.append(",");
                    final float scaling = gameModeConfiguration.getScaling();
                    sb.append("scaling=");
                    sb.append(scaling);
                    sb.append(",");
                    final int fps = gameModeConfiguration.getFps();
                    sb.append(TextUtils.formatSimple("fps=%d", fps));
                    sb.append("\t");
                }
                sb.append("\n");
            }
            bufferedWriter.append(sb);
            bufferedWriter.flush();
            FileUtils.sync(fileOutputStream);
            mGameModeInterventionListFile.finishWrite(fileOutputStream);
        } catch (Exception e) {
            mGameModeInterventionListFile.failWrite(fileOutputStream);
            Slog.wtf(TAG, "Failed to write game_mode_intervention.list, exception " + e);
        }
        return;
    }

    private int[] getAllUserIds(@UserIdInt int currentUserId) {
        final List<UserInfo> users = mUserManager.getUsers();
        int[] userIds = new int[users.size()];
        for (int i = 0; i < userIds.length; ++i) {
            userIds[i] = users.get(i).id;
        }
        if (currentUserId != -1) {
            userIds = ArrayUtils.appendInt(userIds, currentUserId);
        }
        return userIds;
    }

    private String[] getInstalledGamePackageNames(@UserIdInt int userId) {
        final List<PackageInfo> packages =
                mPackageManager.getInstalledPackagesAsUser(0, userId);
        return packages.stream().filter(e -> e.applicationInfo != null && e.applicationInfo.category
                        == ApplicationInfo.CATEGORY_GAME)
                .map(e -> e.packageName)
                .toArray(String[]::new);
    }

    private List<String> getInstalledGamePackageNamesByAllUsers(@UserIdInt int currentUserId) {
        HashSet<String> packageSet = new HashSet<>();

        final int[] userIds = getAllUserIds(currentUserId);
        for (int userId : userIds) {
            packageSet.addAll(Arrays.asList(getInstalledGamePackageNames(userId)));
        }

        return new ArrayList<>(packageSet);
    }

    /**
     * @hide
     */
    public GamePackageConfiguration getConfig(String packageName, int userId) {
        GamePackageConfiguration overrideConfig = null;
        GamePackageConfiguration config;
        synchronized (mDeviceConfigLock) {
            config = mConfigs.get(packageName);
        }

        synchronized (mLock) {
            if (mSettings.containsKey(userId)) {
                overrideConfig = mSettings.get(userId).getConfigOverride(packageName);
            }
        }
        if (overrideConfig == null || config == null) {
            return overrideConfig == null ? config : overrideConfig;
        }
        return config.copyAndApplyOverride(overrideConfig);
    }

    private void registerPackageReceiver() {
        final IntentFilter packageFilter = new IntentFilter();
        packageFilter.addAction(ACTION_PACKAGE_ADDED);
        packageFilter.addAction(ACTION_PACKAGE_REMOVED);
        packageFilter.addDataScheme("package");
        final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(@NonNull final Context context, @NonNull final Intent intent) {
                final Uri data = intent.getData();
                try {
                    final int userId = getSendingUserId();
                    if (userId != ActivityManager.getCurrentUser()) {
                        return;
                    }
                    final String packageName = data.getSchemeSpecificPart();
                    try {
                        final ApplicationInfo applicationInfo = mPackageManager
                                .getApplicationInfoAsUser(
                                        packageName, PackageManager.MATCH_ALL, userId);
                        if (applicationInfo.category != ApplicationInfo.CATEGORY_GAME) {
                            return;
                        }
                    } catch (NameNotFoundException e) {
                        // Ignore the exception.
                    }
                    switch (intent.getAction()) {
                        case ACTION_PACKAGE_ADDED:
                            updateConfigsForUser(userId, true /*checkGamePackage*/, packageName);
                            break;
                        case ACTION_PACKAGE_REMOVED:
                            if (!intent.getBooleanExtra(EXTRA_REPLACING, false)) {
                                synchronized (mDeviceConfigLock) {
                                    mConfigs.remove(packageName);
                                }
                                synchronized (mLock) {
                                    if (mSettings.containsKey(userId)) {
                                        mSettings.get(userId).removeGame(packageName);
                                    }
                                    sendUserMessage(userId, WRITE_SETTINGS,
                                            Intent.ACTION_PACKAGE_REMOVED, WRITE_DELAY_MILLIS);
                                    sendUserMessage(userId,
                                            WRITE_GAME_MODE_INTERVENTION_LIST_FILE,
                                            Intent.ACTION_PACKAGE_REMOVED, WRITE_DELAY_MILLIS);
                                }
                            }
                            break;
                        default:
                            // do nothing
                            break;
                    }
                } catch (NullPointerException e) {
                    Slog.e(TAG, "Failed to get package name for new package");
                }
            }
        };
        mContext.registerReceiverForAllUsers(packageReceiver, packageFilter,
                /* broadcastPermission= */ null, /* scheduler= */ null);
    }

    private void registerDeviceConfigListener() {
        mDeviceConfigListener = new DeviceConfigListener();
    }

    private void publishLocalService() {
        LocalServices.addService(GameManagerInternal.class, new LocalService());
    }

    private String dumpDeviceConfigs() {
        StringBuilder out = new StringBuilder();
        for (String key : mConfigs.keySet()) {
            out.append("[\nName: ").append(key)
                    .append("\nConfig: ").append(mConfigs.get(key).toString()).append("\n]");
        }
        return out.toString();
    }

    private static int gameStateModeToStatsdGameState(int mode) {
        switch (mode) {
            case GameState.MODE_NONE:
                return FrameworkStatsLog.GAME_STATE_CHANGED__STATE__MODE_NONE;
            case GameState.MODE_GAMEPLAY_INTERRUPTIBLE:
                return FrameworkStatsLog.GAME_STATE_CHANGED__STATE__MODE_GAMEPLAY_INTERRUPTIBLE;
            case GameState.MODE_GAMEPLAY_UNINTERRUPTIBLE:
                return FrameworkStatsLog.GAME_STATE_CHANGED__STATE__MODE_GAMEPLAY_UNINTERRUPTIBLE;
            case GameState.MODE_CONTENT:
                return FrameworkStatsLog.GAME_STATE_CHANGED__STATE__MODE_CONTENT;
            case GameState.MODE_UNKNOWN:
            default:
                return FrameworkStatsLog.GAME_STATE_CHANGED__STATE__MODE_UNKNOWN;
        }
    }

    private static ServiceThread createServiceThread() {
        ServiceThread handlerThread = new ServiceThread(TAG,
                Process.THREAD_PRIORITY_BACKGROUND, true /*allowIo*/);
        handlerThread.start();
        return handlerThread;
    }

    @VisibleForTesting
    void setOverrideFrameRate(int uid, float frameRate) {
        nativeSetOverrideFrameRate(uid, frameRate);
    }

    /**
     * load dynamic library for frame rate overriding JNI calls
     */
    private static native void nativeSetOverrideFrameRate(int uid, float frameRate);
}
