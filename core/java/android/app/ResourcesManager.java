/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.app;

import static android.app.ActivityThread.DEBUG_CONFIGURATION;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.ThemeUtils;
import android.content.res.AssetManager;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.content.res.ThemeConfig;
import android.content.res.Resources;
import android.content.res.ResourcesKey;
import android.hardware.display.DisplayManagerGlobal;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayAdjustments;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Locale;

/** @hide */
public class ResourcesManager {
    static final String TAG = "ResourcesManager";
    private static final boolean DEBUG = false;

    private static ResourcesManager sResourcesManager;
    private final ArrayMap<ResourcesKey, WeakReference<Resources> > mActiveResources =
            new ArrayMap<>();
    private final ArrayMap<Pair<Integer, DisplayAdjustments>, WeakReference<Display>> mDisplays =
            new ArrayMap<>();

    CompatibilityInfo mResCompatibilityInfo;
    static IPackageManager sPackageManager;

    Configuration mResConfiguration;

    /**
     * Number of default assets attached to a Resource object's AssetManager
     * This currently includes framework and cmsdk resources
     */
    private static final int NUM_DEFAULT_ASSETS = 2;

    public static ResourcesManager getInstance() {
        synchronized (ResourcesManager.class) {
            if (sResourcesManager == null) {
                sResourcesManager = new ResourcesManager();
            }
            return sResourcesManager;
        }
    }

    public Configuration getConfiguration() {
        return mResConfiguration;
    }

    DisplayMetrics getDisplayMetricsLocked() {
        return getDisplayMetricsLocked(Display.DEFAULT_DISPLAY);
    }

    DisplayMetrics getDisplayMetricsLocked(int displayId) {
        DisplayMetrics dm = new DisplayMetrics();
        final Display display =
                getAdjustedDisplay(displayId, DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS);
        if (display != null) {
            display.getMetrics(dm);
        } else {
            dm.setToDefaults();
        }
        return dm;
    }

    final void applyNonDefaultDisplayMetricsToConfigurationLocked(
            DisplayMetrics dm, Configuration config) {
        config.touchscreen = Configuration.TOUCHSCREEN_NOTOUCH;
        config.densityDpi = dm.densityDpi;
        config.screenWidthDp = (int)(dm.widthPixels / dm.density);
        config.screenHeightDp = (int)(dm.heightPixels / dm.density);
        int sl = Configuration.resetScreenLayout(config.screenLayout);
        if (dm.widthPixels > dm.heightPixels) {
            config.orientation = Configuration.ORIENTATION_LANDSCAPE;
            config.screenLayout = Configuration.reduceScreenLayout(sl,
                    config.screenWidthDp, config.screenHeightDp);
        } else {
            config.orientation = Configuration.ORIENTATION_PORTRAIT;
            config.screenLayout = Configuration.reduceScreenLayout(sl,
                    config.screenHeightDp, config.screenWidthDp);
        }
        config.smallestScreenWidthDp = config.screenWidthDp; // assume screen does not rotate
        config.compatScreenWidthDp = config.screenWidthDp;
        config.compatScreenHeightDp = config.screenHeightDp;
        config.compatSmallestScreenWidthDp = config.smallestScreenWidthDp;
    }

    public boolean applyCompatConfiguration(int displayDensity,
            Configuration compatConfiguration) {
        if (mResCompatibilityInfo != null && !mResCompatibilityInfo.supportsScreen()) {
            mResCompatibilityInfo.applyToConfiguration(displayDensity, compatConfiguration);
            return true;
        }
        return false;
    }

    /**
     * Returns an adjusted {@link Display} object based on the inputs or null if display isn't
     * available.
     *
     * @param displayId display Id.
     * @param displayAdjustments display adjustments.
     */
    public Display getAdjustedDisplay(final int displayId, DisplayAdjustments displayAdjustments) {
        final DisplayAdjustments displayAdjustmentsCopy = (displayAdjustments != null)
                ? new DisplayAdjustments(displayAdjustments) : new DisplayAdjustments();
        final Pair<Integer, DisplayAdjustments> key =
                Pair.create(displayId, displayAdjustmentsCopy);
        synchronized (this) {
            WeakReference<Display> wd = mDisplays.get(key);
            if (wd != null) {
                final Display display = wd.get();
                if (display != null) {
                    return display;
                }
            }
            final DisplayManagerGlobal dm = DisplayManagerGlobal.getInstance();
            if (dm == null) {
                // may be null early in system startup
                return null;
            }
            final Display display = dm.getCompatibleDisplay(displayId, key.second);
            if (display != null) {
                mDisplays.put(key, new WeakReference<>(display));
            }
            return display;
        }
    }

    /**
     * Creates the top level Resources for applications with the given compatibility info.
     *
     * @param resDir the resource directory.
     * @param splitResDirs split resource directories.
     * @param overlayDirs the resource overlay directories.
     * @param libDirs the shared library resource dirs this app references.
     * @param displayId display Id.
     * @param overrideConfiguration override configurations.
     * @param compatInfo the compatibility info. Must not be null.
     */
    Resources getTopLevelResources(String resDir, String[] splitResDirs,
            String[] overlayDirs, String[] libDirs, int displayId, String packageName,
            Configuration overrideConfiguration, CompatibilityInfo compatInfo, Context context) {
        final float scale = compatInfo.applicationScale;
        final boolean isThemeable = compatInfo.isThemeable;
        Configuration overrideConfigCopy = (overrideConfiguration != null)
                ? new Configuration(overrideConfiguration) : null;
        ResourcesKey key = new ResourcesKey(resDir, displayId, overrideConfigCopy, scale, isThemeable);
        Resources r;
        synchronized (this) {
            // Resources is app scale dependent.
            if (DEBUG) Slog.w(TAG, "getTopLevelResources: " + resDir + " / " + scale);

            WeakReference<Resources> wr = mActiveResources.get(key);
            r = wr != null ? wr.get() : null;
            //if (r != null) Log.i(TAG, "isUpToDate " + resDir + ": " + r.getAssets().isUpToDate());
            if (r != null && r.getAssets().isUpToDate()) {
                if (DEBUG) Slog.w(TAG, "Returning cached resources " + r + " " + resDir
                        + ": appScale=" + r.getCompatibilityInfo().applicationScale
                        + " key=" + key + " overrideConfig=" + overrideConfiguration);
                return r;
            }
        }

        //if (r != null) {
        //    Log.w(TAG, "Throwing away out-of-date resources!!!! "
        //            + r + " " + resDir);
        //}

        AssetManager assets = new AssetManager();
        assets.setAppName(packageName);
        assets.setThemeSupport(compatInfo.isThemeable);
        // resDir can be null if the 'android' package is creating a new Resources object.
        // This is fine, since each AssetManager automatically loads the 'android' package
        // already.
        if (resDir != null) {
            if (assets.addAssetPath(resDir) == 0) {
                return null;
            }
        }

        if (splitResDirs != null) {
            for (String splitResDir : splitResDirs) {
                if (assets.addAssetPath(splitResDir) == 0) {
                    return null;
                }
            }
        }

        if (overlayDirs != null) {
            for (String idmapPath : overlayDirs) {
                assets.addOverlayPath(idmapPath, null, null, null, null);
            }
        }

        if (libDirs != null) {
            for (String libDir : libDirs) {
                if (libDir.endsWith(".apk")) {
                    // Avoid opening files we know do not have resources,
                    // like code-only .jar files.
                    if (assets.addAssetPath(libDir) == 0) {
                        Slog.w(TAG, "Asset path '" + libDir +
                                "' does not exist or contains no resources.");
                    }
                }
            }
        }

        //Log.i(TAG, "Resource: key=" + key + ", display metrics=" + metrics);
        DisplayMetrics dm = getDisplayMetricsLocked(displayId);
        Configuration config;
        final boolean isDefaultDisplay = (displayId == Display.DEFAULT_DISPLAY);
        final boolean hasOverrideConfig = key.hasOverrideConfiguration();
        if (!isDefaultDisplay || hasOverrideConfig) {
            config = new Configuration(getConfiguration());
            if (!isDefaultDisplay) {
                applyNonDefaultDisplayMetricsToConfigurationLocked(dm, config);
            }
            if (hasOverrideConfig) {
                config.updateFrom(key.mOverrideConfiguration);
                if (DEBUG) Slog.v(TAG, "Applied overrideConfig=" + key.mOverrideConfiguration);
            }
        } else {
            config = getConfiguration();
        }

        boolean iconsAttached = false;
        /* Attach theme information to the resulting AssetManager when appropriate. */
        if (compatInfo.isThemeable && config != null && !context.getPackageManager().isSafeMode()) {
            if (config.themeConfig == null) {
                try {
                    config.themeConfig = ThemeConfig.getBootTheme(context.getContentResolver());
                } catch (Exception e) {
                    Slog.d(TAG, "ThemeConfig.getBootTheme failed, falling back to system theme", e);
                    config.themeConfig = ThemeConfig.getSystemTheme();
                }
            }

            if (config.themeConfig != null) {
                attachThemeAssets(assets, config.themeConfig);
                attachCommonAssets(assets, config.themeConfig);
                iconsAttached = attachIconAssets(assets, config.themeConfig);
            }
        }

        r = new Resources(assets, dm, config, compatInfo);
        if (iconsAttached) setActivityIcons(r);
        if (DEBUG) Slog.i(TAG, "Created app resources " + resDir + " " + r + ": "
                + r.getConfiguration() + " appScale=" + r.getCompatibilityInfo().applicationScale);


        synchronized (this) {
            WeakReference<Resources> wr = mActiveResources.get(key);
            Resources existing = wr != null ? wr.get() : null;
            if (existing != null && existing.getAssets().isUpToDate()) {
                // Someone else already created the resources while we were
                // unlocked; go ahead and use theirs.
                r.getAssets().close();
                return existing;
            }

            // XXX need to remove entries when weak references go away
            mActiveResources.put(key, new WeakReference<>(r));
            if (DEBUG) Slog.v(TAG, "mActiveResources.size()=" + mActiveResources.size());
            return r;
        }
    }

    /**
     * Creates the top level Resources for applications with the given compatibility info.
     *
     * @param resDir the resource directory.
     * @param compatInfo the compability info. Must not be null.
     * @param token the application token for determining stack bounds.
     *
     * @hide
     */
    public Resources getTopLevelThemedResources(String resDir, int displayId,
                                                String packageName,
                                                String themePackageName,
                                                CompatibilityInfo compatInfo) {
        Resources r;

        AssetManager assets = new AssetManager();
        assets.setAppName(packageName);
        assets.setThemeSupport(true);
        if (assets.addAssetPath(resDir) == 0) {
            return null;
        }

        //Slog.i(TAG, "Resource: key=" + key + ", display metrics=" + metrics);
        DisplayMetrics dm = getDisplayMetricsLocked(displayId);
        Configuration config;
        boolean isDefaultDisplay = (displayId == Display.DEFAULT_DISPLAY);
        if (!isDefaultDisplay) {
            config = new Configuration(getConfiguration());
            applyNonDefaultDisplayMetricsToConfigurationLocked(dm, config);
        } else {
            config = getConfiguration();
        }

        /* Attach theme information to the resulting AssetManager when appropriate. */
        ThemeConfig.Builder builder = new ThemeConfig.Builder();
        builder.defaultOverlay(themePackageName);
        builder.defaultIcon(themePackageName);
        builder.defaultFont(themePackageName);

        ThemeConfig themeConfig = builder.build();
        attachThemeAssets(assets, themeConfig);
        attachCommonAssets(assets, themeConfig);
        attachIconAssets(assets, themeConfig);

        r = new Resources(assets, dm, config, compatInfo);
        setActivityIcons(r);

        return r;
    }

    /**
     * Creates a map between an activity & app's icon ids to its component info. This map
     * is then stored in the resource object.
     * When resource.getDrawable(id) is called it will check this mapping and replace
     * the id with the themed resource id if one is available
     * @param context
     * @param pkgName
     * @param r
     */
    private void setActivityIcons(Resources r) {
        SparseArray<PackageItemInfo> iconResources = new SparseArray<PackageItemInfo>();
        String pkgName = r.getAssets().getAppName();
        PackageInfo pkgInfo = null;
        ApplicationInfo appInfo = null;

        try {
            pkgInfo = getPackageManager().getPackageInfo(pkgName, PackageManager.GET_ACTIVITIES,
                    UserHandle.getCallingUserId());
        } catch (RemoteException e1) {
            Slog.e(TAG, "Unable to get pkg " + pkgName, e1);
            return;
        }

        final ThemeConfig themeConfig = r.getConfiguration().themeConfig;
        if (pkgName != null && themeConfig != null &&
                pkgName.equals(themeConfig.getIconPackPkgName())) {
            return;
        }

        //Map application icon
        if (pkgInfo != null && pkgInfo.applicationInfo != null) {
            appInfo = pkgInfo.applicationInfo;
            if (appInfo.themedIcon != 0 || iconResources.get(appInfo.icon) == null) {
                iconResources.put(appInfo.icon, appInfo);
            }
        }

        //Map activity icons.
        if (pkgInfo != null && pkgInfo.activities != null) {
            for (ActivityInfo ai : pkgInfo.activities) {
                if (ai.icon != 0 && (ai.themedIcon != 0 || iconResources.get(ai.icon) == null)) {
                    iconResources.put(ai.icon, ai);
                } else if (appInfo != null && appInfo.icon != 0 &&
                        (ai.themedIcon != 0 || iconResources.get(appInfo.icon) == null)) {
                    iconResources.put(appInfo.icon, ai);
                }
            }
        }

        r.setIconResources(iconResources);
        final IPackageManager pm = getPackageManager();
        try {
            ComposedIconInfo iconInfo = pm.getComposedIconInfo();
            r.setComposedIconInfo(iconInfo);
        } catch (Exception e) {
            Slog.wtf(TAG, "Failed to retrieve ComposedIconInfo", e);
        }
    }

    final boolean applyConfigurationToResourcesLocked(Configuration config,
            CompatibilityInfo compat) {
        if (mResConfiguration == null) {
            mResConfiguration = new Configuration();
        }
        if (!mResConfiguration.isOtherSeqNewer(config) && compat == null) {
            if (DEBUG || DEBUG_CONFIGURATION) Slog.v(TAG, "Skipping new config: curSeq="
                    + mResConfiguration.seq + ", newSeq=" + config.seq);
            return false;
        }
        int changes = mResConfiguration.updateFrom(config);
        // Things might have changed in display manager, so clear the cached displays.
        mDisplays.clear();
        DisplayMetrics defaultDisplayMetrics = getDisplayMetricsLocked();

        if (compat != null && (mResCompatibilityInfo == null ||
                !mResCompatibilityInfo.equals(compat))) {
            mResCompatibilityInfo = compat;
            changes |= ActivityInfo.CONFIG_SCREEN_LAYOUT
                    | ActivityInfo.CONFIG_SCREEN_SIZE
                    | ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE;
        }

        // set it for java, this also affects newly created Resources
        if (config.locale != null) {
            Locale.setDefault(config.locale);
        }

        Resources.updateSystemConfiguration(config, defaultDisplayMetrics, compat);

        ApplicationPackageManager.configurationChanged();
        //Slog.i(TAG, "Configuration changed in " + currentPackageName());

        Configuration tmpConfig = null;

        for (int i = mActiveResources.size() - 1; i >= 0; i--) {
            ResourcesKey key = mActiveResources.keyAt(i);
            Resources r = mActiveResources.valueAt(i).get();
            if (r != null) {
                if (DEBUG || DEBUG_CONFIGURATION) Slog.v(TAG, "Changing resources "
                        + r + " config to: " + config);
                int displayId = key.mDisplayId;
                boolean isDefaultDisplay = (displayId == Display.DEFAULT_DISPLAY);
                DisplayMetrics dm = defaultDisplayMetrics;
                final boolean hasOverrideConfiguration = key.hasOverrideConfiguration();
                boolean themeChanged = (changes & ActivityInfo.CONFIG_THEME_RESOURCE) != 0;
                if (themeChanged) {
                    AssetManager am = r.getAssets();
                    if (am.hasThemeSupport()) {
                        r.setIconResources(null);
                        r.setComposedIconInfo(null);
                        detachThemeAssets(am);
                        if (config.themeConfig != null) {
                            attachThemeAssets(am, config.themeConfig);
                            attachCommonAssets(am, config.themeConfig);
                            if (attachIconAssets(am, config.themeConfig)) {
                                setActivityIcons(r);
                            }
                        }
                    }
                }
                if (!isDefaultDisplay || hasOverrideConfiguration) {
                    if (tmpConfig == null) {
                        tmpConfig = new Configuration();
                    }
                    tmpConfig.setTo(config);
                    if (!isDefaultDisplay) {
                        dm = getDisplayMetricsLocked(displayId);
                        applyNonDefaultDisplayMetricsToConfigurationLocked(dm, tmpConfig);
                    }
                    if (hasOverrideConfiguration) {
                        tmpConfig.updateFrom(key.mOverrideConfiguration);
                    }
                    r.updateConfiguration(tmpConfig, dm, compat);
                } else {
                    r.updateConfiguration(config, dm, compat);
                }
                if (themeChanged) {
                    r.updateStringCache();
                }
                //Slog.i(TAG, "Updated app resources " + v.getKey()
                //        + " " + r + ": " + r.getConfiguration());
            } else {
                //Slog.i(TAG, "Removing old resources " + v.getKey());
                mActiveResources.removeAt(i);
            }
        }

        return changes != 0;
    }

    public static IPackageManager getPackageManager() {
        if (sPackageManager != null) {
            return sPackageManager;
        }
        IBinder b = ServiceManager.getService("package");
        sPackageManager = IPackageManager.Stub.asInterface(b);
        return sPackageManager;
    }


    /**
     * Attach the necessary theme asset paths and meta information to convert an
     * AssetManager to being globally "theme-aware".
     *
     * @param assets
     * @param theme
     * @return true if the AssetManager is now theme-aware; false otherwise.
     *         This can fail, for example, if the theme package has been been
     *         removed and the theme manager has yet to revert formally back to
     *         the framework default.
     */
    private boolean attachThemeAssets(AssetManager assets, ThemeConfig theme) {
        PackageInfo piTheme = null;
        PackageInfo piTarget = null;
        PackageInfo piAndroid = null;

        // Some apps run in process of another app (eg keyguard/systemUI) so we must get the
        // package name from the res tables. The 0th base package name will be the android group.
        // The 1st base package name will be the app group if one is attached. Check if it is there
        // first or else the system will crash!
        String basePackageName = null;
        String resourcePackageName = null;
        int count = assets.getBasePackageCount();
        if (count > NUM_DEFAULT_ASSETS) {
            basePackageName  = assets.getBasePackageName(NUM_DEFAULT_ASSETS);
            resourcePackageName = assets.getBaseResourcePackageName(NUM_DEFAULT_ASSETS);
        } else if (count == NUM_DEFAULT_ASSETS) {
            basePackageName  = assets.getBasePackageName(0);
        } else {
            return false;
        }

        try {
            piTheme = getPackageManager().getPackageInfo(
                    theme.getOverlayPkgNameForApp(basePackageName), 0,
                    UserHandle.getCallingUserId());
            piTarget = getPackageManager().getPackageInfo(
                    basePackageName, 0, UserHandle.getCallingUserId());

            // Handle special case where a system app (ex trebuchet) may have had its pkg name
            // renamed during an upgrade. basePackageName would be the manifest value which will
            // fail on getPackageInfo(). resource pkg is assumed to have the original name
            if (piTarget == null && resourcePackageName != null) {
                piTarget = getPackageManager().getPackageInfo(resourcePackageName,
                        0, UserHandle.getCallingUserId());
            }
            piAndroid = getPackageManager().getPackageInfo("android", 0,
                    UserHandle.getCallingUserId());
        } catch (RemoteException e) {
        }

        if (piTheme == null || piTheme.applicationInfo == null ||
                    piTarget == null || piTarget.applicationInfo == null ||
                    piAndroid == null || piAndroid.applicationInfo == null ||
                    piTheme.mOverlayTargets == null) {
            return false;
        }

        String themePackageName = piTheme.packageName;
        String themePath = piTheme.applicationInfo.publicSourceDir;
        if (!piTarget.isThemeApk && piTheme.mOverlayTargets.contains(basePackageName)) {
            String targetPackagePath = piTarget.applicationInfo.sourceDir;
            String prefixPath = ThemeUtils.getOverlayPathToTarget(basePackageName);

            String resCachePath = ThemeUtils.getTargetCacheDir(piTarget.packageName, piTheme);
            String resApkPath = resCachePath + "/resources.apk";
            String idmapPath = ThemeUtils.getIdmapPath(piTarget.packageName, piTheme.packageName);
            int cookie = assets.addOverlayPath(idmapPath, themePath, resApkPath,
                    targetPackagePath, prefixPath);

            if (cookie != 0) {
                assets.setThemePackageName(themePackageName);
                assets.addThemeCookie(cookie);
            }
        }

        if (!piTarget.isThemeApk && piTheme.mOverlayTargets.contains("android")) {
            String resCachePath= ThemeUtils.getTargetCacheDir(piAndroid.packageName, piTheme);
            String prefixPath = ThemeUtils.getOverlayPathToTarget(piAndroid.packageName);
            String targetPackagePath = piAndroid.applicationInfo.publicSourceDir;
            String resApkPath = resCachePath + "/resources.apk";
            String idmapPath = ThemeUtils.getIdmapPath("android", piTheme.packageName);
            int cookie = assets.addOverlayPath(idmapPath, themePath,
                    resApkPath, targetPackagePath, prefixPath);
            if (cookie != 0) {
                assets.setThemePackageName(themePackageName);
                assets.addThemeCookie(cookie);
            }
        }

        return true;
    }

    /**
     * Attach the necessary icon asset paths. Icon assets should be in a different
     * namespace than the standard 0x7F.
     *
     * @param assets
     * @param theme
     * @return true if succes, false otherwise
     */
    private boolean attachIconAssets(AssetManager assets, ThemeConfig theme) {
        PackageInfo piIcon = null;
        try {
            piIcon = getPackageManager().getPackageInfo(theme.getIconPackPkgName(), 0,
                    UserHandle.getCallingUserId());
        } catch (RemoteException e) {
        }

        if (piIcon == null || piIcon.applicationInfo == null) {
            return false;
        }

        String iconPkg = theme.getIconPackPkgName();
        if (iconPkg != null && !iconPkg.isEmpty()) {
            String themeIconPath =  piIcon.applicationInfo.publicSourceDir;
            String prefixPath = ThemeUtils.ICONS_PATH;
            String iconDir = ThemeUtils.getIconPackDir(iconPkg);
            String resTablePath = iconDir + "/resources.arsc";
            String resApkPath = iconDir + "/resources.apk";

            // Legacy Icon packs have everything in their APK
            if (piIcon.isLegacyIconPackApk) {
                prefixPath = "";
                resApkPath = "";
                resTablePath = "";
            }

            int cookie = assets.addIconPath(themeIconPath, resApkPath, prefixPath,
                    Resources.THEME_ICON_PKG_ID);
            if (cookie != 0) {
                assets.setIconPackCookie(cookie);
                assets.setIconPackageName(iconPkg);
            }
        }

        return true;
    }

    /**
     * Attach the necessary common asset paths. Common assets should be in a different
     * namespace than the standard 0x7F.
     *
     * @param assets
     * @param theme
     * @return true if succes, false otherwise
     */
    private boolean attachCommonAssets(AssetManager assets, ThemeConfig theme) {
        // Some apps run in process of another app (eg keyguard/systemUI) so we must get the
        // package name from the res tables. The 0th base package name will be the android group.
        // The 1st base package name will be the app group if one is attached. Check if it is there
        // first or else the system will crash!
        String basePackageName;
        int count = assets.getBasePackageCount();
        if (count > NUM_DEFAULT_ASSETS) {
            basePackageName  = assets.getBasePackageName(NUM_DEFAULT_ASSETS);
        } else if (count == NUM_DEFAULT_ASSETS) {
            basePackageName  = assets.getBasePackageName(0);
        } else {
            return false;
        }

        PackageInfo piTheme = null;
        try {
            piTheme = getPackageManager().getPackageInfo(
                    theme.getOverlayPkgNameForApp(basePackageName), 0,
                    UserHandle.getCallingUserId());
        } catch (RemoteException e) {
        }

        if (piTheme == null || piTheme.applicationInfo == null) {
            return false;
        }

        String themePackageName =
                ThemeUtils.getCommonPackageName(piTheme.applicationInfo.packageName);
        if (themePackageName != null && !themePackageName.isEmpty()) {
            String themePath =  piTheme.applicationInfo.publicSourceDir;
            String prefixPath = ThemeUtils.COMMON_RES_PATH;
            String resCachePath =
                    ThemeUtils.getTargetCacheDir(ThemeUtils.COMMON_RES_TARGET, piTheme);
            String resApkPath = resCachePath + "/resources.apk";
            int cookie = assets.addCommonOverlayPath(themePath, resApkPath,
                    prefixPath);
            if (cookie != 0) {
                assets.setCommonResCookie(cookie);
                assets.setCommonResPackageName(themePackageName);
            }
        }

        return true;
    }

    private void detachThemeAssets(AssetManager assets) {
        String themePackageName = assets.getThemePackageName();
        String iconPackageName = assets.getIconPackageName();
        String commonResPackageName = assets.getCommonResPackageName();

        //Remove Icon pack if it exists
        if (!TextUtils.isEmpty(iconPackageName) && assets.getIconPackCookie() > 0) {
            assets.removeOverlayPath(iconPackageName, assets.getIconPackCookie());
            assets.setIconPackageName(null);
            assets.setIconPackCookie(0);
        }
        //Remove common resources if it exists
        if (!TextUtils.isEmpty(commonResPackageName) && assets.getCommonResCookie() > 0) {
            assets.removeOverlayPath(commonResPackageName, assets.getCommonResCookie());
            assets.setCommonResPackageName(null);
            assets.setCommonResCookie(0);
        }
        final List<Integer> themeCookies = assets.getThemeCookies();
        if (!TextUtils.isEmpty(themePackageName) && !themeCookies.isEmpty()) {
            // remove overlays in reverse order
            for (int i = themeCookies.size() - 1; i >= 0; i--) {
                assets.removeOverlayPath(themePackageName, themeCookies.get(i));
            }
        }
        assets.getThemeCookies().clear();
        assets.setThemePackageName(null);
    }
}
