/*
 * Copyright (C) 2007 The Android Open Source Project
 * Copyright (C) 2015 The CyanogenMod Project
 * Copyright (C) 2017 The LineageOS Project
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

package com.android.server.notification;

import static android.app.NotificationManager.IMPORTANCE_MIN;
import static android.app.NotificationManager.IMPORTANCE_NONE;
import static android.content.pm.PackageManager.FEATURE_LEANBACK;
import static android.content.pm.PackageManager.FEATURE_TELEVISION;
import static android.service.notification.NotificationListenerService
        .NOTIFICATION_CHANNEL_OR_GROUP_ADDED;
import static android.service.notification.NotificationListenerService
        .NOTIFICATION_CHANNEL_OR_GROUP_DELETED;
import static android.service.notification.NotificationListenerService
        .NOTIFICATION_CHANNEL_OR_GROUP_UPDATED;
import static android.service.notification.NotificationListenerService.REASON_APP_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_APP_CANCEL_ALL;
import static android.service.notification.NotificationListenerService.REASON_CHANNEL_BANNED;
import static android.service.notification.NotificationListenerService.REASON_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_CANCEL_ALL;
import static android.service.notification.NotificationListenerService.REASON_CLICK;
import static android.service.notification.NotificationListenerService.REASON_ERROR;
import static android.service.notification.NotificationListenerService.REASON_GROUP_SUMMARY_CANCELED;
import static android.service.notification.NotificationListenerService.REASON_LISTENER_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_LISTENER_CANCEL_ALL;
import static android.service.notification.NotificationListenerService.REASON_PACKAGE_BANNED;
import static android.service.notification.NotificationListenerService.REASON_PACKAGE_CHANGED;
import static android.service.notification.NotificationListenerService.REASON_PACKAGE_SUSPENDED;
import static android.service.notification.NotificationListenerService.REASON_PROFILE_TURNED_OFF;
import static android.service.notification.NotificationListenerService.REASON_SNOOZED;
import static android.service.notification.NotificationListenerService.REASON_TIMEOUT;
import static android.service.notification.NotificationListenerService.REASON_UNAUTOBUNDLED;
import static android.service.notification.NotificationListenerService.REASON_USER_STOPPED;
import static android.service.notification.NotificationListenerService.HINT_HOST_DISABLE_EFFECTS;
import static android.service.notification.NotificationListenerService.HINT_HOST_DISABLE_NOTIFICATION_EFFECTS;
import static android.service.notification.NotificationListenerService.HINT_HOST_DISABLE_CALL_EFFECTS;
import static android.service.notification.NotificationListenerService.SUPPRESSED_EFFECT_SCREEN_OFF;
import static android.service.notification.NotificationListenerService.SUPPRESSED_EFFECT_SCREEN_ON;
import static android.service.notification.NotificationListenerService.TRIM_FULL;
import static android.service.notification.NotificationListenerService.TRIM_LIGHT;

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;
import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AlarmManager;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.AutomaticZenRule;
import android.app.NotificationChannelGroup;
import android.app.backup.BackupManager;
import android.app.IActivityManager;
import android.app.INotificationManager;
import android.app.ITransientNotification;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager.Policy;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManagerInternal;
import android.companion.ICompanionDeviceManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ParceledListSlice;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.media.AudioManagerInternal;
import android.media.IRingtonePlayer;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.Vibrator;
import android.os.VibrationEffect;
import android.provider.Settings;
import android.service.notification.Adjustment;
import android.service.notification.Condition;
import android.service.notification.IConditionProvider;
import android.service.notification.INotificationListener;
import android.service.notification.IStatusBarNotificationHolder;
import android.service.notification.NotificationAssistantService;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationRankingUpdate;
import android.service.notification.NotificationRecordProto;
import android.service.notification.NotificationServiceDumpProto;
import android.service.notification.NotificationServiceProto;
import android.service.notification.SnoozeCriterion;
import android.service.notification.StatusBarNotification;
import android.service.notification.ZenModeConfig;
import android.service.notification.ZenModeProto;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;
import android.util.proto.ProtoOutputStream;
import android.view.WindowManagerInternal;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.widget.Toast;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.Preconditions;
import com.android.server.DeviceIdleController;
import com.android.server.EventLogTags;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.lights.Light;
import com.android.server.lights.LightsManager;
import com.android.server.notification.ManagedServices.ManagedServiceInfo;
import com.android.server.policy.PhoneWindowManager;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.notification.ManagedServices.UserProfiles;

import lineageos.providers.LineageSettings;
import org.lineageos.internal.lights.LightsCapabilities;

import libcore.io.IoUtils;

import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** {@hide} */
public class NotificationManagerService extends SystemService {
    static final String TAG = "NotificationService";
    static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    public static final boolean ENABLE_CHILD_NOTIFICATIONS
            = SystemProperties.getBoolean("debug.child_notifs", true);

    static final int MAX_PACKAGE_NOTIFICATIONS = 50;
    static final float DEFAULT_MAX_NOTIFICATION_ENQUEUE_RATE = 10f;

    // message codes
    static final int MESSAGE_TIMEOUT = 2;
    static final int MESSAGE_SAVE_POLICY_FILE = 3;
    static final int MESSAGE_SEND_RANKING_UPDATE = 4;
    static final int MESSAGE_LISTENER_HINTS_CHANGED = 5;
    static final int MESSAGE_LISTENER_NOTIFICATION_FILTER_CHANGED = 6;

    // ranking thread messages
    private static final int MESSAGE_RECONSIDER_RANKING = 1000;
    private static final int MESSAGE_RANKING_SORT = 1001;

    static final int LONG_DELAY = PhoneWindowManager.TOAST_WINDOW_TIMEOUT;
    static final int SHORT_DELAY = 2000; // 2 seconds

    static final long[] DEFAULT_VIBRATE_PATTERN = {0, 250, 250, 250};

    static final long SNOOZE_UNTIL_UNSPECIFIED = -1;

    static final int VIBRATE_PATTERN_MAXLEN = 8 * 2 + 1; // up to eight bumps

    static final int DEFAULT_STREAM_TYPE = AudioManager.STREAM_NOTIFICATION;

    static final boolean ENABLE_BLOCKED_TOASTS = true;

    // When #matchesCallFilter is called from the ringer, wait at most
    // 3s to resolve the contacts. This timeout is required since
    // ContactsProvider might take a long time to start up.
    //
    // Return STARRED_CONTACT when the timeout is hit in order to avoid
    // missed calls in ZEN mode "Important".
    static final int MATCHES_CALL_FILTER_CONTACTS_TIMEOUT_MS = 3000;
    static final float MATCHES_CALL_FILTER_TIMEOUT_AFFINITY =
            ValidateNotificationPeople.STARRED_CONTACT;

    /** notification_enqueue status value for a newly enqueued notification. */
    private static final int EVENTLOG_ENQUEUE_STATUS_NEW = 0;

    /** notification_enqueue status value for an existing notification. */
    private static final int EVENTLOG_ENQUEUE_STATUS_UPDATE = 1;

    /** notification_enqueue status value for an ignored notification. */
    private static final int EVENTLOG_ENQUEUE_STATUS_IGNORED = 2;
    private static final long MIN_PACKAGE_OVERRATE_LOG_INTERVAL = 5000; // milliseconds

    private static final long DELAY_FOR_ASSISTANT_TIME = 100;

    private static final String ACTION_NOTIFICATION_TIMEOUT =
            NotificationManagerService.class.getSimpleName() + ".TIMEOUT";
    private static final int REQUEST_CODE_TIMEOUT = 1;
    private static final String SCHEME_TIMEOUT = "timeout";
    private static final String EXTRA_KEY = "key";

    /** notification light maximum brightness value to use. */
    private static final int LIGHT_BRIGHTNESS_MAXIMUM = 255;

    private IActivityManager mAm;
    private IPackageManager mPackageManager;
    private PackageManager mPackageManagerClient;
    AudioManager mAudioManager;
    AudioManagerInternal mAudioManagerInternal;
    @Nullable StatusBarManagerInternal mStatusBar;
    Vibrator mVibrator;
    private WindowManagerInternal mWindowManagerInternal;
    private AlarmManager mAlarmManager;
    private ICompanionDeviceManager mCompanionManager;

    final IBinder mForegroundToken = new Binder();
    private Handler mHandler;
    private final HandlerThread mRankingThread = new HandlerThread("ranker",
            Process.THREAD_PRIORITY_BACKGROUND);

    private Light mNotificationLight;
    Light mAttentionLight;

    private boolean mAdjustableNotificationLedBrightness;
    private int mNotificationLedBrightnessLevel = LIGHT_BRIGHTNESS_MAXIMUM;

    private boolean mScreenOnEnabled = false;
    private boolean mScreenOnDefault = false;

    private long[] mFallbackVibrationPattern;
    private boolean mUseAttentionLight;
    boolean mSystemReady;

    private boolean mDisableNotificationEffects;
    private int mCallState;
    private String mSoundNotificationKey;
    private String mVibrateNotificationKey;

    private final SparseArray<ArraySet<ManagedServiceInfo>> mListenersDisablingEffects =
            new SparseArray<ArraySet<ManagedServiceInfo>>();
    private List<ComponentName> mEffectsSuppressors = new ArrayList<ComponentName>();
    private int mListenerHints;  // right now, all hints are global
    private int mInterruptionFilter = NotificationListenerService.INTERRUPTION_FILTER_UNKNOWN;

    // for enabling and disabling notification pulse behavior
    private boolean mScreenOn = true;
    private boolean mInCall = false;
    private boolean mNotificationPulseEnabled;
    private ArrayMap<String, NotificationLedValues> mNotificationPulseCustomLedValues;
    private Map<String, String> mPackageNameMappings;

    // for checking lockscreen status
    private KeyguardManager mKeyguardManager;

    // for generating notification tones in-call
    private ToneGenerator mInCallToneGenerator;
    private final Object mInCallToneGeneratorLock = new Object();

    // used as a mutex for access to all active notifications & listeners
    final Object mNotificationLock = new Object();
    @GuardedBy("mNotificationLock")
    final ArrayList<NotificationRecord> mNotificationList =
            new ArrayList<NotificationRecord>();
    @GuardedBy("mNotificationLock")
    final ArrayMap<String, NotificationRecord> mNotificationsByKey =
            new ArrayMap<String, NotificationRecord>();
    @GuardedBy("mNotificationLock")
    final ArrayList<NotificationRecord> mEnqueuedNotifications = new ArrayList<>();
    @GuardedBy("mNotificationLock")
    final ArrayMap<Integer, ArrayMap<String, String>> mAutobundledSummaries = new ArrayMap<>();
    final ArrayList<ToastRecord> mToastQueue = new ArrayList<ToastRecord>();
    final ArrayMap<String, NotificationRecord> mSummaryByGroupKey = new ArrayMap<>();
    final PolicyAccess mPolicyAccess = new PolicyAccess();

    // The last key in this list owns the hardware.
    ArrayList<String> mLights = new ArrayList<>();

    private AppOpsManager mAppOps;
    private UsageStatsManagerInternal mAppUsageStats;

    private Archive mArchive;

    // Persistent storage for notification policy
    private AtomicFile mPolicyFile;

    private static final int DB_VERSION = 1;

    private static final String TAG_NOTIFICATION_POLICY = "notification-policy";
    private static final String ATTR_VERSION = "version";

    private RankingHelper mRankingHelper;

    private final UserProfiles mUserProfiles = new UserProfiles();
    private NotificationListeners mListeners;
    private NotificationAssistants mNotificationAssistants;
    private ConditionProviders mConditionProviders;
    private NotificationUsageStats mUsageStats;

    private static final int MY_UID = Process.myUid();
    private static final int MY_PID = Process.myPid();
    private static final IBinder WHITELIST_TOKEN = new Binder();
    private RankingHandler mRankingHandler;
    private long mLastOverRateLogTime;
    private float mMaxPackageEnqueueRate = DEFAULT_MAX_NOTIFICATION_ENQUEUE_RATE;

    private SnoozeHelper mSnoozeHelper;
    private GroupHelper mGroupHelper;
    private boolean mIsTelevision;

    private int mDefaultNotificationColor;
    private int mDefaultNotificationLedOn;
    private int mDefaultNotificationLedOff;
    private long[] mDefaultVibrationPattern;

    private static class Archive {
        final int mBufferSize;
        final ArrayDeque<StatusBarNotification> mBuffer;

        public Archive(int size) {
            mBufferSize = size;
            mBuffer = new ArrayDeque<StatusBarNotification>(mBufferSize);
        }

        public String toString() {
            final StringBuilder sb = new StringBuilder();
            final int N = mBuffer.size();
            sb.append("Archive (");
            sb.append(N);
            sb.append(" notification");
            sb.append((N==1)?")":"s)");
            return sb.toString();
        }

        public void record(StatusBarNotification nr) {
            if (mBuffer.size() == mBufferSize) {
                mBuffer.removeFirst();
            }

            // We don't want to store the heavy bits of the notification in the archive,
            // but other clients in the system process might be using the object, so we
            // store a (lightened) copy.
            mBuffer.addLast(nr.cloneLight());
        }

        public Iterator<StatusBarNotification> descendingIterator() {
            return mBuffer.descendingIterator();
        }

        public StatusBarNotification[] getArray(int count) {
            if (count == 0) count = mBufferSize;
            final StatusBarNotification[] a
                    = new StatusBarNotification[Math.min(count, mBuffer.size())];
            Iterator<StatusBarNotification> iter = descendingIterator();
            int i=0;
            while (iter.hasNext() && i < count) {
                a[i++] = iter.next();
            }
            return a;
        }

    }

    private void readPolicyXml(InputStream stream, boolean forRestore)
            throws XmlPullParserException, NumberFormatException, IOException {
        final XmlPullParser parser = Xml.newPullParser();
        parser.setInput(stream, StandardCharsets.UTF_8.name());

        while (parser.next() != END_DOCUMENT) {
            mZenModeHelper.readXml(parser, forRestore);
            mRankingHelper.readXml(parser, forRestore);
        }
    }

    private void loadPolicyFile() {
        if (DBG) Slog.d(TAG, "loadPolicyFile");
        synchronized (mPolicyFile) {

            FileInputStream infile = null;
            try {
                infile = mPolicyFile.openRead();
                readPolicyXml(infile, false /*forRestore*/);
            } catch (FileNotFoundException e) {
                // No data yet
            } catch (IOException e) {
                Log.wtf(TAG, "Unable to read notification policy", e);
            } catch (NumberFormatException e) {
                Log.wtf(TAG, "Unable to parse notification policy", e);
            } catch (XmlPullParserException e) {
                Log.wtf(TAG, "Unable to parse notification policy", e);
            } finally {
                IoUtils.closeQuietly(infile);
            }
        }
    }

    public void savePolicyFile() {
        mHandler.removeMessages(MESSAGE_SAVE_POLICY_FILE);
        mHandler.sendEmptyMessage(MESSAGE_SAVE_POLICY_FILE);
    }

    private void handleSavePolicyFile() {
        if (DBG) Slog.d(TAG, "handleSavePolicyFile");
        synchronized (mPolicyFile) {
            final FileOutputStream stream;
            try {
                stream = mPolicyFile.startWrite();
            } catch (IOException e) {
                Slog.w(TAG, "Failed to save policy file", e);
                return;
            }

            try {
                writePolicyXml(stream, false /*forBackup*/);
                mPolicyFile.finishWrite(stream);
            } catch (IOException e) {
                Slog.w(TAG, "Failed to save policy file, restoring backup", e);
                mPolicyFile.failWrite(stream);
            }
        }
        BackupManager.dataChanged(getContext().getPackageName());
    }

    private void writePolicyXml(OutputStream stream, boolean forBackup) throws IOException {
        final XmlSerializer out = new FastXmlSerializer();
        out.setOutput(stream, StandardCharsets.UTF_8.name());
        out.startDocument(null, true);
        out.startTag(null, TAG_NOTIFICATION_POLICY);
        out.attribute(null, ATTR_VERSION, Integer.toString(DB_VERSION));
        mZenModeHelper.writeXml(out, forBackup);
        mRankingHelper.writeXml(out, forBackup);
        out.endTag(null, TAG_NOTIFICATION_POLICY);
        out.endDocument();
    }

    /** Use this to check if a package can post a notification or toast. */
    private boolean checkNotificationOp(String pkg, int uid) {
        return mAppOps.checkOp(AppOpsManager.OP_POST_NOTIFICATION, uid, pkg)
                == AppOpsManager.MODE_ALLOWED && !isPackageSuspendedForUser(pkg, uid);
    }

    private static final class ToastRecord
    {
        final int pid;
        final String pkg;
        final ITransientNotification callback;
        int duration;
        Binder token;

        ToastRecord(int pid, String pkg, ITransientNotification callback, int duration,
                    Binder token) {
            this.pid = pid;
            this.pkg = pkg;
            this.callback = callback;
            this.duration = duration;
            this.token = token;
        }

        void update(int duration) {
            this.duration = duration;
        }

        void dump(PrintWriter pw, String prefix, DumpFilter filter) {
            if (filter != null && !filter.matches(pkg)) return;
            pw.println(prefix + this);
        }

        @Override
        public final String toString()
        {
            return "ToastRecord{"
                + Integer.toHexString(System.identityHashCode(this))
                + " pkg=" + pkg
                + " callback=" + callback
                + " duration=" + duration;
        }
    }

    class NotificationLedValues {
        public int color;
        public int onMS;
        public int offMS;
    }

    private final NotificationDelegate mNotificationDelegate = new NotificationDelegate() {

        @Override
        public void onSetDisabled(int status) {
            synchronized (mNotificationLock) {
                mDisableNotificationEffects =
                        (status & StatusBarManager.DISABLE_NOTIFICATION_ALERTS) != 0;
                if (disableNotificationEffects(null) != null) {
                    // cancel whatever's going on
                    long identity = Binder.clearCallingIdentity();
                    try {
                        final IRingtonePlayer player = mAudioManager.getRingtonePlayer();
                        if (player != null) {
                            player.stopAsync();
                        }
                    } catch (RemoteException e) {
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }

                    identity = Binder.clearCallingIdentity();
                    try {
                        mVibrator.cancel();
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                }
            }
        }

        @Override
        public void onClearAll(int callingUid, int callingPid, int userId) {
            synchronized (mNotificationLock) {
                cancelAllLocked(callingUid, callingPid, userId, REASON_CANCEL_ALL, null,
                        /*includeCurrentProfiles*/ true);
            }
        }

        @Override
        public void onNotificationClick(int callingUid, int callingPid, String key) {
            synchronized (mNotificationLock) {
                NotificationRecord r = mNotificationsByKey.get(key);
                if (r == null) {
                    Log.w(TAG, "No notification with key: " + key);
                    return;
                }
                final long now = System.currentTimeMillis();
                MetricsLogger.action(r.getLogMaker(now)
                        .setCategory(MetricsEvent.NOTIFICATION_ITEM)
                        .setType(MetricsEvent.TYPE_ACTION));
                EventLogTags.writeNotificationClicked(key,
                        r.getLifespanMs(now), r.getFreshnessMs(now), r.getExposureMs(now));

                StatusBarNotification sbn = r.sbn;
                cancelNotification(callingUid, callingPid, sbn.getPackageName(), sbn.getTag(),
                        sbn.getId(), Notification.FLAG_AUTO_CANCEL,
                        Notification.FLAG_FOREGROUND_SERVICE, false, r.getUserId(),
                        REASON_CLICK, null);
            }
        }

        @Override
        public void onNotificationActionClick(int callingUid, int callingPid, String key,
                int actionIndex) {
            synchronized (mNotificationLock) {
                NotificationRecord r = mNotificationsByKey.get(key);
                if (r == null) {
                    Log.w(TAG, "No notification with key: " + key);
                    return;
                }
                final long now = System.currentTimeMillis();
                MetricsLogger.action(r.getLogMaker(now)
                        .setCategory(MetricsEvent.NOTIFICATION_ITEM_ACTION)
                        .setType(MetricsEvent.TYPE_ACTION)
                        .setSubtype(actionIndex));
                EventLogTags.writeNotificationActionClicked(key, actionIndex,
                        r.getLifespanMs(now), r.getFreshnessMs(now), r.getExposureMs(now));
                // TODO: Log action click via UsageStats.
            }
        }

        @Override
        public void onNotificationClear(int callingUid, int callingPid,
                String pkg, String tag, int id, int userId) {
            cancelNotification(callingUid, callingPid, pkg, tag, id, 0,
                    Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE,
                    true, userId, REASON_CANCEL, null);
        }

        @Override
        public void onPanelRevealed(boolean clearEffects, int items) {
            MetricsLogger.visible(getContext(), MetricsEvent.NOTIFICATION_PANEL);
            MetricsLogger.histogram(getContext(), "note_load", items);
            EventLogTags.writeNotificationPanelRevealed(items);
            if (clearEffects) {
                clearEffects();
            }
        }

        @Override
        public void onPanelHidden() {
            MetricsLogger.hidden(getContext(), MetricsEvent.NOTIFICATION_PANEL);
            EventLogTags.writeNotificationPanelHidden();
        }

        @Override
        public void clearEffects() {
            synchronized (mNotificationLock) {
                if (DBG) Slog.d(TAG, "clearEffects");
                clearSoundLocked();
                clearVibrateLocked();
                clearLightsLocked();
            }
        }

        @Override
        public void onNotificationError(int callingUid, int callingPid, String pkg, String tag, int id,
                int uid, int initialPid, String message, int userId) {
            Slog.d(TAG, "onNotification error pkg=" + pkg + " tag=" + tag + " id=" + id
                    + "; will crashApplication(uid=" + uid + ", pid=" + initialPid + ")");
            cancelNotification(callingUid, callingPid, pkg, tag, id, 0, 0, false, userId,
                    REASON_ERROR, null);
            long ident = Binder.clearCallingIdentity();
            try {
                ActivityManager.getService().crashApplication(uid, initialPid, pkg, -1,
                        "Bad notification posted from package " + pkg
                        + ": " + message);
            } catch (RemoteException e) {
            }
            Binder.restoreCallingIdentity(ident);
        }

        @Override
        public void onNotificationVisibilityChanged(NotificationVisibility[] newlyVisibleKeys,
                NotificationVisibility[] noLongerVisibleKeys) {
            synchronized (mNotificationLock) {
                for (NotificationVisibility nv : newlyVisibleKeys) {
                    NotificationRecord r = mNotificationsByKey.get(nv.key);
                    if (r == null) continue;
                    r.setVisibility(true, nv.rank);
                    nv.recycle();
                }
                // Note that we might receive this event after notifications
                // have already left the system, e.g. after dismissing from the
                // shade. Hence not finding notifications in
                // mNotificationsByKey is not an exceptional condition.
                for (NotificationVisibility nv : noLongerVisibleKeys) {
                    NotificationRecord r = mNotificationsByKey.get(nv.key);
                    if (r == null) continue;
                    r.setVisibility(false, nv.rank);
                    nv.recycle();
                }
            }
        }

        @Override
        public void onNotificationExpansionChanged(String key,
                boolean userAction, boolean expanded) {
            synchronized (mNotificationLock) {
                NotificationRecord r = mNotificationsByKey.get(key);
                if (r != null) {
                    r.stats.onExpansionChanged(userAction, expanded);
                    final long now = System.currentTimeMillis();
                    MetricsLogger.action(r.getLogMaker(now)
                            .setCategory(MetricsEvent.NOTIFICATION_ITEM)
                            .setType(MetricsEvent.TYPE_DETAIL));
                    EventLogTags.writeNotificationExpansion(key,
                            userAction ? 1 : 0, expanded ? 1 : 0,
                            r.getLifespanMs(now), r.getFreshnessMs(now), r.getExposureMs(now));
                }
            }
        }
    };

    @GuardedBy("mNotificationLock")
    private void clearSoundLocked() {
        mSoundNotificationKey = null;
        long identity = Binder.clearCallingIdentity();
        try {
            final IRingtonePlayer player = mAudioManager.getRingtonePlayer();
            if (player != null) {
                player.stopAsync();
            }
        } catch (RemoteException e) {
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @GuardedBy("mNotificationLock")
    private void clearVibrateLocked() {
        mVibrateNotificationKey = null;
        long identity = Binder.clearCallingIdentity();
        try {
            mVibrator.cancel();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @GuardedBy("mNotificationLock")
    private void clearLightsLocked() {
        // lights
        // clear only if lockscreen is not active
        if (mKeyguardManager != null && !mKeyguardManager.isKeyguardLocked()) {
            mLights.clear();
            updateLightsLocked();
        }
    }

    private final BroadcastReceiver mNotificationTimeoutReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                return;
            }
            if (ACTION_NOTIFICATION_TIMEOUT.equals(action)) {
                final NotificationRecord record;
                synchronized (mNotificationLock) {
                    record = findNotificationByKeyLocked(intent.getStringExtra(EXTRA_KEY));
                }
                if (record != null) {
                    cancelNotification(record.sbn.getUid(), record.sbn.getInitialPid(),
                            record.sbn.getPackageName(), record.sbn.getTag(),
                            record.sbn.getId(), 0,
                            Notification.FLAG_FOREGROUND_SERVICE, true, record.getUserId(),
                            REASON_TIMEOUT, null);
                }
            }
        }
    };

    private final BroadcastReceiver mPackageIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                return;
            }

            boolean queryRestart = false;
            boolean queryRemove = false;
            boolean packageChanged = false;
            boolean cancelNotifications = true;
            int reason = REASON_PACKAGE_CHANGED;

            if (action.equals(Intent.ACTION_PACKAGE_ADDED)
                    || (queryRemove=action.equals(Intent.ACTION_PACKAGE_REMOVED))
                    || action.equals(Intent.ACTION_PACKAGE_RESTARTED)
                    || (packageChanged=action.equals(Intent.ACTION_PACKAGE_CHANGED))
                    || (queryRestart=action.equals(Intent.ACTION_QUERY_PACKAGE_RESTART))
                    || action.equals(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE)
                    || action.equals(Intent.ACTION_PACKAGES_SUSPENDED)) {
                int changeUserId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                        UserHandle.USER_ALL);
                String pkgList[] = null;
                int uidList[] = null;
                boolean removingPackage = queryRemove &&
                        !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
                if (DBG) Slog.i(TAG, "action=" + action + " removing=" + removingPackage);
                if (action.equals(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE)) {
                    pkgList = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                    uidList = intent.getIntArrayExtra(Intent.EXTRA_CHANGED_UID_LIST);
                } else if (action.equals(Intent.ACTION_PACKAGES_SUSPENDED)) {
                    pkgList = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                    reason = REASON_PACKAGE_SUSPENDED;
                } else if (queryRestart) {
                    pkgList = intent.getStringArrayExtra(Intent.EXTRA_PACKAGES);
                    uidList = new int[] {intent.getIntExtra(Intent.EXTRA_UID, -1)};
                } else {
                    Uri uri = intent.getData();
                    if (uri == null) {
                        return;
                    }
                    String pkgName = uri.getSchemeSpecificPart();
                    if (pkgName == null) {
                        return;
                    }
                    if (packageChanged) {
                        // We cancel notifications for packages which have just been disabled
                        try {
                            final int enabled = mPackageManager.getApplicationEnabledSetting(
                                    pkgName,
                                    changeUserId != UserHandle.USER_ALL ? changeUserId :
                                            UserHandle.USER_SYSTEM);
                            if (enabled == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                                    || enabled == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                                cancelNotifications = false;
                            }
                        } catch (IllegalArgumentException e) {
                            // Package doesn't exist; probably racing with uninstall.
                            // cancelNotifications is already true, so nothing to do here.
                            if (DBG) {
                                Slog.i(TAG, "Exception trying to look up app enabled setting", e);
                            }
                        } catch (RemoteException e) {
                            // Failed to talk to PackageManagerService Should never happen!
                        }
                    }
                    pkgList = new String[]{pkgName};
                    uidList = new int[] {intent.getIntExtra(Intent.EXTRA_UID, -1)};
                }
                if (pkgList != null && (pkgList.length > 0)) {
                    for (String pkgName : pkgList) {
                        if (cancelNotifications) {
                            cancelAllNotificationsInt(MY_UID, MY_PID, pkgName, null, 0, 0,
                                    !queryRestart, changeUserId, reason, null);
                        }
                    }
                }
                mListeners.onPackagesChanged(removingPackage, pkgList);
                mNotificationAssistants.onPackagesChanged(removingPackage, pkgList);
                mConditionProviders.onPackagesChanged(removingPackage, pkgList);
                mRankingHelper.onPackagesChanged(removingPackage, changeUserId, pkgList, uidList);
                savePolicyFile();
            }
        }
    };

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (action.equals(Intent.ACTION_SCREEN_ON)) {
                // Keep track of screen on/off state, but do not turn off the notification light
                // until user passes through the lock screen or views the notification.
                mScreenOn = true;
                updateNotificationPulse();
            } else if (action.equals(Intent.ACTION_SCREEN_OFF)) {
                mScreenOn = false;
                updateNotificationPulse();
            } else if (action.equals(TelephonyManager.ACTION_PHONE_STATE_CHANGED)) {
                mInCall = TelephonyManager.EXTRA_STATE_OFFHOOK
                        .equals(intent.getStringExtra(TelephonyManager.EXTRA_STATE));
                updateNotificationPulse();
                synchronized (mInCallToneGeneratorLock) {
                    if (mInCall) {
                        if (mInCallToneGenerator == null) {
                            int relativeToneVolume = getContext().getResources().getInteger(
                                    R.integer.config_inCallNotificationVolumeRelative);
                            if (relativeToneVolume < ToneGenerator.MIN_VOLUME
                                    || relativeToneVolume > ToneGenerator.MAX_VOLUME) {
                                relativeToneVolume = ToneGenerator.MAX_VOLUME;
                            }
                            try {
                                mInCallToneGenerator = new ToneGenerator(
                                        AudioManager.STREAM_VOICE_CALL, relativeToneVolume);
                            } catch (RuntimeException e) {
                                Log.e(TAG, "Error creating local tone generator: " + e);
                                mInCallToneGenerator = null;
                            }
                        }
                    } else {
                        if (mInCallToneGenerator != null) {
                            mInCallToneGenerator.release();
                            mInCallToneGenerator = null;
                        }
                     }
                }
            } else if (action.equals(Intent.ACTION_USER_STOPPED)) {
                int userHandle = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                if (userHandle >= 0) {
                    cancelAllNotificationsInt(MY_UID, MY_PID, null, null, 0, 0, true, userHandle,
                            REASON_USER_STOPPED, null);
                }
            } else if (action.equals(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)) {
                int userHandle = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);
                if (userHandle >= 0) {
                    cancelAllNotificationsInt(MY_UID, MY_PID, null, null, 0, 0, true, userHandle,
                            REASON_PROFILE_TURNED_OFF, null);
                }
            } else if (action.equals(Intent.ACTION_USER_PRESENT)) {
                // turn off LED when user passes through lock screen
                // if lights with screen on is disabled.
                if (!mScreenOnEnabled) {
                    mNotificationLight.turnOff();
                }
            } else if (action.equals(Intent.ACTION_USER_SWITCHED)) {
                final int user = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
                // reload per-user settings
                mSettingsObserver.update(null);
                mUserProfiles.updateCache(context);
                // Refresh managed services
                mConditionProviders.onUserSwitched(user);
                mListeners.onUserSwitched(user);
                mNotificationAssistants.onUserSwitched(user);
                mZenModeHelper.onUserSwitched(user);
            } else if (action.equals(Intent.ACTION_USER_ADDED)) {
                mUserProfiles.updateCache(context);
            } else if (action.equals(Intent.ACTION_USER_REMOVED)) {
                final int user = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
                mZenModeHelper.onUserRemoved(user);
                mRankingHelper.onUserRemoved(user);
                savePolicyFile();
            } else if (action.equals(Intent.ACTION_USER_UNLOCKED)) {
                final int user = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
                mConditionProviders.onUserUnlocked(user);
                mListeners.onUserUnlocked(user);
                mNotificationAssistants.onUserUnlocked(user);
                mZenModeHelper.onUserUnlocked(user);
            }
        }
    };

    private final class LEDSettingsObserver extends ContentObserver {
        private final Uri NOTIFICATION_BADGING_URI
                = Settings.Secure.getUriFor(Settings.Secure.NOTIFICATION_BADGING);
        private final Uri NOTIFICATION_LIGHT_PULSE_URI
                = Settings.System.getUriFor(Settings.System.NOTIFICATION_LIGHT_PULSE);
        private final Uri NOTIFICATION_RATE_LIMIT_URI
                = Settings.Global.getUriFor(Settings.Global.MAX_NOTIFICATION_ENQUEUE_RATE);
        private final Uri ENABLED_NOTIFICATION_LISTENERS_URI
                = Settings.Secure.getUriFor(Settings.Secure.ENABLED_NOTIFICATION_LISTENERS);

        LEDSettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = getContext().getContentResolver();
            resolver.registerContentObserver(NOTIFICATION_BADGING_URI,
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(
                    NOTIFICATION_LIGHT_PULSE_URI, false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(
                    ENABLED_NOTIFICATION_LISTENERS_URI, false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(LineageSettings.System.getUriFor(
                    LineageSettings.System.NOTIFICATION_LIGHT_PULSE_DEFAULT_COLOR),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(LineageSettings.System.getUriFor(
                    LineageSettings.System.NOTIFICATION_LIGHT_PULSE_DEFAULT_LED_ON),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(LineageSettings.System.getUriFor(
                    LineageSettings.System.NOTIFICATION_LIGHT_PULSE_DEFAULT_LED_OFF),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(LineageSettings.System.getUriFor(
                    LineageSettings.System.NOTIFICATION_LIGHT_PULSE_CUSTOM_ENABLE),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(LineageSettings.System.getUriFor(
                    LineageSettings.System.NOTIFICATION_LIGHT_PULSE_CUSTOM_VALUES),
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(NOTIFICATION_LIGHT_PULSE_URI,
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(NOTIFICATION_RATE_LIMIT_URI,
                    false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(LineageSettings.System.getUriFor(
                    LineageSettings.System.NOTIFICATION_LIGHT_SCREEN_ON),
                    false, this, UserHandle.USER_ALL);
            if (mAdjustableNotificationLedBrightness) {
                resolver.registerContentObserver(LineageSettings.System.getUriFor(
                        LineageSettings.System.NOTIFICATION_LIGHT_BRIGHTNESS_LEVEL),
                        false, this, UserHandle.USER_ALL);
            }
            update(null);
        }

        @Override public void onChange(boolean selfChange, Uri uri) {
            update(uri);
        }

        public void update(Uri uri) {
            ContentResolver resolver = getContext().getContentResolver();

            // LED enabled
            mNotificationPulseEnabled = Settings.System.getIntForUser(resolver,
                    Settings.System.NOTIFICATION_LIGHT_PULSE, 0, UserHandle.USER_CURRENT) != 0;

            // LED default color
            mDefaultNotificationColor = LineageSettings.System.getIntForUser(resolver,
                    LineageSettings.System.NOTIFICATION_LIGHT_PULSE_DEFAULT_COLOR,
                    mDefaultNotificationColor, UserHandle.USER_CURRENT);

            // LED default on MS
            mDefaultNotificationLedOn = LineageSettings.System.getIntForUser(resolver,
                    LineageSettings.System.NOTIFICATION_LIGHT_PULSE_DEFAULT_LED_ON,
                    mDefaultNotificationLedOn, UserHandle.USER_CURRENT);

            // LED default off MS
            mDefaultNotificationLedOff = LineageSettings.System.getIntForUser(resolver,
                    LineageSettings.System.NOTIFICATION_LIGHT_PULSE_DEFAULT_LED_OFF,
                    mDefaultNotificationLedOff, UserHandle.USER_CURRENT);

            // LED custom notification colors
            mNotificationPulseCustomLedValues.clear();
            if (LineageSettings.System.getIntForUser(resolver,
                    LineageSettings.System.NOTIFICATION_LIGHT_PULSE_CUSTOM_ENABLE, 0,
                    UserHandle.USER_CURRENT) != 0) {
                parseNotificationPulseCustomValuesString(LineageSettings.System.getStringForUser(
                        resolver, LineageSettings.System.NOTIFICATION_LIGHT_PULSE_CUSTOM_VALUES,
                        UserHandle.USER_CURRENT));
            }
            if (uri == null || NOTIFICATION_RATE_LIMIT_URI.equals(uri)) {
                mMaxPackageEnqueueRate = Settings.Global.getFloat(resolver,
                            Settings.Global.MAX_NOTIFICATION_ENQUEUE_RATE, mMaxPackageEnqueueRate);
            }
            if (uri == null || NOTIFICATION_BADGING_URI.equals(uri)) {
                mRankingHelper.updateBadgingEnabled();
            }

            // Notification LED brightness
            if (mAdjustableNotificationLedBrightness) {
                mNotificationLedBrightnessLevel = LineageSettings.System.getIntForUser(resolver,
                        LineageSettings.System.NOTIFICATION_LIGHT_BRIGHTNESS_LEVEL,
                        LIGHT_BRIGHTNESS_MAXIMUM, UserHandle.USER_CURRENT);
            }

            // Notification lights with screen on
            mScreenOnEnabled = (LineageSettings.System.getIntForUser(resolver,
                    LineageSettings.System.NOTIFICATION_LIGHT_SCREEN_ON,
                    mScreenOnDefault ? 1 : 0, UserHandle.USER_CURRENT) != 0);

            updateNotificationPulse();
        }
    }

    private LEDSettingsObserver mSettingsObserver;
    private ZenModeHelper mZenModeHelper;

    static long[] getLongArray(Resources r, int resid, int maxlen, long[] def) {
        int[] ar = r.getIntArray(resid);
        if (ar == null) {
            return def;
        }
        final int len = ar.length > maxlen ? maxlen : ar.length;
        long[] out = new long[len];
        for (int i=0; i<len; i++) {
            out[i] = ar[i];
        }
        return out;
    }

    public NotificationManagerService(Context context) {
        super(context);
        Notification.processWhitelistToken = WHITELIST_TOKEN;
    }

    // TODO - replace these methods with a single VisibleForTesting constructor
    @VisibleForTesting
    void setAudioManager(AudioManager audioMananger) {
        mAudioManager = audioMananger;
    }

    @VisibleForTesting
    void setVibrator(Vibrator vibrator) {
        mVibrator = vibrator;
    }

    @VisibleForTesting
    void setLights(Light light) {
        mNotificationLight = light;
        mAttentionLight = light;
        mNotificationPulseEnabled = true;
    }

    @VisibleForTesting
    void setScreenOn(boolean on) {
        mScreenOn = on;
    }

    @VisibleForTesting
    void addNotification(NotificationRecord r) {
        mNotificationList.add(r);
        mNotificationsByKey.put(r.sbn.getKey(), r);
        if (r.sbn.isGroup()) {
            mSummaryByGroupKey.put(r.getGroupKey(), r);
        }
    }

    @VisibleForTesting
    void addEnqueuedNotification(NotificationRecord r) {
        mEnqueuedNotifications.add(r);
    }

    @VisibleForTesting
    void setSystemReady(boolean systemReady) {
        mSystemReady = systemReady;
    }

    @VisibleForTesting
    void setHandler(Handler handler) {
        mHandler = handler;
    }

    @VisibleForTesting
    void setFallbackVibrationPattern(long[] vibrationPattern) {
        mFallbackVibrationPattern = vibrationPattern;
    }

    @VisibleForTesting
    void setPackageManager(IPackageManager packageManager) {
        mPackageManager = packageManager;
    }

    @VisibleForTesting
    void setRankingHelper(RankingHelper rankingHelper) {
        mRankingHelper = rankingHelper;
    }

    @VisibleForTesting
    void setIsTelevision(boolean isTelevision) {
        mIsTelevision = isTelevision;
    }

    // TODO: Tests should call onStart instead once the methods above are removed.
    @VisibleForTesting
    void init(Looper looper, IPackageManager packageManager, PackageManager packageManagerClient,
            LightsManager lightsManager, NotificationListeners notificationListeners,
            ICompanionDeviceManager companionManager, SnoozeHelper snoozeHelper,
            NotificationUsageStats usageStats) {
        Resources resources = getContext().getResources();
        mMaxPackageEnqueueRate = Settings.Global.getFloat(getContext().getContentResolver(),
                Settings.Global.MAX_NOTIFICATION_ENQUEUE_RATE,
                DEFAULT_MAX_NOTIFICATION_ENQUEUE_RATE);

        mAm = ActivityManager.getService();
        mPackageManager = packageManager;
        mPackageManagerClient = packageManagerClient;
        mAppOps = (AppOpsManager) getContext().getSystemService(Context.APP_OPS_SERVICE);
        mVibrator = (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
        mAppUsageStats = LocalServices.getService(UsageStatsManagerInternal.class);
        mAlarmManager = (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
        mCompanionManager = companionManager;
        mKeyguardManager =
                (KeyguardManager) getContext().getSystemService(Context.KEYGUARD_SERVICE);

        mHandler = new WorkerHandler(looper);
        mRankingThread.start();
        String[] extractorNames;
        try {
            extractorNames = resources.getStringArray(R.array.config_notificationSignalExtractors);
        } catch (Resources.NotFoundException e) {
            extractorNames = new String[0];
        }
        mUsageStats = usageStats;
        mRankingHandler = new RankingHandlerWorker(mRankingThread.getLooper());
        mRankingHelper = new RankingHelper(getContext(),
                getContext().getPackageManager(),
                mRankingHandler,
                mUsageStats,
                extractorNames);
        mConditionProviders = new ConditionProviders(getContext(), mHandler, mUserProfiles);
        mZenModeHelper = new ZenModeHelper(getContext(), mHandler.getLooper(), mConditionProviders);
        mZenModeHelper.addCallback(new ZenModeHelper.Callback() {
            @Override
            public void onConfigChanged() {
                savePolicyFile();
            }

            @Override
            void onZenModeChanged() {
                sendRegisteredOnlyBroadcast(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED);
                getContext().sendBroadcastAsUser(
                        new Intent(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED_INTERNAL)
                                .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT),
                        UserHandle.ALL, android.Manifest.permission.MANAGE_NOTIFICATIONS);
                synchronized (mNotificationLock) {
                    updateInterruptionFilterLocked();
                }
            }

            @Override
            void onPolicyChanged() {
                sendRegisteredOnlyBroadcast(NotificationManager.ACTION_NOTIFICATION_POLICY_CHANGED);
            }
        });
        mSnoozeHelper = snoozeHelper;
        mGroupHelper = new GroupHelper(new GroupHelper.Callback() {
            @Override
            public void addAutoGroup(String key) {
                synchronized (mNotificationLock) {
                    addAutogroupKeyLocked(key);
                }
                mRankingHandler.requestSort(false);
            }

            @Override
            public void removeAutoGroup(String key) {
                synchronized (mNotificationLock) {
                    removeAutogroupKeyLocked(key);
                }
                mRankingHandler.requestSort(false);
            }

            @Override
            public void addAutoGroupSummary(int userId, String pkg, String triggeringKey) {
                createAutoGroupSummary(userId, pkg, triggeringKey);
            }

            @Override
            public void removeAutoGroupSummary(int userId, String pkg) {
                synchronized (mNotificationLock) {
                    clearAutogroupSummaryLocked(userId, pkg);
                }
            }
        });

        final File systemDir = new File(Environment.getDataDirectory(), "system");
        mPolicyFile = new AtomicFile(new File(systemDir, "notification_policy.xml"));

        loadPolicyFile();

        // This is a ManagedServices object that keeps track of the listeners.
        mListeners = notificationListeners;

        // This is a MangedServices object that keeps track of the assistant.
        mNotificationAssistants = new NotificationAssistants();

        mStatusBar = getLocalService(StatusBarManagerInternal.class);
        if (mStatusBar != null) {
            mStatusBar.setNotificationDelegate(mNotificationDelegate);
        }

        mNotificationLight = lightsManager.getLight(LightsManager.LIGHT_ID_NOTIFICATIONS);
        mAttentionLight = lightsManager.getLight(LightsManager.LIGHT_ID_ATTENTION);

        mDefaultNotificationColor = resources.getColor(
                R.color.config_defaultNotificationColor);
        mDefaultNotificationLedOn = resources.getInteger(
                R.integer.config_defaultNotificationLedOn);
        mDefaultNotificationLedOff = resources.getInteger(
                R.integer.config_defaultNotificationLedOff);

        mNotificationPulseCustomLedValues = new ArrayMap<String, NotificationLedValues>();

        mPackageNameMappings = new ArrayMap<String, String>();
        final String[] defaultMapping = resources.getStringArray(
                com.android.internal.R.array.notification_light_package_mapping);
        for (String mapping : defaultMapping) {
            String[] map = mapping.split("\\|");
            mPackageNameMappings.put(map[0], map[1]);
        }

        mDefaultVibrationPattern = getLongArray(resources,
                R.array.config_defaultNotificationVibePattern,
                VIBRATE_PATTERN_MAXLEN,
                DEFAULT_VIBRATE_PATTERN);

        mFallbackVibrationPattern = getLongArray(resources,
                R.array.config_notificationFallbackVibePattern,
                VIBRATE_PATTERN_MAXLEN,
                DEFAULT_VIBRATE_PATTERN);

        mAdjustableNotificationLedBrightness = LightsCapabilities.supports(
                getContext(), LightsCapabilities.LIGHTS_ADJUSTABLE_NOTIFICATION_LED_BRIGHTNESS);

        mUseAttentionLight = resources.getBoolean(R.bool.config_useAttentionLight);

        // Don't start allowing notifications until the setup wizard has run once.
        // After that, including subsequent boots, init with notifications turned on.
        // This works on the first boot because the setup wizard will toggle this
        // flag at least once and we'll go back to 0 after that.
        if (0 == Settings.Global.getInt(getContext().getContentResolver(),
                    Settings.Global.DEVICE_PROVISIONED, 0)) {
            mDisableNotificationEffects = true;
        }
        mZenModeHelper.initZenMode();
        mInterruptionFilter = mZenModeHelper.getZenModeListenerInterruptionFilter();

        mUserProfiles.updateCache(getContext());
        listenForCallState();

        // register for various Intents
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_USER_STOPPED);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_USER_ADDED);
        filter.addAction(Intent.ACTION_USER_REMOVED);
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        filter.addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE);
        getContext().registerReceiver(mIntentReceiver, filter);

        IntentFilter pkgFilter = new IntentFilter();
        pkgFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_RESTARTED);
        pkgFilter.addAction(Intent.ACTION_QUERY_PACKAGE_RESTART);
        pkgFilter.addDataScheme("package");
        getContext().registerReceiverAsUser(mPackageIntentReceiver, UserHandle.ALL, pkgFilter, null,
                null);

        IntentFilter suspendedPkgFilter = new IntentFilter();
        suspendedPkgFilter.addAction(Intent.ACTION_PACKAGES_SUSPENDED);
        getContext().registerReceiverAsUser(mPackageIntentReceiver, UserHandle.ALL,
                suspendedPkgFilter, null, null);

        IntentFilter sdFilter = new IntentFilter(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        getContext().registerReceiverAsUser(mPackageIntentReceiver, UserHandle.ALL, sdFilter, null,
                null);

        IntentFilter timeoutFilter = new IntentFilter(ACTION_NOTIFICATION_TIMEOUT);
        timeoutFilter.addDataScheme(SCHEME_TIMEOUT);
        getContext().registerReceiver(mNotificationTimeoutReceiver, timeoutFilter);

        mSettingsObserver = new LEDSettingsObserver(mHandler);
        mSettingsObserver.observe();

        mArchive = new Archive(resources.getInteger(
                R.integer.config_notificationServiceArchiveSize));

        mIsTelevision = mPackageManagerClient.hasSystemFeature(FEATURE_LEANBACK)
                || mPackageManagerClient.hasSystemFeature(FEATURE_TELEVISION);
    }

    @Override
    public void onStart() {
        SnoozeHelper snoozeHelper = new SnoozeHelper(getContext(), new SnoozeHelper.Callback() {
            @Override
            public void repost(int userId, NotificationRecord r) {
                try {
                    if (DBG) {
                        Slog.d(TAG, "Reposting " + r.getKey());
                    }
                    enqueueNotificationInternal(r.sbn.getPackageName(), r.sbn.getOpPkg(),
                            r.sbn.getUid(), r.sbn.getInitialPid(), r.sbn.getTag(), r.sbn.getId(),
                            r.sbn.getNotification(), userId);
                } catch (Exception e) {
                    Slog.e(TAG, "Cannot un-snooze notification", e);
                }
            }
        }, mUserProfiles);

        init(Looper.myLooper(), AppGlobals.getPackageManager(), getContext().getPackageManager(),
                getLocalService(LightsManager.class), new NotificationListeners(),
                null, snoozeHelper, new NotificationUsageStats(getContext()));
        publishBinderService(Context.NOTIFICATION_SERVICE, mService);
        publishLocalService(NotificationManagerInternal.class, mInternalService);
    }

    private void sendRegisteredOnlyBroadcast(String action) {
        getContext().sendBroadcastAsUser(new Intent(action)
                .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY), UserHandle.ALL, null);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            // no beeping until we're basically done booting
            mSystemReady = true;

            // Grab our optional AudioService
            mAudioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            mAudioManagerInternal = getLocalService(AudioManagerInternal.class);
            mWindowManagerInternal = LocalServices.getService(WindowManagerInternal.class);
            mZenModeHelper.onSystemReady();
        } else if (phase == SystemService.PHASE_THIRD_PARTY_APPS_CAN_START) {
            // This observer will force an update when observe is called, causing us to
            // bind to listener services.
            mSettingsObserver.observe();
            mListeners.onBootPhaseAppsCanStart();
            mNotificationAssistants.onBootPhaseAppsCanStart();
            mConditionProviders.onBootPhaseAppsCanStart();
        }
    }

    @GuardedBy("mNotificationLock")
    private void updateListenerHintsLocked() {
        final int hints = calculateHints();
        if (hints == mListenerHints) return;
        ZenLog.traceListenerHintsChanged(mListenerHints, hints, mEffectsSuppressors.size());
        mListenerHints = hints;
        scheduleListenerHintsChanged(hints);
    }

    @GuardedBy("mNotificationLock")
    private void updateEffectsSuppressorLocked() {
        final long updatedSuppressedEffects = calculateSuppressedEffects();
        if (updatedSuppressedEffects == mZenModeHelper.getSuppressedEffects()) return;
        final List<ComponentName> suppressors = getSuppressors();
        ZenLog.traceEffectsSuppressorChanged(mEffectsSuppressors, suppressors, updatedSuppressedEffects);
        mEffectsSuppressors = suppressors;
        mZenModeHelper.setSuppressedEffects(updatedSuppressedEffects);
        sendRegisteredOnlyBroadcast(NotificationManager.ACTION_EFFECTS_SUPPRESSOR_CHANGED);
    }

    private void updateNotificationChannelInt(String pkg, int uid, NotificationChannel channel,
            boolean fromListener) {
        if (channel.getImportance() == NotificationManager.IMPORTANCE_NONE) {
            // cancel
            cancelAllNotificationsInt(MY_UID, MY_PID, pkg, channel.getId(), 0, 0, true,
                    UserHandle.getUserId(Binder.getCallingUid()), REASON_CHANNEL_BANNED,
                    null);
        }
        mRankingHelper.updateNotificationChannel(pkg, uid, channel);

        final NotificationChannel modifiedChannel =
                mRankingHelper.getNotificationChannel(pkg, uid, channel.getId(), false);

        if (!fromListener) {
            mListeners.notifyNotificationChannelChanged(
                    pkg, UserHandle.getUserHandleForUid(uid),
                    modifiedChannel, NOTIFICATION_CHANNEL_OR_GROUP_UPDATED);
        }

        synchronized (mNotificationLock) {
            final int N = mNotificationList.size();
            for (int i = N - 1; i >= 0; --i) {
                NotificationRecord r = mNotificationList.get(i);
                if (r.sbn.getPackageName().equals(pkg)
                        && r.sbn.getUid() == uid
                        && channel.getId() != null
                        && channel.getId().equals(r.getChannel().getId())) {
                    r.updateNotificationChannel(modifiedChannel);
                }
            }
        }
        mRankingHandler.requestSort(true);
        savePolicyFile();
    }

    private ArrayList<ComponentName> getSuppressors() {
        ArrayList<ComponentName> names = new ArrayList<ComponentName>();
        for (int i = mListenersDisablingEffects.size() - 1; i >= 0; --i) {
            ArraySet<ManagedServiceInfo> serviceInfoList = mListenersDisablingEffects.valueAt(i);

            for (ManagedServiceInfo info : serviceInfoList) {
                names.add(info.component);
            }
        }

        return names;
    }

    private boolean removeDisabledHints(ManagedServiceInfo info) {
        return removeDisabledHints(info, 0);
    }

    private boolean removeDisabledHints(ManagedServiceInfo info, int hints) {
        boolean removed = false;

        for (int i = mListenersDisablingEffects.size() - 1; i >= 0; --i) {
            final int hint = mListenersDisablingEffects.keyAt(i);
            final ArraySet<ManagedServiceInfo> listeners =
                    mListenersDisablingEffects.valueAt(i);

            if (hints == 0 || (hint & hints) == hint) {
                removed = removed || listeners.remove(info);
            }
        }

        return removed;
    }

    private void addDisabledHints(ManagedServiceInfo info, int hints) {
        if ((hints & HINT_HOST_DISABLE_EFFECTS) != 0) {
            addDisabledHint(info, HINT_HOST_DISABLE_EFFECTS);
        }

        if ((hints & HINT_HOST_DISABLE_NOTIFICATION_EFFECTS) != 0) {
            addDisabledHint(info, HINT_HOST_DISABLE_NOTIFICATION_EFFECTS);
        }

        if ((hints & HINT_HOST_DISABLE_CALL_EFFECTS) != 0) {
            addDisabledHint(info, HINT_HOST_DISABLE_CALL_EFFECTS);
        }
    }

    private void addDisabledHint(ManagedServiceInfo info, int hint) {
        if (mListenersDisablingEffects.indexOfKey(hint) < 0) {
            mListenersDisablingEffects.put(hint, new ArraySet<ManagedServiceInfo>());
        }

        ArraySet<ManagedServiceInfo> hintListeners = mListenersDisablingEffects.get(hint);
        hintListeners.add(info);
    }

    private int calculateHints() {
        int hints = 0;
        for (int i = mListenersDisablingEffects.size() - 1; i >= 0; --i) {
            int hint = mListenersDisablingEffects.keyAt(i);
            ArraySet<ManagedServiceInfo> serviceInfoList = mListenersDisablingEffects.valueAt(i);

            if (!serviceInfoList.isEmpty()) {
                hints |= hint;
            }
        }

        return hints;
    }

    private long calculateSuppressedEffects() {
        int hints = calculateHints();
        long suppressedEffects = 0;

        if ((hints & HINT_HOST_DISABLE_EFFECTS) != 0) {
            suppressedEffects |= ZenModeHelper.SUPPRESSED_EFFECT_ALL;
        }

        if ((hints & HINT_HOST_DISABLE_NOTIFICATION_EFFECTS) != 0) {
            suppressedEffects |= ZenModeHelper.SUPPRESSED_EFFECT_NOTIFICATIONS;
        }

        if ((hints & HINT_HOST_DISABLE_CALL_EFFECTS) != 0) {
            suppressedEffects |= ZenModeHelper.SUPPRESSED_EFFECT_CALLS;
        }

        return suppressedEffects;
    }

    @GuardedBy("mNotificationLock")
    private void updateInterruptionFilterLocked() {
        int interruptionFilter = mZenModeHelper.getZenModeListenerInterruptionFilter();
        if (interruptionFilter == mInterruptionFilter) return;
        mInterruptionFilter = interruptionFilter;
        scheduleInterruptionFilterChanged(interruptionFilter);
    }

    @VisibleForTesting
    INotificationManager getBinderService() {
        return INotificationManager.Stub.asInterface(mService);
    }

    @VisibleForTesting
    NotificationManagerInternal getInternalService() {
        return mInternalService;
    }

    private final IBinder mService = new INotificationManager.Stub() {
        // Toasts
        // ============================================================================

        @Override
        public void enqueueToast(String pkg, ITransientNotification callback, int duration)
        {
            if (DBG) {
                Slog.i(TAG, "enqueueToast pkg=" + pkg + " callback=" + callback
                        + " duration=" + duration);
            }

            if (pkg == null || callback == null) {
                Slog.e(TAG, "Not doing toast. pkg=" + pkg + " callback=" + callback);
                return ;
            }

            final boolean isSystemToast = isCallerSystemOrPhone() || ("android".equals(pkg));
            final boolean isPackageSuspended =
                    isPackageSuspendedForUser(pkg, Binder.getCallingUid());

            if (ENABLE_BLOCKED_TOASTS && !isSystemToast &&
                    (!areNotificationsEnabledForPackage(pkg, Binder.getCallingUid())
                            || isPackageSuspended)) {
                Slog.e(TAG, "Suppressing toast from package " + pkg
                        + (isPackageSuspended
                                ? " due to package suspended by administrator."
                                : " by user request."));
                return;
            }

            synchronized (mToastQueue) {
                int callingPid = Binder.getCallingPid();
                long callingId = Binder.clearCallingIdentity();
                try {
                    ToastRecord record;
                    int index = indexOfToastLocked(pkg, callback);
                    // If it's already in the queue, we update it in place, we don't
                    // move it to the end of the queue.
                    if (index >= 0) {
                        record = mToastQueue.get(index);
                        record.update(duration);
                    } else {
                        // Limit the number of toasts that any given package except the android
                        // package can enqueue.  Prevents DOS attacks and deals with leaks.
                        if (!isSystemToast) {
                            int count = 0;
                            final int N = mToastQueue.size();
                            for (int i=0; i<N; i++) {
                                 final ToastRecord r = mToastQueue.get(i);
                                 if (r.pkg.equals(pkg)) {
                                     count++;
                                     if (count >= MAX_PACKAGE_NOTIFICATIONS) {
                                         Slog.e(TAG, "Package has already posted " + count
                                                + " toasts. Not showing more. Package=" + pkg);
                                         return;
                                     }
                                 }
                            }
                        }

                        Binder token = new Binder();
                        mWindowManagerInternal.addWindowToken(token, TYPE_TOAST, DEFAULT_DISPLAY);
                        record = new ToastRecord(callingPid, pkg, callback, duration, token);
                        mToastQueue.add(record);
                        index = mToastQueue.size() - 1;
                        keepProcessAliveIfNeededLocked(callingPid);
                    }
                    // If it's at index 0, it's the current toast.  It doesn't matter if it's
                    // new or just been updated.  Call back and tell it to show itself.
                    // If the callback fails, this will remove it from the list, so don't
                    // assume that it's valid after this.
                    if (index == 0) {
                        showNextToastLocked();
                    }
                } finally {
                    Binder.restoreCallingIdentity(callingId);
                }
            }
        }

        @Override
        public void cancelToast(String pkg, ITransientNotification callback) {
            Slog.i(TAG, "cancelToast pkg=" + pkg + " callback=" + callback);

            if (pkg == null || callback == null) {
                Slog.e(TAG, "Not cancelling notification. pkg=" + pkg + " callback=" + callback);
                return ;
            }

            synchronized (mToastQueue) {
                long callingId = Binder.clearCallingIdentity();
                try {
                    int index = indexOfToastLocked(pkg, callback);
                    if (index >= 0) {
                        cancelToastLocked(index);
                    } else {
                        Slog.w(TAG, "Toast already cancelled. pkg=" + pkg
                                + " callback=" + callback);
                    }
                } finally {
                    Binder.restoreCallingIdentity(callingId);
                }
            }
        }

        @Override
        public void enqueueNotificationWithTag(String pkg, String opPkg, String tag, int id,
                Notification notification, int userId) throws RemoteException {
            enqueueNotificationInternal(pkg, opPkg, Binder.getCallingUid(),
                    Binder.getCallingPid(), tag, id, notification, userId);
        }

        @Override
        public void cancelNotificationWithTag(String pkg, String tag, int id, int userId) {
            checkCallerIsSystemOrSameApp(pkg);
            userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, true, false, "cancelNotificationWithTag", pkg);
            // Don't allow client applications to cancel foreground service notis or autobundled
            // summaries.
            final int mustNotHaveFlags = isCallingUidSystem() ? 0 :
                    (Notification.FLAG_FOREGROUND_SERVICE | Notification.FLAG_AUTOGROUP_SUMMARY);
            cancelNotification(Binder.getCallingUid(), Binder.getCallingPid(), pkg, tag, id, 0,
                    mustNotHaveFlags, false, userId, REASON_APP_CANCEL, null);
        }

        @Override
        public void cancelAllNotifications(String pkg, int userId) {
            checkCallerIsSystemOrSameApp(pkg);

            userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, true, false, "cancelAllNotifications", pkg);

            // Calling from user space, don't allow the canceling of actively
            // running foreground services.
            cancelAllNotificationsInt(Binder.getCallingUid(), Binder.getCallingPid(),
                    pkg, null, 0, Notification.FLAG_FOREGROUND_SERVICE, true, userId,
                    REASON_APP_CANCEL_ALL, null);
        }

        @Override
        public void setNotificationsEnabledForPackage(String pkg, int uid, boolean enabled) {
            checkCallerIsSystem();

            mRankingHelper.setEnabled(pkg, uid, enabled);
            // Now, cancel any outstanding notifications that are part of a just-disabled app
            if (!enabled) {
                cancelAllNotificationsInt(MY_UID, MY_PID, pkg, null, 0, 0, true,
                        UserHandle.getUserId(uid), REASON_PACKAGE_BANNED, null);
            }
            savePolicyFile();
        }

        /**
         * Use this when you just want to know if notifications are OK for this package.
         */
        @Override
        public boolean areNotificationsEnabled(String pkg) {
            return areNotificationsEnabledForPackage(pkg, Binder.getCallingUid());
        }

        /**
         * Use this when you just want to know if notifications are OK for this package.
         */
        @Override
        public boolean areNotificationsEnabledForPackage(String pkg, int uid) {
            checkCallerIsSystemOrSameApp(pkg);

            return mRankingHelper.getImportance(pkg, uid) != IMPORTANCE_NONE;
        }

        @Override
        public int getPackageImportance(String pkg) {
            checkCallerIsSystemOrSameApp(pkg);
            return mRankingHelper.getImportance(pkg, Binder.getCallingUid());
        }

        @Override
        public boolean canShowBadge(String pkg, int uid) {
            checkCallerIsSystem();
            return mRankingHelper.canShowBadge(pkg, uid);
        }

        @Override
        public void setShowBadge(String pkg, int uid, boolean showBadge) {
            checkCallerIsSystem();
            mRankingHelper.setShowBadge(pkg, uid, showBadge);
            savePolicyFile();
        }

        @Override
        public void createNotificationChannelGroups(String pkg,
                ParceledListSlice channelGroupList) throws RemoteException {
            checkCallerIsSystemOrSameApp(pkg);
            List<NotificationChannelGroup> groups = channelGroupList.getList();
            final int groupSize = groups.size();
            for (int i = 0; i < groupSize; i++) {
                final NotificationChannelGroup group = groups.get(i);
                Preconditions.checkNotNull(group, "group in list is null");
                mRankingHelper.createNotificationChannelGroup(pkg, Binder.getCallingUid(), group,
                        true /* fromTargetApp */);
                mListeners.notifyNotificationChannelGroupChanged(pkg,
                        UserHandle.of(UserHandle.getCallingUserId()), group,
                        NOTIFICATION_CHANNEL_OR_GROUP_ADDED);
            }
            savePolicyFile();
        }

        private void createNotificationChannelsImpl(String pkg, int uid,
                ParceledListSlice channelsList) {
            List<NotificationChannel> channels = channelsList.getList();
            final int channelsSize = channels.size();
            for (int i = 0; i < channelsSize; i++) {
                final NotificationChannel channel = channels.get(i);
                Preconditions.checkNotNull(channel, "channel in list is null");
                mRankingHelper.createNotificationChannel(pkg, uid, channel,
                        true /* fromTargetApp */);
                mListeners.notifyNotificationChannelChanged(pkg,
                        UserHandle.getUserHandleForUid(uid),
                        mRankingHelper.getNotificationChannel(pkg, uid, channel.getId(), false),
                        NOTIFICATION_CHANNEL_OR_GROUP_ADDED);
            }
            savePolicyFile();
        }

        @Override
        public void createNotificationChannels(String pkg,
                ParceledListSlice channelsList) throws RemoteException {
            checkCallerIsSystemOrSameApp(pkg);
            createNotificationChannelsImpl(pkg, Binder.getCallingUid(), channelsList);
        }

        @Override
        public void createNotificationChannelsForPackage(String pkg, int uid,
                ParceledListSlice channelsList) throws RemoteException {
            checkCallerIsSystem();
            createNotificationChannelsImpl(pkg, uid, channelsList);
        }

        @Override
        public NotificationChannel getNotificationChannel(String pkg, String channelId) {
            checkCallerIsSystemOrSameApp(pkg);
            return mRankingHelper.getNotificationChannel(
                    pkg, Binder.getCallingUid(), channelId, false /* includeDeleted */);
        }

        @Override
        public NotificationChannel getNotificationChannelForPackage(String pkg, int uid,
                String channelId, boolean includeDeleted) {
            checkCallerIsSystem();
            return mRankingHelper.getNotificationChannel(pkg, uid, channelId, includeDeleted);
        }

        @Override
        public void deleteNotificationChannel(String pkg, String channelId) {
            checkCallerIsSystemOrSameApp(pkg);
            final int callingUid = Binder.getCallingUid();
            if (NotificationChannel.DEFAULT_CHANNEL_ID.equals(channelId)) {
                throw new IllegalArgumentException("Cannot delete default channel");
            }
            cancelAllNotificationsInt(MY_UID, MY_PID, pkg, channelId, 0, 0, true,
                    UserHandle.getUserId(callingUid), REASON_CHANNEL_BANNED, null);
            mRankingHelper.deleteNotificationChannel(pkg, callingUid, channelId);
            mListeners.notifyNotificationChannelChanged(pkg,
                    UserHandle.getUserHandleForUid(callingUid),
                    mRankingHelper.getNotificationChannel(pkg, callingUid, channelId, true),
                    NOTIFICATION_CHANNEL_OR_GROUP_DELETED);
            savePolicyFile();
        }

        @Override
        public ParceledListSlice<NotificationChannelGroup> getNotificationChannelGroups(
                String pkg) {
            checkCallerIsSystemOrSameApp(pkg);
            return new ParceledListSlice<>(new ArrayList(
                    mRankingHelper.getNotificationChannelGroups(pkg, Binder.getCallingUid())));
        }

        @Override
        public void deleteNotificationChannelGroup(String pkg, String groupId) {
            checkCallerIsSystemOrSameApp(pkg);

            final int callingUid = Binder.getCallingUid();
            NotificationChannelGroup groupToDelete =
                    mRankingHelper.getNotificationChannelGroup(groupId, pkg, callingUid);
            if (groupToDelete != null) {
                List<NotificationChannel> deletedChannels =
                        mRankingHelper.deleteNotificationChannelGroup(pkg, callingUid, groupId);
                for (int i = 0; i < deletedChannels.size(); i++) {
                    final NotificationChannel deletedChannel = deletedChannels.get(i);
                    cancelAllNotificationsInt(MY_UID, MY_PID, pkg, deletedChannel.getId(), 0, 0,
                            true,
                            UserHandle.getUserId(Binder.getCallingUid()), REASON_CHANNEL_BANNED,
                            null);
                    mListeners.notifyNotificationChannelChanged(pkg,
                            UserHandle.getUserHandleForUid(callingUid),
                            deletedChannel,
                            NOTIFICATION_CHANNEL_OR_GROUP_DELETED);
                }
                mListeners.notifyNotificationChannelGroupChanged(
                        pkg, UserHandle.getUserHandleForUid(callingUid), groupToDelete,
                        NOTIFICATION_CHANNEL_OR_GROUP_DELETED);
                savePolicyFile();
            }
        }

        @Override
        public void updateNotificationChannelForPackage(String pkg, int uid,
                NotificationChannel channel) {
            enforceSystemOrSystemUI("Caller not system or systemui");
            Preconditions.checkNotNull(channel);
            updateNotificationChannelInt(pkg, uid, channel, false);
        }

        @Override
        public ParceledListSlice<NotificationChannel> getNotificationChannelsForPackage(String pkg,
                int uid, boolean includeDeleted) {
            enforceSystemOrSystemUI("getNotificationChannelsForPackage");
            return mRankingHelper.getNotificationChannels(pkg, uid, includeDeleted);
        }

        @Override
        public int getNumNotificationChannelsForPackage(String pkg, int uid,
                boolean includeDeleted) {
            enforceSystemOrSystemUI("getNumNotificationChannelsForPackage");
            return mRankingHelper.getNotificationChannels(pkg, uid, includeDeleted)
                    .getList().size();
        }

        @Override
        public boolean onlyHasDefaultChannel(String pkg, int uid) {
            enforceSystemOrSystemUI("onlyHasDefaultChannel");
            return mRankingHelper.onlyHasDefaultChannel(pkg, uid);
        }

        @Override
        public int getDeletedChannelCount(String pkg, int uid) {
            enforceSystemOrSystemUI("getDeletedChannelCount");
            return mRankingHelper.getDeletedChannelCount(pkg, uid);
        }

        @Override
        public ParceledListSlice<NotificationChannelGroup> getNotificationChannelGroupsForPackage(
                String pkg, int uid, boolean includeDeleted) {
            checkCallerIsSystem();
            return mRankingHelper.getNotificationChannelGroups(pkg, uid, includeDeleted);
        }

        @Override
        public NotificationChannelGroup getNotificationChannelGroupForPackage(
                String groupId, String pkg, int uid) {
            enforceSystemOrSystemUI("getNotificationChannelGroupForPackage");
            return mRankingHelper.getNotificationChannelGroup(groupId, pkg, uid);
        }

        @Override
        public ParceledListSlice<NotificationChannel> getNotificationChannels(String pkg) {
            checkCallerIsSystemOrSameApp(pkg);
            return mRankingHelper.getNotificationChannels(
                    pkg, Binder.getCallingUid(), false /* includeDeleted */);
        }

        @Override
        public void clearData(String packageName, int uid, boolean fromApp) throws RemoteException {
            checkCallerIsSystem();

            // Cancel posted notifications
            cancelAllNotificationsInt(MY_UID, MY_PID, packageName, null, 0, 0, true,
                    UserHandle.getUserId(Binder.getCallingUid()), REASON_CHANNEL_BANNED, null);

            // Listener & assistant
            mListeners.onPackagesChanged(true, new String[] {packageName});
            mNotificationAssistants.onPackagesChanged(true, new String[] {packageName});

            // Zen
            mConditionProviders.onPackagesChanged(true, new String[] {packageName});

            // Reset notification preferences
            if (!fromApp) {
                mRankingHelper.onPackagesChanged(true, UserHandle.getCallingUserId(),
                        new String[]{packageName}, new int[]{uid});
            }

            savePolicyFile();
        }


        /**
         * System-only API for getting a list of current (i.e. not cleared) notifications.
         *
         * Requires ACCESS_NOTIFICATIONS which is signature|system.
         * @returns A list of all the notifications, in natural order.
         */
        @Override
        public StatusBarNotification[] getActiveNotifications(String callingPkg) {
            // enforce() will ensure the calling uid has the correct permission
            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.ACCESS_NOTIFICATIONS,
                    "NotificationManagerService.getActiveNotifications");

            StatusBarNotification[] tmp = null;
            int uid = Binder.getCallingUid();

            // noteOp will check to make sure the callingPkg matches the uid
            if (mAppOps.noteOpNoThrow(AppOpsManager.OP_ACCESS_NOTIFICATIONS, uid, callingPkg)
                    == AppOpsManager.MODE_ALLOWED) {
                synchronized (mNotificationLock) {
                    tmp = new StatusBarNotification[mNotificationList.size()];
                    final int N = mNotificationList.size();
                    for (int i=0; i<N; i++) {
                        tmp[i] = mNotificationList.get(i).sbn;
                    }
                }
            }
            return tmp;
        }

        /**
         * Public API for getting a list of current notifications for the calling package/uid.
         *
         * Note that since notification posting is done asynchronously, this will not return
         * notifications that are in the process of being posted.
         *
         * @returns A list of all the package's notifications, in natural order.
         */
        @Override
        public ParceledListSlice<StatusBarNotification> getAppActiveNotifications(String pkg,
                int incomingUserId) {
            checkCallerIsSystemOrSameApp(pkg);
            int userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                    Binder.getCallingUid(), incomingUserId, true, false,
                    "getAppActiveNotifications", pkg);
            synchronized (mNotificationLock) {
                final ArrayMap<String, StatusBarNotification> map
                        = new ArrayMap<>(mNotificationList.size() + mEnqueuedNotifications.size());
                final int N = mNotificationList.size();
                for (int i = 0; i < N; i++) {
                    StatusBarNotification sbn = sanitizeSbn(pkg, userId,
                            mNotificationList.get(i).sbn);
                    if (sbn != null) {
                        map.put(sbn.getKey(), sbn);
                    }
                }
                for(NotificationRecord snoozed: mSnoozeHelper.getSnoozed(userId, pkg)) {
                    StatusBarNotification sbn = sanitizeSbn(pkg, userId, snoozed.sbn);
                    if (sbn != null) {
                        map.put(sbn.getKey(), sbn);
                    }
                }
                final int M = mEnqueuedNotifications.size();
                for (int i = 0; i < M; i++) {
                    StatusBarNotification sbn = sanitizeSbn(pkg, userId,
                            mEnqueuedNotifications.get(i).sbn);
                    if (sbn != null) {
                        map.put(sbn.getKey(), sbn); // pending update overwrites existing post here
                    }
                }
                final ArrayList<StatusBarNotification> list = new ArrayList<>(map.size());
                list.addAll(map.values());
                return new ParceledListSlice<StatusBarNotification>(list);
            }
        }

        private StatusBarNotification sanitizeSbn(String pkg, int userId,
                StatusBarNotification sbn) {
            if (sbn.getPackageName().equals(pkg) && sbn.getUserId() == userId
                    && (sbn.getNotification().flags
                    & Notification.FLAG_AUTOGROUP_SUMMARY) == 0) {
                // We could pass back a cloneLight() but clients might get confused and
                // try to send this thing back to notify() again, which would not work
                // very well.
                return new StatusBarNotification(
                        sbn.getPackageName(),
                        sbn.getOpPkg(),
                        sbn.getId(), sbn.getTag(), sbn.getUid(), sbn.getInitialPid(),
                        sbn.getNotification().clone(),
                        sbn.getUser(), sbn.getOverrideGroupKey(), sbn.getPostTime());
            }
            return null;
        }

        /**
         * System-only API for getting a list of recent (cleared, no longer shown) notifications.
         *
         * Requires ACCESS_NOTIFICATIONS which is signature|system.
         */
        @Override
        public StatusBarNotification[] getHistoricalNotifications(String callingPkg, int count) {
            // enforce() will ensure the calling uid has the correct permission
            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.ACCESS_NOTIFICATIONS,
                    "NotificationManagerService.getHistoricalNotifications");

            StatusBarNotification[] tmp = null;
            int uid = Binder.getCallingUid();

            // noteOp will check to make sure the callingPkg matches the uid
            if (mAppOps.noteOpNoThrow(AppOpsManager.OP_ACCESS_NOTIFICATIONS, uid, callingPkg)
                    == AppOpsManager.MODE_ALLOWED) {
                synchronized (mArchive) {
                    tmp = mArchive.getArray(count);
                }
            }
            return tmp;
        }

        /**
         * Register a listener binder directly with the notification manager.
         *
         * Only works with system callers. Apps should extend
         * {@link android.service.notification.NotificationListenerService}.
         */
        @Override
        public void registerListener(final INotificationListener listener,
                final ComponentName component, final int userid) {
            enforceSystemOrSystemUI("INotificationManager.registerListener");
            mListeners.registerService(listener, component, userid);
        }

        /**
         * Remove a listener binder directly
         */
        @Override
        public void unregisterListener(INotificationListener token, int userid) {
            mListeners.unregisterService(token, userid);
        }

        /**
         * Allow an INotificationListener to simulate a "clear all" operation.
         *
         * {@see com.android.server.StatusBarManagerService.NotificationCallbacks#onClearAllNotifications}
         *
         * @param token The binder for the listener, to check that the caller is allowed
         */
        @Override
        public void cancelNotificationsFromListener(INotificationListener token, String[] keys) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationLock) {
                    final ManagedServiceInfo info = mListeners.checkServiceTokenLocked(token);
                    if (keys != null) {
                        final int N = keys.length;
                        for (int i = 0; i < N; i++) {
                            NotificationRecord r = mNotificationsByKey.get(keys[i]);
                            if (r == null) continue;
                            final int userId = r.sbn.getUserId();
                            if (userId != info.userid && userId != UserHandle.USER_ALL &&
                                    !mUserProfiles.isCurrentProfile(userId)) {
                                throw new SecurityException("Disallowed call from listener: "
                                        + info.service);
                            }
                            cancelNotificationFromListenerLocked(info, callingUid, callingPid,
                                    r.sbn.getPackageName(), r.sbn.getTag(), r.sbn.getId(),
                                    userId);
                        }
                    } else {
                        cancelAllLocked(callingUid, callingPid, info.userid,
                                REASON_LISTENER_CANCEL_ALL, info, info.supportsProfiles());
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        /**
         * Handle request from an approved listener to re-enable itself.
         *
         * @param component The componenet to be re-enabled, caller must match package.
         */
        @Override
        public void requestBindListener(ComponentName component) {
            checkCallerIsSystemOrSameApp(component.getPackageName());
            long identity = Binder.clearCallingIdentity();
            try {
                ManagedServices manager =
                        mNotificationAssistants.isComponentEnabledForCurrentProfiles(component)
                        ? mNotificationAssistants
                        : mListeners;
                manager.setComponentState(component, true);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void requestUnbindListener(INotificationListener token) {
            long identity = Binder.clearCallingIdentity();
            try {
                // allow bound services to disable themselves
                synchronized (mNotificationLock) {
                    final ManagedServiceInfo info = mListeners.checkServiceTokenLocked(token);
                    info.getOwner().setComponentState(info.component, false);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void setNotificationsShownFromListener(INotificationListener token, String[] keys) {
            long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationLock) {
                    final ManagedServiceInfo info = mListeners.checkServiceTokenLocked(token);
                    if (keys != null) {
                        final int N = keys.length;
                        for (int i = 0; i < N; i++) {
                            NotificationRecord r = mNotificationsByKey.get(keys[i]);
                            if (r == null) continue;
                            final int userId = r.sbn.getUserId();
                            if (userId != info.userid && userId != UserHandle.USER_ALL &&
                                    !mUserProfiles.isCurrentProfile(userId)) {
                                throw new SecurityException("Disallowed call from listener: "
                                        + info.service);
                            }
                            if (!r.isSeen()) {
                                if (DBG) Slog.d(TAG, "Marking notification as seen " + keys[i]);
                                mAppUsageStats.reportEvent(r.sbn.getPackageName(),
                                        userId == UserHandle.USER_ALL ? UserHandle.USER_SYSTEM
                                                : userId,
                                        UsageEvents.Event.USER_INTERACTION);
                                r.setSeen();
                            }
                        }
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        /**
         * Allow an INotificationListener to simulate clearing (dismissing) a single notification.
         *
         * {@see com.android.server.StatusBarManagerService.NotificationCallbacks#onNotificationClear}
         *
         * @param info The binder for the listener, to check that the caller is allowed
         */
        @GuardedBy("mNotificationLock")
        private void cancelNotificationFromListenerLocked(ManagedServiceInfo info,
                int callingUid, int callingPid, String pkg, String tag, int id, int userId) {
            cancelNotification(callingUid, callingPid, pkg, tag, id, 0,
                    Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE,
                    true,
                    userId, REASON_LISTENER_CANCEL, info);
        }

        /**
         * Allow an INotificationListener to snooze a single notification until a context.
         *
         * @param token The binder for the listener, to check that the caller is allowed
         */
        @Override
        public void snoozeNotificationUntilContextFromListener(INotificationListener token,
                String key, String snoozeCriterionId) {
            long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationLock) {
                    final ManagedServiceInfo info = mListeners.checkServiceTokenLocked(token);
                    snoozeNotificationInt(key, SNOOZE_UNTIL_UNSPECIFIED, snoozeCriterionId, info);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        /**
         * Allow an INotificationListener to snooze a single notification until a time.
         *
         * @param token The binder for the listener, to check that the caller is allowed
         */
        @Override
        public void snoozeNotificationUntilFromListener(INotificationListener token, String key,
                long duration) {
            long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationLock) {
                    final ManagedServiceInfo info = mListeners.checkServiceTokenLocked(token);
                    snoozeNotificationInt(key, duration, null, info);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        /**
         * Allows the notification assistant to un-snooze a single notification.
         *
         * @param token The binder for the assistant, to check that the caller is allowed
         */
        @Override
        public void unsnoozeNotificationFromAssistant(INotificationListener token, String key) {
            long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationLock) {
                    final ManagedServiceInfo info =
                            mNotificationAssistants.checkServiceTokenLocked(token);
                    unsnoozeNotificationInt(key, info);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        /**
         * Allow an INotificationListener to simulate clearing (dismissing) a single notification.
         *
         * {@see com.android.server.StatusBarManagerService.NotificationCallbacks#onNotificationClear}
         *
         * @param token The binder for the listener, to check that the caller is allowed
         */
        @Override
        public void cancelNotificationFromListener(INotificationListener token, String pkg,
                String tag, int id) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationLock) {
                    final ManagedServiceInfo info = mListeners.checkServiceTokenLocked(token);
                    if (info.supportsProfiles()) {
                        Log.e(TAG, "Ignoring deprecated cancelNotification(pkg, tag, id) "
                                + "from " + info.component
                                + " use cancelNotification(key) instead.");
                    } else {
                        cancelNotificationFromListenerLocked(info, callingUid, callingPid,
                                pkg, tag, id, info.userid);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        /**
         * Allow an INotificationListener to request the list of outstanding notifications seen by
         * the current user. Useful when starting up, after which point the listener callbacks
         * should be used.
         *
         * @param token The binder for the listener, to check that the caller is allowed
         * @param keys An array of notification keys to fetch, or null to fetch everything
         * @returns The return value will contain the notifications specified in keys, in that
         *      order, or if keys is null, all the notifications, in natural order.
         */
        @Override
        public ParceledListSlice<StatusBarNotification> getActiveNotificationsFromListener(
                INotificationListener token, String[] keys, int trim) {
            synchronized (mNotificationLock) {
                final ManagedServiceInfo info = mListeners.checkServiceTokenLocked(token);
                final boolean getKeys = keys != null;
                final int N = getKeys ? keys.length : mNotificationList.size();
                final ArrayList<StatusBarNotification> list
                        = new ArrayList<StatusBarNotification>(N);
                for (int i=0; i<N; i++) {
                    final NotificationRecord r = getKeys
                            ? mNotificationsByKey.get(keys[i])
                            : mNotificationList.get(i);
                    if (r == null) continue;
                    StatusBarNotification sbn = r.sbn;
                    if (!isVisibleToListener(sbn, info)) continue;
                    StatusBarNotification sbnToSend =
                            (trim == TRIM_FULL) ? sbn : sbn.cloneLight();
                    list.add(sbnToSend);
                }
                return new ParceledListSlice<StatusBarNotification>(list);
            }
        }

        /**
         * Allow an INotificationListener to request the list of outstanding snoozed notifications
         * seen by the current user. Useful when starting up, after which point the listener
         * callbacks should be used.
         *
         * @param token The binder for the listener, to check that the caller is allowed
         * @returns The return value will contain the notifications specified in keys, in that
         *      order, or if keys is null, all the notifications, in natural order.
         */
        @Override
        public ParceledListSlice<StatusBarNotification> getSnoozedNotificationsFromListener(
                INotificationListener token, int trim) {
            synchronized (mNotificationLock) {
                final ManagedServiceInfo info = mListeners.checkServiceTokenLocked(token);
                List<NotificationRecord> snoozedRecords = mSnoozeHelper.getSnoozed();
                final int N = snoozedRecords.size();
                final ArrayList<StatusBarNotification> list = new ArrayList<>(N);
                for (int i=0; i < N; i++) {
                    final NotificationRecord r = snoozedRecords.get(i);
                    if (r == null) continue;
                    StatusBarNotification sbn = r.sbn;
                    if (!isVisibleToListener(sbn, info)) continue;
                    StatusBarNotification sbnToSend =
                            (trim == TRIM_FULL) ? sbn : sbn.cloneLight();
                    list.add(sbnToSend);
                }
                return new ParceledListSlice<>(list);
            }
        }

        @Override
        public void requestHintsFromListener(INotificationListener token, int hints) {
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationLock) {
                    final ManagedServiceInfo info = mListeners.checkServiceTokenLocked(token);
                    final int disableEffectsMask = HINT_HOST_DISABLE_EFFECTS
                            | HINT_HOST_DISABLE_NOTIFICATION_EFFECTS
                            | HINT_HOST_DISABLE_CALL_EFFECTS;
                    final boolean disableEffects = (hints & disableEffectsMask) != 0;
                    if (disableEffects) {
                        addDisabledHints(info, hints);
                    } else {
                        removeDisabledHints(info, hints);
                    }
                    updateListenerHintsLocked();
                    updateEffectsSuppressorLocked();
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public int getHintsFromListener(INotificationListener token) {
            synchronized (mNotificationLock) {
                return mListenerHints;
            }
        }

        @Override
        public void requestInterruptionFilterFromListener(INotificationListener token,
                int interruptionFilter) throws RemoteException {
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationLock) {
                    final ManagedServiceInfo info = mListeners.checkServiceTokenLocked(token);
                    mZenModeHelper.requestFromListener(info.component, interruptionFilter);
                    updateInterruptionFilterLocked();
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public int getInterruptionFilterFromListener(INotificationListener token)
                throws RemoteException {
            synchronized (mNotificationLight) {
                return mInterruptionFilter;
            }
        }

        @Override
        public void setOnNotificationPostedTrimFromListener(INotificationListener token, int trim)
                throws RemoteException {
            synchronized (mNotificationLock) {
                final ManagedServiceInfo info = mListeners.checkServiceTokenLocked(token);
                if (info == null) return;
                mListeners.setOnNotificationPostedTrimLocked(info, trim);
            }
        }

        @Override
        public int getZenMode() {
            return mZenModeHelper.getZenMode();
        }

        @Override
        public ZenModeConfig getZenModeConfig() {
            enforceSystemOrSystemUI("INotificationManager.getZenModeConfig");
            return mZenModeHelper.getConfig();
        }

        @Override
        public void setZenMode(int mode, Uri conditionId, String reason) throws RemoteException {
            enforceSystemOrSystemUI("INotificationManager.setZenMode");
            final long identity = Binder.clearCallingIdentity();
            try {
                mZenModeHelper.setManualZenMode(mode, conditionId, null, reason);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public List<ZenModeConfig.ZenRule> getZenRules() throws RemoteException {
            enforcePolicyAccess(Binder.getCallingUid(), "getAutomaticZenRules");
            return mZenModeHelper.getZenRules();
        }

        @Override
        public AutomaticZenRule getAutomaticZenRule(String id) throws RemoteException {
            Preconditions.checkNotNull(id, "Id is null");
            enforcePolicyAccess(Binder.getCallingUid(), "getAutomaticZenRule");
            return mZenModeHelper.getAutomaticZenRule(id);
        }

        @Override
        public String addAutomaticZenRule(AutomaticZenRule automaticZenRule)
                throws RemoteException {
            Preconditions.checkNotNull(automaticZenRule, "automaticZenRule is null");
            Preconditions.checkNotNull(automaticZenRule.getName(), "Name is null");
            Preconditions.checkNotNull(automaticZenRule.getOwner(), "Owner is null");
            Preconditions.checkNotNull(automaticZenRule.getConditionId(), "ConditionId is null");
            enforcePolicyAccess(Binder.getCallingUid(), "addAutomaticZenRule");

            return mZenModeHelper.addAutomaticZenRule(automaticZenRule,
                    "addAutomaticZenRule");
        }

        @Override
        public boolean updateAutomaticZenRule(String id, AutomaticZenRule automaticZenRule)
                throws RemoteException {
            Preconditions.checkNotNull(automaticZenRule, "automaticZenRule is null");
            Preconditions.checkNotNull(automaticZenRule.getName(), "Name is null");
            Preconditions.checkNotNull(automaticZenRule.getOwner(), "Owner is null");
            Preconditions.checkNotNull(automaticZenRule.getConditionId(), "ConditionId is null");
            enforcePolicyAccess(Binder.getCallingUid(), "updateAutomaticZenRule");

            return mZenModeHelper.updateAutomaticZenRule(id, automaticZenRule,
                    "updateAutomaticZenRule");
        }

        @Override
        public boolean removeAutomaticZenRule(String id) throws RemoteException {
            Preconditions.checkNotNull(id, "Id is null");
            // Verify that they can modify zen rules.
            enforcePolicyAccess(Binder.getCallingUid(), "removeAutomaticZenRule");

            return mZenModeHelper.removeAutomaticZenRule(id, "removeAutomaticZenRule");
        }

        @Override
        public boolean removeAutomaticZenRules(String packageName) throws RemoteException {
            Preconditions.checkNotNull(packageName, "Package name is null");
            enforceSystemOrSystemUI("removeAutomaticZenRules");

            return mZenModeHelper.removeAutomaticZenRules(packageName, "removeAutomaticZenRules");
        }

        @Override
        public int getRuleInstanceCount(ComponentName owner) throws RemoteException {
            Preconditions.checkNotNull(owner, "Owner is null");
            enforceSystemOrSystemUI("getRuleInstanceCount");

            return mZenModeHelper.getCurrentInstanceCount(owner);
        }

        @Override
        public void setInterruptionFilter(String pkg, int filter) throws RemoteException {
            enforcePolicyAccess(pkg, "setInterruptionFilter");
            final int zen = NotificationManager.zenModeFromInterruptionFilter(filter, -1);
            if (zen == -1) throw new IllegalArgumentException("Invalid filter: " + filter);
            final long identity = Binder.clearCallingIdentity();
            try {
                mZenModeHelper.setManualZenMode(zen, null, pkg, "setInterruptionFilter");
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void notifyConditions(final String pkg, IConditionProvider provider,
                final Condition[] conditions) {
            final ManagedServiceInfo info = mConditionProviders.checkServiceToken(provider);
            checkCallerIsSystemOrSameApp(pkg);
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mConditionProviders.notifyConditions(pkg, info, conditions);
                }
            });
        }

        @Override
        public void requestUnbindProvider(IConditionProvider provider) {
            long identity = Binder.clearCallingIdentity();
            try {
                // allow bound services to disable themselves
                final ManagedServiceInfo info = mConditionProviders.checkServiceToken(provider);
                info.getOwner().setComponentState(info.component, false);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void requestBindProvider(ComponentName component) {
            checkCallerIsSystemOrSameApp(component.getPackageName());
            long identity = Binder.clearCallingIdentity();
            try {
                mConditionProviders.setComponentState(component, true);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        private void enforceSystemOrSystemUI(String message) {
            if (isCallerSystemOrPhone()) return;
            getContext().enforceCallingPermission(android.Manifest.permission.STATUS_BAR_SERVICE,
                    message);
        }

        private void enforceSystemOrSystemUIOrSamePackage(String pkg, String message) {
            try {
                checkCallerIsSystemOrSameApp(pkg);
            } catch (SecurityException e) {
                getContext().enforceCallingPermission(
                        android.Manifest.permission.STATUS_BAR_SERVICE,
                        message);
            }
        }

        private void enforcePolicyAccess(int uid, String method) {
            if (PackageManager.PERMISSION_GRANTED == getContext().checkCallingPermission(
                    android.Manifest.permission.MANAGE_NOTIFICATIONS)) {
                return;
            }
            boolean accessAllowed = false;
            String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
            final int packageCount = packages.length;
            for (int i = 0; i < packageCount; i++) {
                if (checkPolicyAccess(packages[i])) {
                    accessAllowed = true;
                }
            }
            if (!accessAllowed) {
                Slog.w(TAG, "Notification policy access denied calling " + method);
                throw new SecurityException("Notification policy access denied");
            }
        }

        private void enforcePolicyAccess(String pkg, String method) {
            if (PackageManager.PERMISSION_GRANTED == getContext().checkCallingPermission(
                    android.Manifest.permission.MANAGE_NOTIFICATIONS)) {
                return;
            }
            checkCallerIsSameApp(pkg);
            if (!checkPolicyAccess(pkg)) {
                Slog.w(TAG, "Notification policy access denied calling " + method);
                throw new SecurityException("Notification policy access denied");
            }
        }

        private boolean checkPackagePolicyAccess(String pkg) {
            return mPolicyAccess.isPackageGranted(pkg);
        }

        private boolean checkPolicyAccess(String pkg) {
            try {
                int uid = getContext().getPackageManager().getPackageUidAsUser(
                        pkg, UserHandle.getCallingUserId());
                if (PackageManager.PERMISSION_GRANTED == ActivityManager.checkComponentPermission(
                        android.Manifest.permission.MANAGE_NOTIFICATIONS, uid,
                        -1, true)) {
                    return true;
                }
            } catch (NameNotFoundException e) {
                return false;
            }
            return checkPackagePolicyAccess(pkg) || mListeners.isComponentEnabledForPackage(pkg);
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpAndUsageStatsPermission(getContext(), TAG, pw)) return;
            final DumpFilter filter = DumpFilter.parseFromArguments(args);
            if (filter != null && filter.stats) {
                dumpJson(pw, filter);
            } else if (filter != null && filter.proto) {
                dumpProto(fd, filter);
            } else {
                dumpImpl(pw, filter);
            }
        }

        @Override
        public ComponentName getEffectsSuppressor() {
            return !mEffectsSuppressors.isEmpty() ? mEffectsSuppressors.get(0) : null;
        }

        @Override
        public boolean matchesCallFilter(Bundle extras) {
            enforceSystemOrSystemUI("INotificationManager.matchesCallFilter");
            return mZenModeHelper.matchesCallFilter(
                    Binder.getCallingUserHandle(),
                    extras,
                    mRankingHelper.findExtractor(ValidateNotificationPeople.class),
                    MATCHES_CALL_FILTER_CONTACTS_TIMEOUT_MS,
                    MATCHES_CALL_FILTER_TIMEOUT_AFFINITY);
        }

        @Override
        public boolean isSystemConditionProviderEnabled(String path) {
            enforceSystemOrSystemUI("INotificationManager.isSystemConditionProviderEnabled");
            return mConditionProviders.isSystemProviderEnabled(path);
        }

        // Backup/restore interface
        @Override
        public byte[] getBackupPayload(int user) {
            if (DBG) Slog.d(TAG, "getBackupPayload u=" + user);
            //TODO: http://b/22388012
            if (user != UserHandle.USER_SYSTEM) {
                Slog.w(TAG, "getBackupPayload: cannot backup policy for user " + user);
                return null;
            }
            synchronized(mPolicyFile) {
                final ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try {
                    writePolicyXml(baos, true /*forBackup*/);
                    return baos.toByteArray();
                } catch (IOException e) {
                    Slog.w(TAG, "getBackupPayload: error writing payload for user " + user, e);
                }
            }
            return null;
        }

        @Override
        public void applyRestore(byte[] payload, int user) {
            if (DBG) Slog.d(TAG, "applyRestore u=" + user + " payload="
                    + (payload != null ? new String(payload, StandardCharsets.UTF_8) : null));
            if (payload == null) {
                Slog.w(TAG, "applyRestore: no payload to restore for user " + user);
                return;
            }
            //TODO: http://b/22388012
            if (user != UserHandle.USER_SYSTEM) {
                Slog.w(TAG, "applyRestore: cannot restore policy for user " + user);
                return;
            }
            synchronized(mPolicyFile) {
                final ByteArrayInputStream bais = new ByteArrayInputStream(payload);
                try {
                    readPolicyXml(bais, true /*forRestore*/);
                    savePolicyFile();
                } catch (NumberFormatException | XmlPullParserException | IOException e) {
                    Slog.w(TAG, "applyRestore: error reading payload", e);
                }
            }
        }

        @Override
        public boolean isNotificationPolicyAccessGranted(String pkg) {
            return checkPolicyAccess(pkg);
        }

        @Override
        public boolean isNotificationPolicyAccessGrantedForPackage(String pkg) {;
            enforceSystemOrSystemUIOrSamePackage(pkg,
                    "request policy access status for another package");
            return checkPolicyAccess(pkg);
        }

        @Override
        public String[] getPackagesRequestingNotificationPolicyAccess()
                throws RemoteException {
            enforceSystemOrSystemUI("request policy access packages");
            final long identity = Binder.clearCallingIdentity();
            try {
                return mPolicyAccess.getRequestingPackages();
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void setNotificationPolicyAccessGranted(String pkg, boolean granted)
                throws RemoteException {
            enforceSystemOrSystemUI("grant notification policy access");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationLock) {
                    mPolicyAccess.put(pkg, granted);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public Policy getNotificationPolicy(String pkg) {
            enforcePolicyAccess(pkg, "getNotificationPolicy");
            final long identity = Binder.clearCallingIdentity();
            try {
                return mZenModeHelper.getNotificationPolicy();
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void setNotificationPolicy(String pkg, Policy policy) {
            enforcePolicyAccess(pkg, "setNotificationPolicy");
            final long identity = Binder.clearCallingIdentity();
            try {
                mZenModeHelper.setNotificationPolicy(policy);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void applyEnqueuedAdjustmentFromAssistant(INotificationListener token,
                Adjustment adjustment) throws RemoteException {
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationLock) {
                    mNotificationAssistants.checkServiceTokenLocked(token);
                    int N = mEnqueuedNotifications.size();
                    for (int i = 0; i < N; i++) {
                        final NotificationRecord n = mEnqueuedNotifications.get(i);
                        if (Objects.equals(adjustment.getKey(), n.getKey())
                                && Objects.equals(adjustment.getUser(), n.getUserId())) {
                            applyAdjustment(n, adjustment);
                            break;
                        }
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void applyAdjustmentFromAssistant(INotificationListener token,
                Adjustment adjustment) throws RemoteException {
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationLock) {
                    mNotificationAssistants.checkServiceTokenLocked(token);
                    NotificationRecord n = mNotificationsByKey.get(adjustment.getKey());
                    applyAdjustment(n, adjustment);
                }
                mRankingHandler.requestSort(true);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void applyAdjustmentsFromAssistant(INotificationListener token,
                List<Adjustment> adjustments) throws RemoteException {

            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mNotificationLock) {
                    mNotificationAssistants.checkServiceTokenLocked(token);
                    for (Adjustment adjustment : adjustments) {
                        NotificationRecord n = mNotificationsByKey.get(adjustment.getKey());
                        applyAdjustment(n, adjustment);
                    }
                }
                mRankingHandler.requestSort(true);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void updateNotificationChannelFromPrivilegedListener(INotificationListener token,
                String pkg, UserHandle user, NotificationChannel channel) throws RemoteException {
            Preconditions.checkNotNull(channel);
            Preconditions.checkNotNull(pkg);
            Preconditions.checkNotNull(user);

            verifyPrivilegedListener(token, user);
            updateNotificationChannelInt(pkg, getUidForPackageAndUser(pkg, user), channel, true);
        }

        @Override
        public ParceledListSlice<NotificationChannel> getNotificationChannelsFromPrivilegedListener(
                INotificationListener token, String pkg, UserHandle user) throws RemoteException {
            Preconditions.checkNotNull(pkg);
            Preconditions.checkNotNull(user);
            verifyPrivilegedListener(token, user);

            return mRankingHelper.getNotificationChannels(pkg, getUidForPackageAndUser(pkg, user),
                    false /* includeDeleted */);
        }

        @Override
        public ParceledListSlice<NotificationChannelGroup>
                getNotificationChannelGroupsFromPrivilegedListener(
                INotificationListener token, String pkg, UserHandle user) throws RemoteException {
            Preconditions.checkNotNull(pkg);
            Preconditions.checkNotNull(user);
            verifyPrivilegedListener(token, user);

            List<NotificationChannelGroup> groups = new ArrayList<>();
            groups.addAll(mRankingHelper.getNotificationChannelGroups(
                    pkg, getUidForPackageAndUser(pkg, user)));
            return new ParceledListSlice<>(groups);
        }

        private void verifyPrivilegedListener(INotificationListener token, UserHandle user) {
            ManagedServiceInfo info;
            synchronized (mNotificationLock) {
                info = mListeners.checkServiceTokenLocked(token);
            }
            if (!hasCompanionDevice(info)) {
                throw new SecurityException(info + " does not have access");
            }
            if (!info.enabledAndUserMatches(user.getIdentifier())) {
                throw new SecurityException(info + " does not have access");
            }
        }

        private int getUidForPackageAndUser(String pkg, UserHandle user) throws RemoteException {
            int uid = 0;
            long identity = Binder.clearCallingIdentity();
            try {
                uid = mPackageManager.getPackageUid(pkg, 0, user.getIdentifier());
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            return uid;
        }
    };

    private void applyAdjustment(NotificationRecord n, Adjustment adjustment) {
        if (n == null) {
            return;
        }
        if (adjustment.getSignals() != null) {
            Bundle.setDefusable(adjustment.getSignals(), true);
            final ArrayList<String> people =
                    adjustment.getSignals().getStringArrayList(Adjustment.KEY_PEOPLE);
            final ArrayList<SnoozeCriterion> snoozeCriterionList =
                    adjustment.getSignals().getParcelableArrayList(Adjustment.KEY_SNOOZE_CRITERIA);
            n.setPeopleOverride(people);
            n.setSnoozeCriteria(snoozeCriterionList);
        }
    }

    @GuardedBy("mNotificationLock")
    private void addAutogroupKeyLocked(String key) {
        NotificationRecord n = mNotificationsByKey.get(key);
        if (n == null) {
            return;
        }
        n.setOverrideGroupKey(GroupHelper.AUTOGROUP_KEY);
        EventLogTags.writeNotificationAutogrouped(key);
    }

    @GuardedBy("mNotificationLock")
    private void removeAutogroupKeyLocked(String key) {
        NotificationRecord n = mNotificationsByKey.get(key);
        if (n == null) {
            return;
        }
        n.setOverrideGroupKey(null);
        EventLogTags.writeNotificationUnautogrouped(key);
    }

    // Clears the 'fake' auto-group summary.
    @GuardedBy("mNotificationLock")
    private void clearAutogroupSummaryLocked(int userId, String pkg) {
        ArrayMap<String, String> summaries = mAutobundledSummaries.get(userId);
        if (summaries != null && summaries.containsKey(pkg)) {
            // Clear summary.
            final NotificationRecord removed = findNotificationByKeyLocked(summaries.remove(pkg));
            if (removed != null) {
                boolean wasPosted = removeFromNotificationListsLocked(removed);
                cancelNotificationLocked(removed, false, REASON_UNAUTOBUNDLED, wasPosted);
            }
        }
    }

    // Posts a 'fake' summary for a package that has exceeded the solo-notification limit.
    private void createAutoGroupSummary(int userId, String pkg, String triggeringKey) {
        NotificationRecord summaryRecord = null;
        synchronized (mNotificationLock) {
            NotificationRecord notificationRecord = mNotificationsByKey.get(triggeringKey);
            if (notificationRecord == null) {
                // The notification could have been cancelled again already. A successive
                // adjustment will post a summary if needed.
                return;
            }
            final StatusBarNotification adjustedSbn = notificationRecord.sbn;
            userId = adjustedSbn.getUser().getIdentifier();
            ArrayMap<String, String> summaries = mAutobundledSummaries.get(userId);
            if (summaries == null) {
                summaries = new ArrayMap<>();
            }
            mAutobundledSummaries.put(userId, summaries);
            if (!summaries.containsKey(pkg)) {
                // Add summary
                final ApplicationInfo appInfo =
                       adjustedSbn.getNotification().extras.getParcelable(
                               Notification.EXTRA_BUILDER_APPLICATION_INFO);
                final Bundle extras = new Bundle();
                extras.putParcelable(Notification.EXTRA_BUILDER_APPLICATION_INFO, appInfo);
                final String channelId = notificationRecord.getChannel().getId();
                final Notification summaryNotification =
                        new Notification.Builder(getContext(), channelId)
                                .setSmallIcon(adjustedSbn.getNotification().getSmallIcon())
                                .setGroupSummary(true)
                                .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN)
                                .setGroup(GroupHelper.AUTOGROUP_KEY)
                                .setFlag(Notification.FLAG_AUTOGROUP_SUMMARY, true)
                                .setFlag(Notification.FLAG_GROUP_SUMMARY, true)
                                .setColor(adjustedSbn.getNotification().color)
                                .setLocalOnly(true)
                                .build();
                summaryNotification.extras.putAll(extras);
                Intent appIntent = getContext().getPackageManager().getLaunchIntentForPackage(pkg);
                if (appIntent != null) {
                    summaryNotification.contentIntent = PendingIntent.getActivityAsUser(
                            getContext(), 0, appIntent, 0, null, UserHandle.of(userId));
                }
                final StatusBarNotification summarySbn =
                        new StatusBarNotification(adjustedSbn.getPackageName(),
                                adjustedSbn.getOpPkg(),
                                Integer.MAX_VALUE,
                                GroupHelper.AUTOGROUP_KEY, adjustedSbn.getUid(),
                                adjustedSbn.getInitialPid(), summaryNotification,
                                adjustedSbn.getUser(), GroupHelper.AUTOGROUP_KEY,
                                System.currentTimeMillis());
                summaryRecord = new NotificationRecord(getContext(), summarySbn,
                        notificationRecord.getChannel());
                summaries.put(pkg, summarySbn.getKey());
            }
        }
        if (summaryRecord != null && checkDisqualifyingFeatures(userId, MY_UID,
                summaryRecord.sbn.getId(), summaryRecord.sbn.getTag(), summaryRecord)) {
            mHandler.post(new EnqueueNotificationRunnable(userId, summaryRecord));
        }
    }

    private String disableNotificationEffects(NotificationRecord record) {
        if (mDisableNotificationEffects) {
            return "booleanState";
        }
        if ((mListenerHints & HINT_HOST_DISABLE_EFFECTS) != 0) {
            return "listenerHints";
        }
        if (mCallState != TelephonyManager.CALL_STATE_IDLE && !mZenModeHelper.isCall(record)) {
            return "callState";
        }
        return null;
    };

    private void dumpJson(PrintWriter pw, DumpFilter filter) {
        JSONObject dump = new JSONObject();
        try {
            dump.put("service", "Notification Manager");
            dump.put("bans", mRankingHelper.dumpBansJson(filter));
            dump.put("ranking", mRankingHelper.dumpJson(filter));
            dump.put("stats", mUsageStats.dumpJson(filter));
            dump.put("channels", mRankingHelper.dumpChannelsJson(filter));
        } catch (JSONException e) {
            e.printStackTrace();
        }
        pw.println(dump);
    }

    private void dumpProto(FileDescriptor fd, DumpFilter filter) {
        final ProtoOutputStream proto = new ProtoOutputStream(fd);
        synchronized (mNotificationLock) {
            long records = proto.start(NotificationServiceDumpProto.RECORDS);
            int N = mNotificationList.size();
            if (N > 0) {
                for (int i = 0; i < N; i++) {
                    final NotificationRecord nr = mNotificationList.get(i);
                    if (filter.filtered && !filter.matches(nr.sbn)) continue;
                    nr.dump(proto, filter.redact);
                    proto.write(NotificationRecordProto.STATE, NotificationServiceProto.POSTED);
                }
            }
            N = mEnqueuedNotifications.size();
            if (N > 0) {
                for (int i = 0; i < N; i++) {
                    final NotificationRecord nr = mEnqueuedNotifications.get(i);
                    if (filter.filtered && !filter.matches(nr.sbn)) continue;
                    nr.dump(proto, filter.redact);
                    proto.write(NotificationRecordProto.STATE, NotificationServiceProto.ENQUEUED);
                }
            }
            List<NotificationRecord> snoozed = mSnoozeHelper.getSnoozed();
            N = snoozed.size();
            if (N > 0) {
                for (int i = 0; i < N; i++) {
                    final NotificationRecord nr = snoozed.get(i);
                    if (filter.filtered && !filter.matches(nr.sbn)) continue;
                    nr.dump(proto, filter.redact);
                    proto.write(NotificationRecordProto.STATE, NotificationServiceProto.SNOOZED);
                }
            }
            proto.end(records);
        }

        long zenLog = proto.start(NotificationServiceDumpProto.ZEN);
        mZenModeHelper.dump(proto);
        for (ComponentName suppressor : mEffectsSuppressors) {
            proto.write(ZenModeProto.SUPPRESSORS, suppressor.toString());
        }
        proto.end(zenLog);

        proto.flush();
    }

    void dumpImpl(PrintWriter pw, DumpFilter filter) {
        pw.print("Current Notification Manager state");
        if (filter.filtered) {
            pw.print(" (filtered to "); pw.print(filter); pw.print(")");
        }
        pw.println(':');
        int N;
        final boolean zenOnly = filter.filtered && filter.zen;

        if (!zenOnly) {
            synchronized (mToastQueue) {
                N = mToastQueue.size();
                if (N > 0) {
                    pw.println("  Toast Queue:");
                    for (int i=0; i<N; i++) {
                        mToastQueue.get(i).dump(pw, "    ", filter);
                    }
                    pw.println("  ");
                }
            }
        }

        synchronized (mNotificationLock) {
            if (!zenOnly) {
                N = mNotificationList.size();
                if (N > 0) {
                    pw.println("  Notification List:");
                    for (int i=0; i<N; i++) {
                        final NotificationRecord nr = mNotificationList.get(i);
                        if (filter.filtered && !filter.matches(nr.sbn)) continue;
                        nr.dump(pw, "    ", getContext(), filter.redact);
                    }
                    pw.println("  ");
                }

                if (!filter.filtered) {
                    N = mLights.size();
                    if (N > 0) {
                        pw.println("  Lights List:");
                        for (int i=0; i<N; i++) {
                            if (i == N - 1) {
                                pw.print("  > ");
                            } else {
                                pw.print("    ");
                            }
                            pw.println(mLights.get(i));
                        }
                        pw.println("  ");
                    }
                    pw.println("  mUseAttentionLight=" + mUseAttentionLight);
                    pw.println("  mNotificationPulseEnabled=" + mNotificationPulseEnabled);
                    pw.println("  mSoundNotificationKey=" + mSoundNotificationKey);
                    pw.println("  mVibrateNotificationKey=" + mVibrateNotificationKey);
                    pw.println("  mDisableNotificationEffects=" + mDisableNotificationEffects);
                    pw.println("  mCallState=" + callStateToString(mCallState));
                    pw.println("  mSystemReady=" + mSystemReady);
                    pw.println("  mMaxPackageEnqueueRate=" + mMaxPackageEnqueueRate);
                }
                pw.println("  mArchive=" + mArchive.toString());
                Iterator<StatusBarNotification> iter = mArchive.descendingIterator();
                int j=0;
                while (iter.hasNext()) {
                    final StatusBarNotification sbn = iter.next();
                    if (filter != null && !filter.matches(sbn)) continue;
                    pw.println("    " + sbn);
                    if (++j >= 5) {
                        if (iter.hasNext()) pw.println("    ...");
                        break;
                    }
                }

                if (!zenOnly) {
                    N = mEnqueuedNotifications.size();
                    if (N > 0) {
                        pw.println("  Enqueued Notification List:");
                        for (int i = 0; i < N; i++) {
                            final NotificationRecord nr = mEnqueuedNotifications.get(i);
                            if (filter.filtered && !filter.matches(nr.sbn)) continue;
                            nr.dump(pw, "    ", getContext(), filter.redact);
                        }
                        pw.println("  ");
                    }

                    mSnoozeHelper.dump(pw, filter);
                }
            }

            if (!zenOnly) {
                pw.println("\n  Ranking Config:");
                mRankingHelper.dump(pw, "    ", filter);

                pw.println("\n  Notification listeners:");
                mListeners.dump(pw, filter);
                pw.print("    mListenerHints: "); pw.println(mListenerHints);
                pw.print("    mListenersDisablingEffects: (");
                N = mListenersDisablingEffects.size();
                for (int i = 0; i < N; i++) {
                    final int hint = mListenersDisablingEffects.keyAt(i);
                    if (i > 0) pw.print(';');
                    pw.print("hint[" + hint + "]:");

                    final ArraySet<ManagedServiceInfo> listeners =
                            mListenersDisablingEffects.valueAt(i);
                    final int listenerSize = listeners.size();

                    for (int j = 0; j < listenerSize; j++) {
                        if (i > 0) pw.print(',');
                        final ManagedServiceInfo listener = listeners.valueAt(i);
                        pw.print(listener.component);
                    }
                }
                pw.println(')');
                pw.println("\n  Notification assistant services:");
                mNotificationAssistants.dump(pw, filter);
            }

            if (!filter.filtered || zenOnly) {
                pw.println("\n  Zen Mode:");
                pw.print("    mInterruptionFilter="); pw.println(mInterruptionFilter);
                mZenModeHelper.dump(pw, "    ");

                pw.println("\n  Zen Log:");
                ZenLog.dump(pw, "    ");
            }

            pw.println("\n  Policy access:");
            pw.print("    mPolicyAccess: "); pw.println(mPolicyAccess);

            pw.println("\n  Condition providers:");
            mConditionProviders.dump(pw, filter);

            pw.println("\n  Group summaries:");
            for (Entry<String, NotificationRecord> entry : mSummaryByGroupKey.entrySet()) {
                NotificationRecord r = entry.getValue();
                pw.println("    " + entry.getKey() + " -> " + r.getKey());
                if (mNotificationsByKey.get(r.getKey()) != r) {
                    pw.println("!!!!!!LEAK: Record not found in mNotificationsByKey.");
                    r.dump(pw, "      ", getContext(), filter.redact);
                }
            }

            if (!zenOnly) {
                pw.println("\n  Usage Stats:");
                mUsageStats.dump(pw, "    ", filter);
            }
        }
    }

    /**
     * The private API only accessible to the system process.
     */
    private final NotificationManagerInternal mInternalService = new NotificationManagerInternal() {
        @Override
        public void enqueueNotification(String pkg, String opPkg, int callingUid, int callingPid,
                String tag, int id, Notification notification, int userId) {
            enqueueNotificationInternal(pkg, opPkg, callingUid, callingPid, tag, id, notification,
                    userId);
        }

        @Override
        public void removeForegroundServiceFlagFromNotification(String pkg, int notificationId,
                int userId) {
            checkCallerIsSystem();
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    synchronized (mNotificationLock) {
                        removeForegroundServiceFlagByListLocked(
                                mEnqueuedNotifications, pkg, notificationId, userId);
                        removeForegroundServiceFlagByListLocked(
                                mNotificationList, pkg, notificationId, userId);
                    }
                }
            });
        }

        @GuardedBy("mNotificationLock")
        private void removeForegroundServiceFlagByListLocked(
                ArrayList<NotificationRecord> notificationList, String pkg, int notificationId,
                int userId) {
            NotificationRecord r = findNotificationByListLocked(
                    notificationList, pkg, null, notificationId, userId);
            if (r == null) {
                return;
            }
            StatusBarNotification sbn = r.sbn;
            // NoMan adds flags FLAG_NO_CLEAR and FLAG_ONGOING_EVENT when it sees
            // FLAG_FOREGROUND_SERVICE. Hence it's not enough to remove
            // FLAG_FOREGROUND_SERVICE, we have to revert to the flags we received
            // initially *and* force remove FLAG_FOREGROUND_SERVICE.
            sbn.getNotification().flags =
                    (r.mOriginalFlags & ~Notification.FLAG_FOREGROUND_SERVICE);
            mRankingHelper.sort(mNotificationList);
            mListeners.notifyPostedLocked(sbn, sbn /* oldSbn */);
            mGroupHelper.onNotificationPosted(sbn);
        }
    };

    void enqueueNotificationInternal(final String pkg, final String opPkg, final int callingUid,
            final int callingPid, final String tag, final int id, final Notification notification,
            int incomingUserId) {
        if (DBG) {
            Slog.v(TAG, "enqueueNotificationInternal: pkg=" + pkg + " id=" + id
                    + " notification=" + notification);
        }
        checkCallerIsSystemOrSameApp(pkg);

        final int userId = ActivityManager.handleIncomingUser(callingPid,
                callingUid, incomingUserId, true, false, "enqueueNotification", pkg);
        final UserHandle user = new UserHandle(userId);

        if (pkg == null || notification == null) {
            throw new IllegalArgumentException("null not allowed: pkg=" + pkg
                    + " id=" + id + " notification=" + notification);
        }

        // The system can post notifications for any package, let us resolve that.
        final int notificationUid = resolveNotificationUid(opPkg, callingUid, userId);

        // Fix the notification as best we can.
        try {
            final ApplicationInfo ai = mPackageManagerClient.getApplicationInfoAsUser(
                    pkg, PackageManager.MATCH_DEBUG_TRIAGED_MISSING,
                    (userId == UserHandle.USER_ALL) ? UserHandle.USER_SYSTEM : userId);
            Notification.addFieldsFromContext(ai, notification);
        } catch (NameNotFoundException e) {
            Slog.e(TAG, "Cannot create a context for sending app", e);
            return;
        }

        mUsageStats.registerEnqueuedByApp(pkg);

        // setup local book-keeping
        String channelId = notification.getChannelId();
        if (mIsTelevision && (new Notification.TvExtender(notification)).getChannelId() != null) {
            channelId = (new Notification.TvExtender(notification)).getChannelId();
        }
        final NotificationChannel channel = mRankingHelper.getNotificationChannel(pkg,
                notificationUid, channelId, false /* includeDeleted */);
        if (channel == null) {
            final String noChannelStr = "No Channel found for "
                    + "pkg=" + pkg
                    + ", channelId=" + channelId
                    + ", id=" + id
                    + ", tag=" + tag
                    + ", opPkg=" + opPkg
                    + ", callingUid=" + callingUid
                    + ", userId=" + userId
                    + ", incomingUserId=" + incomingUserId
                    + ", notificationUid=" + notificationUid
                    + ", notification=" + notification;
            Log.e(TAG, noChannelStr);
            doChannelWarningToast("Developer warning for package \"" + pkg + "\"\n" +
                    "Failed to post notification on channel \"" + channelId + "\"\n" +
                    "See log for more details");
            return;
        }

        final StatusBarNotification n = new StatusBarNotification(
                pkg, opPkg, id, tag, notificationUid, callingPid, notification,
                user, null, System.currentTimeMillis());
        final NotificationRecord r = new NotificationRecord(getContext(), n, channel);

        if (!checkDisqualifyingFeatures(userId, notificationUid, id, tag, r)) {
            return;
        }

        // Whitelist pending intents.
        if (notification.allPendingIntents != null) {
            final int intentCount = notification.allPendingIntents.size();
            if (intentCount > 0) {
                final ActivityManagerInternal am = LocalServices
                        .getService(ActivityManagerInternal.class);
                final long duration = LocalServices.getService(
                        DeviceIdleController.LocalService.class).getNotificationWhitelistDuration();
                for (int i = 0; i < intentCount; i++) {
                    PendingIntent pendingIntent = notification.allPendingIntents.valueAt(i);
                    if (pendingIntent != null) {
                        am.setPendingIntentWhitelistDuration(pendingIntent.getTarget(),
                                WHITELIST_TOKEN, duration);
                    }
                }
            }
        }

        mHandler.post(new EnqueueNotificationRunnable(userId, r));
    }

    private void doChannelWarningToast(CharSequence toastText) {
        final int defaultWarningEnabled = Build.IS_DEBUGGABLE ? 1 : 0;
        final boolean warningEnabled = Settings.Global.getInt(getContext().getContentResolver(),
                Settings.Global.SHOW_NOTIFICATION_CHANNEL_WARNINGS, defaultWarningEnabled) != 0;
        if (warningEnabled) {
            Toast toast = Toast.makeText(getContext(), mHandler.getLooper(), toastText,
                    Toast.LENGTH_SHORT);
            toast.show();
        }
    }

    private int resolveNotificationUid(String opPackageName, int callingUid, int userId) {
        // The system can post notifications on behalf of any package it wants
        if (isCallerSystemOrPhone() && opPackageName != null && !"android".equals(opPackageName)) {
            try {
                return getContext().getPackageManager()
                        .getPackageUidAsUser(opPackageName, userId);
            } catch (NameNotFoundException e) {
                /* ignore */
            }
        }
        return callingUid;
    }

    /**
     * Checks if a notification can be posted. checks rate limiter, snooze helper, and blocking.
     *
     * Has side effects.
     */
    private boolean checkDisqualifyingFeatures(int userId, int callingUid, int id, String tag,
            NotificationRecord r) {
        final String pkg = r.sbn.getPackageName();
        final boolean isSystemNotification =
                isUidSystemOrPhone(callingUid) || ("android".equals(pkg));
        final boolean isNotificationFromListener = mListeners.isListenerPackage(pkg);

        // Limit the number of notifications that any given package except the android
        // package or a registered listener can enqueue.  Prevents DOS attacks and deals with leaks.
        if (!isSystemNotification && !isNotificationFromListener) {
            synchronized (mNotificationLock) {
                if (mNotificationsByKey.get(r.sbn.getKey()) != null) {
                    // this is an update, rate limit updates only
                    final float appEnqueueRate = mUsageStats.getAppEnqueueRate(pkg);
                    if (appEnqueueRate > mMaxPackageEnqueueRate) {
                        mUsageStats.registerOverRateQuota(pkg);
                        final long now = SystemClock.elapsedRealtime();
                        if ((now - mLastOverRateLogTime) > MIN_PACKAGE_OVERRATE_LOG_INTERVAL) {
                            Slog.e(TAG, "Package enqueue rate is " + appEnqueueRate
                                    + ". Shedding events. package=" + pkg);
                            mLastOverRateLogTime = now;
                        }
                        return false;
                    }
                } else if (isCallerInstantApp(pkg)) {
                    // Ephemeral apps have some special constraints for notifications.
                    // They are not allowed to create new notifications however they are allowed to
                    // update notifications created by the system (e.g. a foreground service
                    // notification).
                    throw new SecurityException("Instant app " + pkg
                            + " cannot create notifications");
                }

                int count = 0;
                final int N = mNotificationList.size();
                for (int i=0; i<N; i++) {
                    final NotificationRecord existing = mNotificationList.get(i);
                    if (existing.sbn.getPackageName().equals(pkg)
                            && existing.sbn.getUserId() == userId) {
                        if (existing.sbn.getId() == id
                                && TextUtils.equals(existing.sbn.getTag(), tag)) {
                            break;  // Allow updating existing notification
                        }
                        count++;
                        if (count >= MAX_PACKAGE_NOTIFICATIONS) {
                            mUsageStats.registerOverCountQuota(pkg);
                            Slog.e(TAG, "Package has already posted " + count
                                    + " notifications.  Not showing more.  package=" + pkg);
                            return false;
                        }
                    }
                }
            }
        }

        // snoozed apps
        if (mSnoozeHelper.isSnoozed(userId, pkg, r.getKey())) {
            MetricsLogger.action(r.getLogMaker()
                    .setType(MetricsProto.MetricsEvent.TYPE_UPDATE)
                    .setCategory(MetricsProto.MetricsEvent.NOTIFICATION_SNOOZED));
            if (DBG) {
                Slog.d(TAG, "Ignored enqueue for snoozed notification " + r.getKey());
            }
            mSnoozeHelper.update(userId, r);
            savePolicyFile();
            return false;
        }


        // blocked apps
        if (isBlocked(r, mUsageStats)) {
            return false;
        }

        return true;
    }

    protected boolean isBlocked(NotificationRecord r, NotificationUsageStats usageStats) {
        final String pkg = r.sbn.getPackageName();
        final int callingUid = r.sbn.getUid();

        final boolean isPackageSuspended = isPackageSuspendedForUser(pkg, callingUid);
        if (isPackageSuspended) {
            Slog.e(TAG, "Suppressing notification from package due to package "
                    + "suspended by administrator.");
            usageStats.registerSuspendedByAdmin(r);
            return isPackageSuspended;
        }

        final boolean isBlocked =
                mRankingHelper.getImportance(pkg, callingUid) == NotificationManager.IMPORTANCE_NONE
                || r.getChannel().getImportance() == NotificationManager.IMPORTANCE_NONE;
        if (isBlocked) {
            Slog.e(TAG, "Suppressing notification from package by user request.");
            usageStats.registerBlocked(r);
        }
        return isBlocked;
    }

    protected class SnoozeNotificationRunnable implements Runnable {
        private final String mKey;
        private final long mDuration;
        private final String mSnoozeCriterionId;

        SnoozeNotificationRunnable(String key, long duration, String snoozeCriterionId) {
            mKey = key;
            mDuration = duration;
            mSnoozeCriterionId = snoozeCriterionId;
        }

        @Override
        public void run() {
            synchronized (mNotificationLock) {
                final NotificationRecord r = findNotificationByKeyLocked(mKey);
                if (r != null) {
                    snoozeLocked(r);
                }
            }
        }

        @GuardedBy("mNotificationLock")
        void snoozeLocked(NotificationRecord r) {
            if (r.sbn.isGroup()) {
                final List<NotificationRecord> groupNotifications = findGroupNotificationsLocked(
                        r.sbn.getPackageName(), r.sbn.getGroupKey(), r.sbn.getUserId());
                if (r.getNotification().isGroupSummary()) {
                    // snooze summary and all children
                    for (int i = 0; i < groupNotifications.size(); i++) {
                        snoozeNotificationLocked(groupNotifications.get(i));
                    }
                } else {
                    // if there is a valid summary for this group, and we are snoozing the only
                    // child, also snooze the summary
                    if (mSummaryByGroupKey.containsKey(r.sbn.getGroupKey())) {
                        if (groupNotifications.size() != 2) {
                            snoozeNotificationLocked(r);
                        } else {
                            // snooze summary and the one child
                            for (int i = 0; i < groupNotifications.size(); i++) {
                                snoozeNotificationLocked(groupNotifications.get(i));
                            }
                        }
                    } else {
                        snoozeNotificationLocked(r);
                    }
                }
            } else {
                // just snooze the one notification
                snoozeNotificationLocked(r);
            }
        }

        @GuardedBy("mNotificationLock")
        void snoozeNotificationLocked(NotificationRecord r) {
            MetricsLogger.action(r.getLogMaker()
                    .setCategory(MetricsEvent.NOTIFICATION_SNOOZED)
                    .setType(MetricsEvent.TYPE_CLOSE)
                    .addTaggedData(MetricsEvent.NOTIFICATION_SNOOZED_CRITERIA,
                            mSnoozeCriterionId == null ? 0 : 1));
            boolean wasPosted = removeFromNotificationListsLocked(r);
            cancelNotificationLocked(r, false, REASON_SNOOZED, wasPosted);
            updateLightsLocked();
            if (mSnoozeCriterionId != null) {
                mNotificationAssistants.notifyAssistantSnoozedLocked(r.sbn, mSnoozeCriterionId);
                mSnoozeHelper.snooze(r);
            } else {
                mSnoozeHelper.snooze(r, mDuration);
            }
            savePolicyFile();
        }
    }

    protected class EnqueueNotificationRunnable implements Runnable {
        private final NotificationRecord r;
        private final int userId;

        EnqueueNotificationRunnable(int userId, NotificationRecord r) {
            this.userId = userId;
            this.r = r;
        };

        @Override
        public void run() {
            synchronized (mNotificationLock) {
                mEnqueuedNotifications.add(r);
                scheduleTimeoutLocked(r);

                final StatusBarNotification n = r.sbn;
                if (DBG) Slog.d(TAG, "EnqueueNotificationRunnable.run for: " + n.getKey());
                NotificationRecord old = mNotificationsByKey.get(n.getKey());
                if (old != null) {
                    // Retain ranking information from previous record
                    r.copyRankingInformation(old);
                }

                final int callingUid = n.getUid();
                final int callingPid = n.getInitialPid();
                final Notification notification = n.getNotification();
                final String pkg = n.getPackageName();
                final int id = n.getId();
                final String tag = n.getTag();

                // Handle grouped notifications and bail out early if we
                // can to avoid extracting signals.
                handleGroupedNotificationLocked(r, old, callingUid, callingPid);

                // if this is a group child, unsnooze parent summary
                if (n.isGroup() && notification.isGroupChild()) {
                    mSnoozeHelper.repostGroupSummary(pkg, r.getUserId(), n.getGroupKey());
                }

                // This conditional is a dirty hack to limit the logging done on
                //     behalf of the download manager without affecting other apps.
                if (!pkg.equals("com.android.providers.downloads")
                        || Log.isLoggable("DownloadManager", Log.VERBOSE)) {
                    int enqueueStatus = EVENTLOG_ENQUEUE_STATUS_NEW;
                    if (old != null) {
                        enqueueStatus = EVENTLOG_ENQUEUE_STATUS_UPDATE;
                    }
                    EventLogTags.writeNotificationEnqueue(callingUid, callingPid,
                            pkg, id, tag, userId, notification.toString(),
                            enqueueStatus);
                }

                mRankingHelper.extractSignals(r);

                // tell the assistant service about the notification
                if (mNotificationAssistants.isEnabled()) {
                    mNotificationAssistants.onNotificationEnqueued(r);
                    mHandler.postDelayed(new PostNotificationRunnable(r.getKey()),
                            DELAY_FOR_ASSISTANT_TIME);
                } else {
                    mHandler.post(new PostNotificationRunnable(r.getKey()));
                }
            }
        }
    }

    protected class PostNotificationRunnable implements Runnable {
        private final String key;

        PostNotificationRunnable(String key) {
            this.key = key;
        }

        @Override
        public void run() {
            synchronized (mNotificationLock) {
                try {
                    NotificationRecord r = null;
                    int N = mEnqueuedNotifications.size();
                    for (int i = 0; i < N; i++) {
                        final NotificationRecord enqueued = mEnqueuedNotifications.get(i);
                        if (Objects.equals(key, enqueued.getKey())) {
                            r = enqueued;
                            break;
                        }
                    }
                    if (r == null) {
                        Slog.i(TAG, "Cannot find enqueued record for key: " + key);
                        return;
                    }
                    NotificationRecord old = mNotificationsByKey.get(key);
                    final StatusBarNotification n = r.sbn;
                    final Notification notification = n.getNotification();
                    int index = indexOfNotificationLocked(n.getKey());
                    if (index < 0) {
                        mNotificationList.add(r);
                        mUsageStats.registerPostedByApp(r);
                    } else {
                        old = mNotificationList.get(index);
                        mNotificationList.set(index, r);
                        mUsageStats.registerUpdatedByApp(r, old);
                        // Make sure we don't lose the foreground service state.
                        notification.flags |=
                                old.getNotification().flags & Notification.FLAG_FOREGROUND_SERVICE;
                        r.isUpdate = true;
                    }

                    mNotificationsByKey.put(n.getKey(), r);

                    // Ensure if this is a foreground service that the proper additional
                    // flags are set.
                    if ((notification.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) {
                        notification.flags |= Notification.FLAG_ONGOING_EVENT
                                | Notification.FLAG_NO_CLEAR;
                    }

                    applyZenModeLocked(r);
                    mRankingHelper.sort(mNotificationList);

                    if (notification.getSmallIcon() != null) {
                        StatusBarNotification oldSbn = (old != null) ? old.sbn : null;
                        mListeners.notifyPostedLocked(n, oldSbn);
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                mGroupHelper.onNotificationPosted(n);
                            }
                        });
                    } else {
                        Slog.e(TAG, "Not posting notification without small icon: " + notification);
                        if (old != null && !old.isCanceled) {
                            mListeners.notifyRemovedLocked(n,
                                    NotificationListenerService.REASON_ERROR);
                            mHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    mGroupHelper.onNotificationRemoved(n);
                                }
                            });
                        }
                        // ATTENTION: in a future release we will bail out here
                        // so that we do not play sounds, show lights, etc. for invalid
                        // notifications
                        Slog.e(TAG, "WARNING: In a future release this will crash the app: "
                                + n.getPackageName());
                    }

                    buzzBeepBlinkLocked(r);
                } finally {
                    int N = mEnqueuedNotifications.size();
                    for (int i = 0; i < N; i++) {
                        final NotificationRecord enqueued = mEnqueuedNotifications.get(i);
                        if (Objects.equals(key, enqueued.getKey())) {
                            mEnqueuedNotifications.remove(i);
                            break;
                        }
                    }
                }
            }
        }
    }

    /**
     * Ensures that grouped notification receive their special treatment.
     *
     * <p>Cancels group children if the new notification causes a group to lose
     * its summary.</p>
     *
     * <p>Updates mSummaryByGroupKey.</p>
     */
    @GuardedBy("mNotificationLock")
    private void handleGroupedNotificationLocked(NotificationRecord r, NotificationRecord old,
            int callingUid, int callingPid) {
        StatusBarNotification sbn = r.sbn;
        Notification n = sbn.getNotification();
        if (n.isGroupSummary() && !sbn.isAppGroup())  {
            // notifications without a group shouldn't be a summary, otherwise autobundling can
            // lead to bugs
            n.flags &= ~Notification.FLAG_GROUP_SUMMARY;
        }

        String group = sbn.getGroupKey();
        boolean isSummary = n.isGroupSummary();

        Notification oldN = old != null ? old.sbn.getNotification() : null;
        String oldGroup = old != null ? old.sbn.getGroupKey() : null;
        boolean oldIsSummary = old != null && oldN.isGroupSummary();

        if (oldIsSummary) {
            NotificationRecord removedSummary = mSummaryByGroupKey.remove(oldGroup);
            if (removedSummary != old) {
                String removedKey =
                        removedSummary != null ? removedSummary.getKey() : "<null>";
                Slog.w(TAG, "Removed summary didn't match old notification: old=" + old.getKey() +
                        ", removed=" + removedKey);
            }
        }
        if (isSummary) {
            mSummaryByGroupKey.put(group, r);
        }

        // Clear out group children of the old notification if the update
        // causes the group summary to go away. This happens when the old
        // notification was a summary and the new one isn't, or when the old
        // notification was a summary and its group key changed.
        if (oldIsSummary && (!isSummary || !oldGroup.equals(group))) {
            cancelGroupChildrenLocked(old, callingUid, callingPid, null, false /* sendDelete */);
        }
    }

    @VisibleForTesting
    @GuardedBy("mNotificationLock")
    void scheduleTimeoutLocked(NotificationRecord record) {
        if (record.getNotification().getTimeoutAfter() > 0) {
            final PendingIntent pi = PendingIntent.getBroadcast(getContext(),
                    REQUEST_CODE_TIMEOUT,
                    new Intent(ACTION_NOTIFICATION_TIMEOUT)
                            .setData(new Uri.Builder().scheme(SCHEME_TIMEOUT)
                                    .appendPath(record.getKey()).build())
                            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                            .putExtra(EXTRA_KEY, record.getKey()),
                    PendingIntent.FLAG_UPDATE_CURRENT);
            mAlarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + record.getNotification().getTimeoutAfter(), pi);
        }
    }

    @VisibleForTesting
    @GuardedBy("mNotificationLock")
    void buzzBeepBlinkLocked(NotificationRecord record) {
        boolean buzz = false;
        boolean beep = false;
        boolean blink = false;

        final Notification notification = record.sbn.getNotification();
        final String key = record.getKey();

        // Should this notification make noise, vibe, or use the LED?
        final boolean aboveThreshold =
                record.getImportance() >= NotificationManager.IMPORTANCE_DEFAULT;
        final boolean canInterrupt = aboveThreshold && !record.isIntercepted();
        if (DBG)
            Slog.v(TAG,
                    "pkg=" + record.sbn.getPackageName() + " canInterrupt=" + canInterrupt +
                            " intercept=" + record.isIntercepted()
            );

        // If we're not supposed to beep, vibrate, etc. then don't.
        final String disableEffects = disableNotificationEffects(record);
        if (disableEffects != null) {
            ZenLog.traceDisableEffects(record, disableEffects);
        }

        // Remember if this notification already owns the notification channels.
        boolean wasBeep = key != null && key.equals(mSoundNotificationKey);
        boolean wasBuzz = key != null && key.equals(mVibrateNotificationKey);
        // These are set inside the conditional if the notification is allowed to make noise.
        boolean hasValidVibrate = false;
        boolean hasValidSound = false;

        if (isNotificationForCurrentUser(record)) {
            // If the notification will appear in the status bar, it should send an accessibility
            // event
            if (!record.isUpdate && record.getImportance() > IMPORTANCE_MIN) {
                sendAccessibilityEvent(notification, record.sbn.getPackageName());
            }

            if (disableEffects == null
                    && canInterrupt
                    && mSystemReady
                    && mAudioManager != null) {
                if (DBG) Slog.v(TAG, "Interrupting!");
                Uri soundUri = record.getSound();
                hasValidSound = soundUri != null && !Uri.EMPTY.equals(soundUri);
                long[] vibration = record.getVibration();
                // Demote sound to vibration if vibration missing & phone in vibration mode.
                if (vibration == null
                        && hasValidSound
                        && (mAudioManager.getRingerModeInternal()
                        == AudioManager.RINGER_MODE_VIBRATE)) {
                    vibration = mFallbackVibrationPattern;
                }
                hasValidVibrate = vibration != null;

                if (!shouldMuteNotificationLocked(record)) {
                    if (hasValidSound) {
                        mSoundNotificationKey = key;
                        if (mInCall) {
                            playInCallNotification();
                            beep = true;
                        } else {
                            beep = playSound(record, soundUri);
                        }
                    }

                    final boolean ringerModeSilent =
                            mAudioManager.getRingerModeInternal()
                                    == AudioManager.RINGER_MODE_SILENT;
                    if (!mInCall && hasValidVibrate && !ringerModeSilent) {
                        mVibrateNotificationKey = key;

                        buzz = playVibration(record, vibration, hasValidSound);
                    }
                }
            }
        }
        // If a notification is updated to remove the actively playing sound or vibrate,
        // cancel that feedback now
        if (wasBeep && !hasValidSound) {
            clearSoundLocked();
        }
        if (wasBuzz && !hasValidVibrate) {
            clearVibrateLocked();
        }

        // light
        // release the light
        boolean wasShowLights = mLights.remove(key);
        final boolean aboveThresholdWithLight = aboveThreshold
                || isLedNotificationForcedOn(record);
        if (record.getLight() != null && aboveThresholdWithLight
                && ((record.getSuppressedVisualEffects()
                & NotificationListenerService.SUPPRESSED_EFFECT_SCREEN_OFF) == 0)) {
            mLights.add(key);
            updateLightsLocked();
            if (mUseAttentionLight) {
                mAttentionLight.pulse();
            }
            blink = true;
        } else if (wasShowLights) {
            updateLightsLocked();
        }
        if (buzz || beep || blink) {
            MetricsLogger.action(record.getLogMaker()
                    .setCategory(MetricsEvent.NOTIFICATION_ALERT)
                    .setType(MetricsEvent.TYPE_OPEN)
                    .setSubtype((buzz ? 1 : 0) | (beep ? 2 : 0) | (blink ? 4 : 0)));
            EventLogTags.writeNotificationAlert(key, buzz ? 1 : 0, beep ? 1 : 0, blink ? 1 : 0);
        }
    }

    @GuardedBy("mNotificationLock")
    boolean shouldMuteNotificationLocked(final NotificationRecord record) {
        final Notification notification = record.getNotification();
        if(record.isUpdate
                && (notification.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0) {
            return true;
        }
        if (record.sbn.isGroup()) {
            return notification.suppressAlertingDueToGrouping();
        }
        return false;
    }

    private boolean playSound(final NotificationRecord record, Uri soundUri) {
        boolean looping = (record.getNotification().flags & Notification.FLAG_INSISTENT) != 0;
        // do not play notifications if there is a user of exclusive audio focus
        // or the device is in vibrate mode
        if (!mAudioManager.isAudioFocusExclusive() && mAudioManager.getRingerModeInternal()
                != AudioManager.RINGER_MODE_VIBRATE) {
            final long identity = Binder.clearCallingIdentity();
            try {
                final IRingtonePlayer player = mAudioManager.getRingtonePlayer();
                if (player != null) {
                    if (DBG) Slog.v(TAG, "Playing sound " + soundUri
                            + " with attributes " + record.getAudioAttributes());
                    player.playAsync(soundUri, record.sbn.getUser(), looping,
                            record.getAudioAttributes());
                    return true;
                }
            } catch (RemoteException e) {
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
        return false;
    }

    private boolean playVibration(final NotificationRecord record, long[] vibration,
            boolean delayVibForSound) {
        // Escalate privileges so we can use the vibrator even if the
        // notifying app does not have the VIBRATE permission.
        long identity = Binder.clearCallingIdentity();
        try {
            final VibrationEffect effect;
            try {
                final boolean insistent =
                        (record.getNotification().flags & Notification.FLAG_INSISTENT) != 0;
                effect = VibrationEffect.createWaveform(
                        vibration, insistent ? 0 : -1 /*repeatIndex*/);
            } catch (IllegalArgumentException e) {
                Slog.e(TAG, "Error creating vibration waveform with pattern: " +
                        Arrays.toString(vibration));
                return false;
            }
            if (delayVibForSound) {
                new Thread(() -> {
                    // delay the vibration by the same amount as the notification sound
                    final int waitMs = mAudioManager.getFocusRampTimeMs(
                            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                            record.getAudioAttributes());
                    if (DBG) Slog.v(TAG, "Delaying vibration by " + waitMs + "ms");
                    try {
                        Thread.sleep(waitMs);
                    } catch (InterruptedException e) { }
                    mVibrator.vibrate(record.sbn.getUid(), record.sbn.getOpPkg(),
                            effect, record.getAudioAttributes());
                }).start();
            } else {
                mVibrator.vibrate(record.sbn.getUid(), record.sbn.getOpPkg(),
                        effect, record.getAudioAttributes());
            }
            return true;
        } finally{
            Binder.restoreCallingIdentity(identity);
        }
    }

    private boolean isNotificationForCurrentUser(NotificationRecord record) {
        final int currentUser;
        final long token = Binder.clearCallingIdentity();
        try {
            currentUser = ActivityManager.getCurrentUser();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        return (record.getUserId() == UserHandle.USER_ALL ||
                record.getUserId() == currentUser ||
                mUserProfiles.isCurrentProfile(record.getUserId()));
    }

    private void playInCallNotification() {
        new Thread() {
            @Override
            public void run() {
                // If toneGenerator creation fails, just continue the call
                // without playing the notification sound.
                try {
                    synchronized (mInCallToneGeneratorLock) {
                        if (mInCallToneGenerator != null) {
                            // limit this tone to 1 second; BEEP2 should in fact be much shorter
                            mInCallToneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP2, 1000);
                        }
                    }
                } catch (RuntimeException e) {
                    Log.w(TAG, "Exception from ToneGenerator: " + e);
                }
            }
        }.start();
    }

    @GuardedBy("mToastQueue")
    void showNextToastLocked() {
        ToastRecord record = mToastQueue.get(0);
        while (record != null) {
            if (DBG) Slog.d(TAG, "Show pkg=" + record.pkg + " callback=" + record.callback);
            try {
                record.callback.show(record.token);
                scheduleTimeoutLocked(record);
                return;
            } catch (RemoteException e) {
                Slog.w(TAG, "Object died trying to show notification " + record.callback
                        + " in package " + record.pkg);
                // remove it from the list and let the process die
                int index = mToastQueue.indexOf(record);
                if (index >= 0) {
                    mToastQueue.remove(index);
                }
                keepProcessAliveIfNeededLocked(record.pid);
                if (mToastQueue.size() > 0) {
                    record = mToastQueue.get(0);
                } else {
                    record = null;
                }
            }
        }
    }

    @GuardedBy("mToastQueue")
    void cancelToastLocked(int index) {
        ToastRecord record = mToastQueue.get(index);
        try {
            record.callback.hide();
        } catch (RemoteException e) {
            Slog.w(TAG, "Object died trying to hide notification " + record.callback
                    + " in package " + record.pkg);
            // don't worry about this, we're about to remove it from
            // the list anyway
        }

        ToastRecord lastToast = mToastQueue.remove(index);
        mWindowManagerInternal.removeWindowToken(lastToast.token, true, DEFAULT_DISPLAY);

        keepProcessAliveIfNeededLocked(record.pid);
        if (mToastQueue.size() > 0) {
            // Show the next one. If the callback fails, this will remove
            // it from the list, so don't assume that the list hasn't changed
            // after this point.
            showNextToastLocked();
        }
    }

    @GuardedBy("mToastQueue")
    private void scheduleTimeoutLocked(ToastRecord r)
    {
        mHandler.removeCallbacksAndMessages(r);
        Message m = Message.obtain(mHandler, MESSAGE_TIMEOUT, r);
        long delay = r.duration == Toast.LENGTH_LONG ? LONG_DELAY : SHORT_DELAY;
        mHandler.sendMessageDelayed(m, delay);
    }

    private void handleTimeout(ToastRecord record)
    {
        if (DBG) Slog.d(TAG, "Timeout pkg=" + record.pkg + " callback=" + record.callback);
        synchronized (mToastQueue) {
            int index = indexOfToastLocked(record.pkg, record.callback);
            if (index >= 0) {
                cancelToastLocked(index);
            }
        }
    }

    @GuardedBy("mToastQueue")
    int indexOfToastLocked(String pkg, ITransientNotification callback)
    {
        IBinder cbak = callback.asBinder();
        ArrayList<ToastRecord> list = mToastQueue;
        int len = list.size();
        for (int i=0; i<len; i++) {
            ToastRecord r = list.get(i);
            if (r.pkg.equals(pkg) && r.callback.asBinder() == cbak) {
                return i;
            }
        }
        return -1;
    }

    @GuardedBy("mToastQueue")
    void keepProcessAliveIfNeededLocked(int pid)
    {
        int toastCount = 0; // toasts from this pid
        ArrayList<ToastRecord> list = mToastQueue;
        int N = list.size();
        for (int i=0; i<N; i++) {
            ToastRecord r = list.get(i);
            if (r.pid == pid) {
                toastCount++;
            }
        }
        try {
            mAm.setProcessImportant(mForegroundToken, pid, toastCount > 0, "toast");
        } catch (RemoteException e) {
            // Shouldn't happen.
        }
    }

    private void handleRankingReconsideration(Message message) {
        if (!(message.obj instanceof RankingReconsideration)) return;
        RankingReconsideration recon = (RankingReconsideration) message.obj;
        recon.run();
        boolean changed;
        synchronized (mNotificationLock) {
            final NotificationRecord record = mNotificationsByKey.get(recon.getKey());
            if (record == null) {
                return;
            }
            int indexBefore = findNotificationRecordIndexLocked(record);
            boolean interceptBefore = record.isIntercepted();
            int visibilityBefore = record.getPackageVisibilityOverride();
            recon.applyChangesLocked(record);
            applyZenModeLocked(record);
            mRankingHelper.sort(mNotificationList);
            int indexAfter = findNotificationRecordIndexLocked(record);
            boolean interceptAfter = record.isIntercepted();
            int visibilityAfter = record.getPackageVisibilityOverride();
            changed = indexBefore != indexAfter || interceptBefore != interceptAfter
                    || visibilityBefore != visibilityAfter;
            if (interceptBefore && !interceptAfter) {
                buzzBeepBlinkLocked(record);
            }
        }
        if (changed) {
            scheduleSendRankingUpdate();
        }
    }

    private void handleRankingSort(Message msg) {
        if (!(msg.obj instanceof Boolean)) return;
        if (mRankingHelper == null) return;
        boolean forceUpdate = ((Boolean) msg.obj == null) ? false : (boolean) msg.obj;
        synchronized (mNotificationLock) {
            final int N = mNotificationList.size();
            // Any field that can change via one of the extractors or by the assistant
            // needs to be added here.
            ArrayList<String> orderBefore = new ArrayList<String>(N);
            ArrayList<String> groupOverrideBefore = new ArrayList<>(N);
            int[] visibilities = new int[N];
            boolean[] showBadges = new boolean[N];
            for (int i = 0; i < N; i++) {
                final NotificationRecord r = mNotificationList.get(i);
                orderBefore.add(r.getKey());
                groupOverrideBefore.add(r.sbn.getGroupKey());
                visibilities[i] = r.getPackageVisibilityOverride();
                showBadges[i] = r.canShowBadge();
                mRankingHelper.extractSignals(r);
            }
            mRankingHelper.sort(mNotificationList);
            for (int i = 0; i < N; i++) {
                final NotificationRecord r = mNotificationList.get(i);
                if (forceUpdate
                        || !orderBefore.get(i).equals(r.getKey())
                        || visibilities[i] != r.getPackageVisibilityOverride()
                        || !groupOverrideBefore.get(i).equals(r.sbn.getGroupKey())
                        || showBadges[i] != r.canShowBadge()) {
                    scheduleSendRankingUpdate();
                    return;
                }
            }
        }
    }

    @GuardedBy("mNotificationLock")
    private void recordCallerLocked(NotificationRecord record) {
        if (mZenModeHelper.isCall(record)) {
            mZenModeHelper.recordCaller(record);
        }
    }

    // let zen mode evaluate this record
    @GuardedBy("mNotificationLock")
    private void applyZenModeLocked(NotificationRecord record) {
        record.setIntercepted(mZenModeHelper.shouldIntercept(record));
        if (record.isIntercepted()) {
            int suppressed = (mZenModeHelper.shouldSuppressWhenScreenOff()
                    ? SUPPRESSED_EFFECT_SCREEN_OFF : 0)
                    | (mZenModeHelper.shouldSuppressWhenScreenOn()
                    ? SUPPRESSED_EFFECT_SCREEN_ON : 0);
            record.setSuppressedVisualEffects(suppressed);
        } else {
            record.setSuppressedVisualEffects(0);
        }
    }

    @GuardedBy("mNotificationLock")
    private int findNotificationRecordIndexLocked(NotificationRecord target) {
        return mRankingHelper.indexOf(mNotificationList, target);
    }

    private void scheduleSendRankingUpdate() {
        if (!mHandler.hasMessages(MESSAGE_SEND_RANKING_UPDATE)) {
            Message m = Message.obtain(mHandler, MESSAGE_SEND_RANKING_UPDATE);
            mHandler.sendMessage(m);
        }
    }

    private void handleSendRankingUpdate() {
        synchronized (mNotificationLock) {
            mListeners.notifyRankingUpdateLocked();
        }
    }

    private void scheduleListenerHintsChanged(int state) {
        mHandler.removeMessages(MESSAGE_LISTENER_HINTS_CHANGED);
        mHandler.obtainMessage(MESSAGE_LISTENER_HINTS_CHANGED, state, 0).sendToTarget();
    }

    private void scheduleInterruptionFilterChanged(int listenerInterruptionFilter) {
        mHandler.removeMessages(MESSAGE_LISTENER_NOTIFICATION_FILTER_CHANGED);
        mHandler.obtainMessage(
                MESSAGE_LISTENER_NOTIFICATION_FILTER_CHANGED,
                listenerInterruptionFilter,
                0).sendToTarget();
    }

    private void handleListenerHintsChanged(int hints) {
        synchronized (mNotificationLock) {
            mListeners.notifyListenerHintsChangedLocked(hints);
        }
    }

    private void handleListenerInterruptionFilterChanged(int interruptionFilter) {
        synchronized (mNotificationLock) {
            mListeners.notifyInterruptionFilterChanged(interruptionFilter);
        }
    }

    private final class WorkerHandler extends Handler
    {
        public WorkerHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg)
        {
            switch (msg.what)
            {
                case MESSAGE_TIMEOUT:
                    handleTimeout((ToastRecord)msg.obj);
                    break;
                case MESSAGE_SAVE_POLICY_FILE:
                    handleSavePolicyFile();
                    break;
                case MESSAGE_SEND_RANKING_UPDATE:
                    handleSendRankingUpdate();
                    break;
                case MESSAGE_LISTENER_HINTS_CHANGED:
                    handleListenerHintsChanged(msg.arg1);
                    break;
                case MESSAGE_LISTENER_NOTIFICATION_FILTER_CHANGED:
                    handleListenerInterruptionFilterChanged(msg.arg1);
                    break;
            }
        }

    }

    private final class RankingHandlerWorker extends Handler implements RankingHandler
    {
        public RankingHandlerWorker(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_RECONSIDER_RANKING:
                    handleRankingReconsideration(msg);
                    break;
                case MESSAGE_RANKING_SORT:
                    handleRankingSort(msg);
                    break;
            }
        }

        public void requestSort(boolean forceUpdate) {
            removeMessages(MESSAGE_RANKING_SORT);
            Message msg = Message.obtain();
            msg.what = MESSAGE_RANKING_SORT;
            msg.obj = forceUpdate;
            sendMessage(msg);
        }

        public void requestReconsideration(RankingReconsideration recon) {
            Message m = Message.obtain(this,
                    NotificationManagerService.MESSAGE_RECONSIDER_RANKING, recon);
            long delay = recon.getDelay(TimeUnit.MILLISECONDS);
            sendMessageDelayed(m, delay);
        }
    }

    // Notifications
    // ============================================================================
    static int clamp(int x, int low, int high) {
        return (x < low) ? low : ((x > high) ? high : x);
    }

    void sendAccessibilityEvent(Notification notification, CharSequence packageName) {
        AccessibilityManager manager = AccessibilityManager.getInstance(getContext());
        if (!manager.isEnabled()) {
            return;
        }

        AccessibilityEvent event =
            AccessibilityEvent.obtain(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED);
        event.setPackageName(packageName);
        event.setClassName(Notification.class.getName());
        event.setParcelableData(notification);
        CharSequence tickerText = notification.tickerText;
        if (!TextUtils.isEmpty(tickerText)) {
            event.getText().add(tickerText);
        }

        manager.sendAccessibilityEvent(event);
    }

    /**
     * Removes all NotificationsRecords with the same key as the given notification record
     * from both lists. Do not call this method while iterating over either list.
     */
    @GuardedBy("mNotificationLock")
    private boolean removeFromNotificationListsLocked(NotificationRecord r) {
        // Remove from both lists, either list could have a separate Record for what is
        // effectively the same notification.
        boolean wasPosted = false;
        NotificationRecord recordInList = null;
        if ((recordInList = findNotificationByListLocked(mNotificationList, r.getKey()))
                != null) {
            mNotificationList.remove(recordInList);
            mNotificationsByKey.remove(recordInList.sbn.getKey());
            wasPosted = true;
        }
        while ((recordInList = findNotificationByListLocked(mEnqueuedNotifications, r.getKey()))
                != null) {
            mEnqueuedNotifications.remove(recordInList);
        }
        return wasPosted;
    }

    @GuardedBy("mNotificationLock")
    private void cancelNotificationLocked(NotificationRecord r, boolean sendDelete, int reason,
            boolean wasPosted) {
        final String canceledKey = r.getKey();

        // Record caller.
        recordCallerLocked(r);

        // tell the app
        if (sendDelete) {
            if (r.getNotification().deleteIntent != null) {
                try {
                    r.getNotification().deleteIntent.send();
                } catch (PendingIntent.CanceledException ex) {
                    // do nothing - there's no relevant way to recover, and
                    //     no reason to let this propagate
                    Slog.w(TAG, "canceled PendingIntent for " + r.sbn.getPackageName(), ex);
                }
            }
        }

        // Only cancel these if this notification actually got to be posted.
        if (wasPosted) {
            // status bar
            if (r.getNotification().getSmallIcon() != null) {
                if (reason != REASON_SNOOZED) {
                    r.isCanceled = true;
                }
                mListeners.notifyRemovedLocked(r.sbn, reason);
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mGroupHelper.onNotificationRemoved(r.sbn);
                    }
                });
            }

            // sound
            if (canceledKey.equals(mSoundNotificationKey)) {
                mSoundNotificationKey = null;
                final long identity = Binder.clearCallingIdentity();
                try {
                    final IRingtonePlayer player = mAudioManager.getRingtonePlayer();
                    if (player != null) {
                        player.stopAsync();
                    }
                } catch (RemoteException e) {
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }

            // vibrate
            if (canceledKey.equals(mVibrateNotificationKey)) {
                mVibrateNotificationKey = null;
                long identity = Binder.clearCallingIdentity();
                try {
                    mVibrator.cancel();
                }
                finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }

            // light
            mLights.remove(canceledKey);
        }

        // Record usage stats
        // TODO: add unbundling stats?
        switch (reason) {
            case REASON_CANCEL:
            case REASON_CANCEL_ALL:
            case REASON_LISTENER_CANCEL:
            case REASON_LISTENER_CANCEL_ALL:
                mUsageStats.registerDismissedByUser(r);
                break;
            case REASON_APP_CANCEL:
            case REASON_APP_CANCEL_ALL:
                mUsageStats.registerRemovedByApp(r);
                break;
        }

        String groupKey = r.getGroupKey();
        NotificationRecord groupSummary = mSummaryByGroupKey.get(groupKey);
        if (groupSummary != null && groupSummary.getKey().equals(canceledKey)) {
            mSummaryByGroupKey.remove(groupKey);
        }
        final ArrayMap<String, String> summaries = mAutobundledSummaries.get(r.sbn.getUserId());
        if (summaries != null && r.sbn.getKey().equals(summaries.get(r.sbn.getPackageName()))) {
            summaries.remove(r.sbn.getPackageName());
        }

        // Save it for users of getHistoricalNotifications()
        mArchive.record(r.sbn);

        final long now = System.currentTimeMillis();
        MetricsLogger.action(r.getLogMaker(now)
                .setCategory(MetricsEvent.NOTIFICATION_ITEM)
                .setType(MetricsEvent.TYPE_DISMISS)
                .setSubtype(reason));
        EventLogTags.writeNotificationCanceled(canceledKey, reason,
                r.getLifespanMs(now), r.getFreshnessMs(now), r.getExposureMs(now));
    }

    /**
     * Cancels a notification ONLY if it has all of the {@code mustHaveFlags}
     * and none of the {@code mustNotHaveFlags}.
     */
    void cancelNotification(final int callingUid, final int callingPid,
            final String pkg, final String tag, final int id,
            final int mustHaveFlags, final int mustNotHaveFlags, final boolean sendDelete,
            final int userId, final int reason, final ManagedServiceInfo listener) {
        // In enqueueNotificationInternal notifications are added by scheduling the
        // work on the worker handler. Hence, we also schedule the cancel on this
        // handler to avoid a scenario where an add notification call followed by a
        // remove notification call ends up in not removing the notification.
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                String listenerName = listener == null ? null : listener.component.toShortString();
                if (DBG) EventLogTags.writeNotificationCancel(callingUid, callingPid, pkg, id, tag,
                        userId, mustHaveFlags, mustNotHaveFlags, reason, listenerName);

                synchronized (mNotificationLock) {
                    // Look for the notification, searching both the posted and enqueued lists.
                    NotificationRecord r = findNotificationLocked(pkg, tag, id, userId);
                    if (r != null) {
                        // The notification was found, check if it should be removed.

                        // Ideally we'd do this in the caller of this method. However, that would
                        // require the caller to also find the notification.
                        if (reason == REASON_CLICK) {
                            mUsageStats.registerClickedByUser(r);
                        }

                        if ((r.getNotification().flags & mustHaveFlags) != mustHaveFlags) {
                            return;
                        }
                        if ((r.getNotification().flags & mustNotHaveFlags) != 0) {
                            return;
                        }

                        // Cancel the notification.
                        boolean wasPosted = removeFromNotificationListsLocked(r);
                        cancelNotificationLocked(r, sendDelete, reason, wasPosted);
                        cancelGroupChildrenLocked(r, callingUid, callingPid, listenerName,
                                sendDelete);
                        updateLightsLocked();
                    } else {
                        // No notification was found, assume that it is snoozed and cancel it.
                        if (reason != REASON_SNOOZED) {
                            final boolean wasSnoozed = mSnoozeHelper.cancel(userId, pkg, tag, id);
                            if (wasSnoozed) {
                                savePolicyFile();
                            }
                        }
                    }
                }
            }
        });
    }

    /**
     * Determine whether the userId applies to the notification in question, either because
     * they match exactly, or one of them is USER_ALL (which is treated as a wildcard).
     */
    private boolean notificationMatchesUserId(NotificationRecord r, int userId) {
        return
                // looking for USER_ALL notifications? match everything
                   userId == UserHandle.USER_ALL
                // a notification sent to USER_ALL matches any query
                || r.getUserId() == UserHandle.USER_ALL
                // an exact user match
                || r.getUserId() == userId;
    }

    /**
     * Determine whether the userId applies to the notification in question, either because
     * they match exactly, or one of them is USER_ALL (which is treated as a wildcard) or
     * because it matches one of the users profiles.
     */
    private boolean notificationMatchesCurrentProfiles(NotificationRecord r, int userId) {
        return notificationMatchesUserId(r, userId)
                || mUserProfiles.isCurrentProfile(r.getUserId());
    }

    /**
     * Cancels all notifications from a given package that have all of the
     * {@code mustHaveFlags}.
     */
    void cancelAllNotificationsInt(int callingUid, int callingPid, String pkg, String channelId,
            int mustHaveFlags, int mustNotHaveFlags, boolean doit, int userId, int reason,
            ManagedServiceInfo listener) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                String listenerName = listener == null ? null : listener.component.toShortString();
                EventLogTags.writeNotificationCancelAll(callingUid, callingPid,
                        pkg, userId, mustHaveFlags, mustNotHaveFlags, reason,
                        listenerName);

                // Why does this parameter exist? Do we actually want to execute the above if doit
                // is false?
                if (!doit) {
                    return;
                }

                synchronized (mNotificationLock) {
                    FlagChecker flagChecker = (int flags) -> {
                        if ((flags & mustHaveFlags) != mustHaveFlags) {
                            return false;
                        }
                        if ((flags & mustNotHaveFlags) != 0) {
                            return false;
                        }
                        return true;
                    };

                    cancelAllNotificationsByListLocked(mNotificationList, callingUid, callingPid,
                            pkg, true /*nullPkgIndicatesUserSwitch*/, channelId, flagChecker,
                            false /*includeCurrentProfiles*/, userId, false /*sendDelete*/, reason,
                            listenerName, true /* wasPosted */);
                    cancelAllNotificationsByListLocked(mEnqueuedNotifications, callingUid,
                            callingPid, pkg, true /*nullPkgIndicatesUserSwitch*/, channelId,
                            flagChecker, false /*includeCurrentProfiles*/, userId,
                            false /*sendDelete*/, reason, listenerName, false /* wasPosted */);
                    mSnoozeHelper.cancel(userId, pkg);
                }
            }
        });
    }

    private interface FlagChecker {
        // Returns false if these flags do not pass the defined flag test.
        public boolean apply(int flags);
    }

    @GuardedBy("mNotificationLock")
    private void cancelAllNotificationsByListLocked(ArrayList<NotificationRecord> notificationList,
            int callingUid, int callingPid, String pkg, boolean nullPkgIndicatesUserSwitch,
            String channelId, FlagChecker flagChecker, boolean includeCurrentProfiles, int userId,
            boolean sendDelete, int reason, String listenerName, boolean wasPosted) {
        ArrayList<NotificationRecord> canceledNotifications = null;
        for (int i = notificationList.size() - 1; i >= 0; --i) {
            NotificationRecord r = notificationList.get(i);
            if (includeCurrentProfiles) {
                if (!notificationMatchesCurrentProfiles(r, userId)) {
                    continue;
                }
            } else if (!notificationMatchesUserId(r, userId)) {
                continue;
            }
            // Don't remove notifications to all, if there's no package name specified
            if (nullPkgIndicatesUserSwitch && pkg == null && r.getUserId() == UserHandle.USER_ALL) {
                continue;
            }
            if (!flagChecker.apply(r.getFlags())) {
                continue;
            }
            if (pkg != null && !r.sbn.getPackageName().equals(pkg)) {
                continue;
            }
            if (channelId != null && !channelId.equals(r.getChannel().getId())) {
                continue;
            }

            if (canceledNotifications == null) {
                canceledNotifications = new ArrayList<>();
            }
            notificationList.remove(i);
            canceledNotifications.add(r);
            cancelNotificationLocked(r, sendDelete, reason, wasPosted);
        }
        if (canceledNotifications != null) {
            final int M = canceledNotifications.size();
            for (int i = 0; i < M; i++) {
                cancelGroupChildrenLocked(canceledNotifications.get(i), callingUid, callingPid,
                        listenerName, false /* sendDelete */);
            }
            updateLightsLocked();
        }
    }

    void snoozeNotificationInt(String key, long duration, String snoozeCriterionId,
            ManagedServiceInfo listener) {
        String listenerName = listener == null ? null : listener.component.toShortString();
        if (duration <= 0 && snoozeCriterionId == null || key == null) {
            return;
        }

        if (DBG) {
            Slog.d(TAG, String.format("snooze event(%s, %d, %s, %s)", key, duration,
                    snoozeCriterionId, listenerName));
        }
        // Needs to post so that it can cancel notifications not yet enqueued.
        mHandler.post(new SnoozeNotificationRunnable(key, duration, snoozeCriterionId));
    }

    void unsnoozeNotificationInt(String key, ManagedServiceInfo listener) {
        String listenerName = listener == null ? null : listener.component.toShortString();
        if (DBG) {
            Slog.d(TAG, String.format("unsnooze event(%s, %s)", key, listenerName));
        }
        mSnoozeHelper.repost(key);
        savePolicyFile();
    }

    @GuardedBy("mNotificationLock")
    void cancelAllLocked(int callingUid, int callingPid, int userId, int reason,
            ManagedServiceInfo listener, boolean includeCurrentProfiles) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                synchronized (mNotificationLock) {
                    String listenerName =
                            listener == null ? null : listener.component.toShortString();
                    EventLogTags.writeNotificationCancelAll(callingUid, callingPid,
                            null, userId, 0, 0, reason, listenerName);

                    FlagChecker flagChecker = (int flags) -> {
                        if ((flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR))
                                != 0) {
                            return false;
                        }
                        return true;
                    };

                    cancelAllNotificationsByListLocked(mNotificationList, callingUid, callingPid,
                            null, false /*nullPkgIndicatesUserSwitch*/, null, flagChecker,
                            includeCurrentProfiles, userId, true /*sendDelete*/, reason,
                            listenerName, true);
                    cancelAllNotificationsByListLocked(mEnqueuedNotifications, callingUid,
                            callingPid, null, false /*nullPkgIndicatesUserSwitch*/, null,
                            flagChecker, includeCurrentProfiles, userId, true /*sendDelete*/,
                            reason, listenerName, false);
                    mSnoozeHelper.cancel(userId, includeCurrentProfiles);
                }
            }
        });
    }

    // Warning: The caller is responsible for invoking updateLightsLocked().
    @GuardedBy("mNotificationLock")
    private void cancelGroupChildrenLocked(NotificationRecord r, int callingUid, int callingPid,
            String listenerName, boolean sendDelete) {
        Notification n = r.getNotification();
        if (!n.isGroupSummary()) {
            return;
        }

        String pkg = r.sbn.getPackageName();

        if (pkg == null) {
            if (DBG) Log.e(TAG, "No package for group summary: " + r.getKey());
            return;
        }

        cancelGroupChildrenByListLocked(mNotificationList, r, callingUid, callingPid, listenerName,
                sendDelete, true);
        cancelGroupChildrenByListLocked(mEnqueuedNotifications, r, callingUid, callingPid,
                listenerName, sendDelete, false);
    }

    @GuardedBy("mNotificationLock")
    private void cancelGroupChildrenByListLocked(ArrayList<NotificationRecord> notificationList,
            NotificationRecord parentNotification, int callingUid, int callingPid,
            String listenerName, boolean sendDelete, boolean wasPosted) {
        final String pkg = parentNotification.sbn.getPackageName();
        final int userId = parentNotification.getUserId();
        final int reason = REASON_GROUP_SUMMARY_CANCELED;
        for (int i = notificationList.size() - 1; i >= 0; i--) {
            final NotificationRecord childR = notificationList.get(i);
            final StatusBarNotification childSbn = childR.sbn;
            if ((childSbn.isGroup() && !childSbn.getNotification().isGroupSummary()) &&
                    childR.getGroupKey().equals(parentNotification.getGroupKey())
                    && (childR.getFlags() & Notification.FLAG_FOREGROUND_SERVICE) == 0) {
                EventLogTags.writeNotificationCancel(callingUid, callingPid, pkg, childSbn.getId(),
                        childSbn.getTag(), userId, 0, 0, reason, listenerName);
                notificationList.remove(i);
                cancelNotificationLocked(childR, sendDelete, reason, wasPosted);
            }
        }
    }

    private boolean isLedNotificationForcedOn(NotificationRecord r) {
        if (r != null) {
            final Notification n = r.sbn.getNotification();
            if (n.extras != null) {
                return n.extras.getBoolean(Notification.EXTRA_FORCE_SHOW_LIGHTS, false);
            }
        }
        return false;
    }

    // lock on mNotificationList
    @GuardedBy("mNotificationLock")
    void updateLightsLocked()
    {
        // handle notification lights
        NotificationRecord ledNotification = null;
        while (ledNotification == null && !mLights.isEmpty()) {
            final String owner = mLights.get(mLights.size() - 1);
            ledNotification = mNotificationsByKey.get(owner);
            if (ledNotification == null) {
                Slog.wtfStack(TAG, "LED Notification does not exist: " + owner);
                mLights.remove(owner);
            }
        }

        // Don't flash while we are in a call or screen is on
        // (unless Notification has EXTRA_FORCE_SHOW_LGHTS)
        final boolean enableLed;
        if (ledNotification == null) {
            enableLed = false;
        } else if (isLedNotificationForcedOn(ledNotification)) {
            enableLed = true;
        } else if (!mScreenOnEnabled && (mInCall || mScreenOn)) {
            enableLed = false;
        } else {
            enableLed = true;
        }

        if (!enableLed) {
            mNotificationLight.turnOff();
        } else {
            NotificationRecord.Light light = ledNotification.getLight();
            final NotificationLedValues ledValues = getLedValuesForNotification(ledNotification);
            int ledARGB;
            int ledOnMS;
            int ledOffMS;

            if (ledValues != null) {
                ledARGB = ledValues.color != 0 ? ledValues.color : mDefaultNotificationColor;
                ledOnMS = ledValues.onMS >= 0 ? ledValues.onMS : mDefaultNotificationLedOn;
                ledOffMS = ledValues.offMS >= 0 ? ledValues.offMS : mDefaultNotificationLedOff;
            } else if (isDefaultLights(ledNotification)) {
                ledARGB = mDefaultNotificationColor;
                ledOnMS = mDefaultNotificationLedOn;
                ledOffMS = mDefaultNotificationLedOff;
            } else {
                ledARGB = light.color;
                ledOnMS = light.onMs;
                ledOffMS = light.offMs;
            }

            if (mNotificationPulseEnabled) {
                // update the LEDs modes variables
                mNotificationLight.setModes(mNotificationLedBrightnessLevel);

                // pulse repeatedly
                mNotificationLight.setFlashing(ledARGB, Light.LIGHT_FLASH_TIMED,
                        ledOnMS, ledOffMS);
            }
        }
    }

    private void parseNotificationPulseCustomValuesString(String customLedValuesString) {
        if (TextUtils.isEmpty(customLedValuesString)) {
            return;
        }

        for (String packageValuesString : customLedValuesString.split("\\|")) {
            String[] packageValues = packageValuesString.split("=");
            if (packageValues.length != 2) {
                Log.e(TAG, "Error parsing custom led values for unknown package");
                continue;
            }
            String packageName = packageValues[0];
            String[] values = packageValues[1].split(";");
            if (values.length != 3) {
                Log.e(TAG, "Error parsing custom led values '"
                        + packageValues[1] + "' for " + packageName);
                continue;
            }
            NotificationLedValues ledValues = new NotificationLedValues();
            try {
                ledValues.color = Integer.parseInt(values[0]);
                ledValues.onMS = Integer.parseInt(values[1]);
                ledValues.offMS = Integer.parseInt(values[2]);
            } catch (NumberFormatException e) {
                Log.e(TAG, "Error parsing custom led values '"
                        + packageValues[1] + "' for " + packageName);
                continue;
            }
            mNotificationPulseCustomLedValues.put(packageName, ledValues);
        }
    }

    private NotificationLedValues getLedValuesForNotification(NotificationRecord ledNotification) {
        final String packageName = ledNotification.sbn.getPackageName();
        return mNotificationPulseCustomLedValues.get(mapPackage(packageName));
    }

    private boolean isDefaultLights(NotificationRecord ledNotification) {
        final Notification notification = ledNotification.sbn.getNotification();
        return (notification.defaults & Notification.DEFAULT_LIGHTS) != 0;
    }

    private String mapPackage(String pkg) {
        if (!mPackageNameMappings.containsKey(pkg)) {
            return pkg;
        }
        return mPackageNameMappings.get(pkg);
    }

    @GuardedBy("mNotificationLock")
    @NonNull List<NotificationRecord> findGroupNotificationsLocked(String pkg,
            String groupKey, int userId) {
        List<NotificationRecord> records = new ArrayList<>();
        records.addAll(findGroupNotificationByListLocked(mNotificationList, pkg, groupKey, userId));
        records.addAll(
                findGroupNotificationByListLocked(mEnqueuedNotifications, pkg, groupKey, userId));
        return records;
    }


    @GuardedBy("mNotificationLock")
    private @NonNull List<NotificationRecord> findGroupNotificationByListLocked(
            ArrayList<NotificationRecord> list, String pkg, String groupKey, int userId) {
        List<NotificationRecord> records = new ArrayList<>();
        final int len = list.size();
        for (int i = 0; i < len; i++) {
            NotificationRecord r = list.get(i);
            if (notificationMatchesUserId(r, userId) && r.getGroupKey().equals(groupKey)
                    && r.sbn.getPackageName().equals(pkg)) {
                records.add(r);
            }
        }
        return records;
    }

    // Searches both enqueued and posted notifications by key.
    // TODO: need to combine a bunch of these getters with slightly different behavior.
    // TODO: Should enqueuing just add to mNotificationsByKey instead?
    @GuardedBy("mNotificationLock")
    private NotificationRecord findNotificationByKeyLocked(String key) {
        NotificationRecord r;
        if ((r = findNotificationByListLocked(mNotificationList, key)) != null) {
            return r;
        }
        if ((r = findNotificationByListLocked(mEnqueuedNotifications, key)) != null) {
            return r;
        }
        return null;
    }

    @GuardedBy("mNotificationLock")
    NotificationRecord findNotificationLocked(String pkg, String tag, int id, int userId) {
        NotificationRecord r;
        if ((r = findNotificationByListLocked(mNotificationList, pkg, tag, id, userId)) != null) {
            return r;
        }
        if ((r = findNotificationByListLocked(mEnqueuedNotifications, pkg, tag, id, userId))
                != null) {
            return r;
        }
        return null;
    }

    @GuardedBy("mNotificationLock")
    private NotificationRecord findNotificationByListLocked(ArrayList<NotificationRecord> list,
            String pkg, String tag, int id, int userId) {
        final int len = list.size();
        for (int i = 0; i < len; i++) {
            NotificationRecord r = list.get(i);
            if (notificationMatchesUserId(r, userId) && r.sbn.getId() == id &&
                    TextUtils.equals(r.sbn.getTag(), tag) && r.sbn.getPackageName().equals(pkg)) {
                return r;
            }
        }
        return null;
    }

    @GuardedBy("mNotificationLock")
    private NotificationRecord findNotificationByListLocked(ArrayList<NotificationRecord> list,
            String key) {
        final int N = list.size();
        for (int i = 0; i < N; i++) {
            if (key.equals(list.get(i).getKey())) {
                return list.get(i);
            }
        }
        return null;
    }

    @GuardedBy("mNotificationLock")
    int indexOfNotificationLocked(String key) {
        final int N = mNotificationList.size();
        for (int i = 0; i < N; i++) {
            if (key.equals(mNotificationList.get(i).getKey())) {
                return i;
            }
        }
        return -1;
    }

    private void updateNotificationPulse() {
        synchronized (mNotificationLock) {
            updateLightsLocked();
        }
    }

    protected boolean isCallingUidSystem() {
        final int uid = Binder.getCallingUid();
        return uid == Process.SYSTEM_UID;
    }

    protected boolean isUidSystemOrPhone(int uid) {
        final int appid = UserHandle.getAppId(uid);
        return (appid == Process.SYSTEM_UID || appid == Process.PHONE_UID || uid == 0);
    }

    // TODO: Most calls should probably move to isCallerSystem.
    protected boolean isCallerSystemOrPhone() {
        return isUidSystemOrPhone(Binder.getCallingUid());
    }

    private void checkCallerIsSystem() {
        if (isCallerSystemOrPhone()) {
            return;
        }
        throw new SecurityException("Disallowed call for uid " + Binder.getCallingUid());
    }

    private void checkCallerIsSystemOrSameApp(String pkg) {
        if (isCallerSystemOrPhone()) {
            return;
        }
        checkCallerIsSameApp(pkg);
    }

    private boolean isCallerInstantApp(String pkg) {
        // System is always allowed to act for ephemeral apps.
        if (isCallerSystemOrPhone()) {
            return false;
        }

        mAppOps.checkPackage(Binder.getCallingUid(), pkg);

        try {
            ApplicationInfo ai = mPackageManager.getApplicationInfo(pkg, 0,
                    UserHandle.getCallingUserId());
            if (ai == null) {
                throw new SecurityException("Unknown package " + pkg);
            }
            return ai.isInstantApp();
        } catch (RemoteException re) {
            throw new SecurityException("Unknown package " + pkg, re);
        }

    }

    private void checkCallerIsSameApp(String pkg) {
        final int uid = Binder.getCallingUid();
        try {
            ApplicationInfo ai = mPackageManager.getApplicationInfo(
                    pkg, 0, UserHandle.getCallingUserId());
            if (ai == null) {
                throw new SecurityException("Unknown package " + pkg);
            }
            if (!UserHandle.isSameApp(ai.uid, uid)) {
                throw new SecurityException("Calling uid " + uid + " gave package "
                        + pkg + " which is owned by uid " + ai.uid);
            }
        } catch (RemoteException re) {
            throw new SecurityException("Unknown package " + pkg + "\n" + re);
        }
    }

    private static String callStateToString(int state) {
        switch (state) {
            case TelephonyManager.CALL_STATE_IDLE: return "CALL_STATE_IDLE";
            case TelephonyManager.CALL_STATE_RINGING: return "CALL_STATE_RINGING";
            case TelephonyManager.CALL_STATE_OFFHOOK: return "CALL_STATE_OFFHOOK";
            default: return "CALL_STATE_UNKNOWN_" + state;
        }
    }

    private void listenForCallState() {
        TelephonyManager.from(getContext()).listen(new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String incomingNumber) {
                if (mCallState == state) return;
                if (DBG) Slog.d(TAG, "Call state changed: " + callStateToString(state));
                mCallState = state;
            }
        }, PhoneStateListener.LISTEN_CALL_STATE);
    }

    /**
     * Generates a NotificationRankingUpdate from 'sbns', considering only
     * notifications visible to the given listener.
     */
    @GuardedBy("mNotificationLock")
    private NotificationRankingUpdate makeRankingUpdateLocked(ManagedServiceInfo info) {
        final int N = mNotificationList.size();
        ArrayList<String> keys = new ArrayList<String>(N);
        ArrayList<String> interceptedKeys = new ArrayList<String>(N);
        ArrayList<Integer> importance = new ArrayList<>(N);
        Bundle overrideGroupKeys = new Bundle();
        Bundle visibilityOverrides = new Bundle();
        Bundle suppressedVisualEffects = new Bundle();
        Bundle explanation = new Bundle();
        Bundle channels = new Bundle();
        Bundle overridePeople = new Bundle();
        Bundle snoozeCriteria = new Bundle();
        Bundle showBadge = new Bundle();
        for (int i = 0; i < N; i++) {
            NotificationRecord record = mNotificationList.get(i);
            if (!isVisibleToListener(record.sbn, info)) {
                continue;
            }
            final String key = record.sbn.getKey();
            keys.add(key);
            importance.add(record.getImportance());
            if (record.getImportanceExplanation() != null) {
                explanation.putCharSequence(key, record.getImportanceExplanation());
            }
            if (record.isIntercepted()) {
                interceptedKeys.add(key);

            }
            suppressedVisualEffects.putInt(key, record.getSuppressedVisualEffects());
            if (record.getPackageVisibilityOverride()
                    != NotificationListenerService.Ranking.VISIBILITY_NO_OVERRIDE) {
                visibilityOverrides.putInt(key, record.getPackageVisibilityOverride());
            }
            overrideGroupKeys.putString(key, record.sbn.getOverrideGroupKey());
            channels.putParcelable(key, record.getChannel());
            overridePeople.putStringArrayList(key, record.getPeopleOverride());
            snoozeCriteria.putParcelableArrayList(key, record.getSnoozeCriteria());
            showBadge.putBoolean(key, record.canShowBadge());
        }
        final int M = keys.size();
        String[] keysAr = keys.toArray(new String[M]);
        String[] interceptedKeysAr = interceptedKeys.toArray(new String[interceptedKeys.size()]);
        int[] importanceAr = new int[M];
        for (int i = 0; i < M; i++) {
            importanceAr[i] = importance.get(i);
        }
        return new NotificationRankingUpdate(keysAr, interceptedKeysAr, visibilityOverrides,
                suppressedVisualEffects, importanceAr, explanation, overrideGroupKeys,
                channels, overridePeople, snoozeCriteria, showBadge);
    }

    boolean hasCompanionDevice(ManagedServiceInfo info) {
        if (mCompanionManager == null) {
            mCompanionManager = getCompanionManager();
        }
        // Companion mgr doesn't exist on all device types
        if (mCompanionManager == null) {
            return false;
        }
        long identity = Binder.clearCallingIdentity();
        try {
            List<String> associations = mCompanionManager.getAssociations(
                    info.component.getPackageName(), info.userid);
            if (!ArrayUtils.isEmpty(associations)) {
                return true;
            }
        } catch (SecurityException se) {
            // Not a privileged listener
        } catch (RemoteException re) {
            Slog.e(TAG, "Cannot reach companion device service", re);
        } catch (Exception e) {
            Slog.e(TAG, "Cannot verify listener " + info, e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return false;
    }

    protected ICompanionDeviceManager getCompanionManager() {
        return ICompanionDeviceManager.Stub.asInterface(
                ServiceManager.getService(Context.COMPANION_DEVICE_SERVICE));
    }

    private boolean isVisibleToListener(StatusBarNotification sbn, ManagedServiceInfo listener) {
        if (!listener.enabledAndUserMatches(sbn.getUserId())) {
            return false;
        }
        // TODO: remove this for older listeners.
        return true;
    }

    private boolean isPackageSuspendedForUser(String pkg, int uid) {
        int userId = UserHandle.getUserId(uid);
        try {
            return mPackageManager.isPackageSuspendedForUser(pkg, userId);
        } catch (RemoteException re) {
            throw new SecurityException("Could not talk to package manager service");
        } catch (IllegalArgumentException ex) {
            // Package not found.
            return false;
        }
    }

    private class TrimCache {
        StatusBarNotification heavy;
        StatusBarNotification sbnClone;
        StatusBarNotification sbnCloneLight;

        TrimCache(StatusBarNotification sbn) {
            heavy = sbn;
        }

        StatusBarNotification ForListener(ManagedServiceInfo info) {
            if (mListeners.getOnNotificationPostedTrim(info) == TRIM_LIGHT) {
                if (sbnCloneLight == null) {
                    sbnCloneLight = heavy.cloneLight();
                }
                return sbnCloneLight;
            } else {
                if (sbnClone == null) {
                    sbnClone = heavy.clone();
                }
                return sbnClone;
            }
        }
    }

    public class NotificationAssistants extends ManagedServices {

        public NotificationAssistants() {
            super(getContext(), mHandler, mNotificationLock, mUserProfiles);
        }

        @Override
        protected Config getConfig() {
            Config c = new Config();
            c.caption = "notification assistant service";
            c.serviceInterface = NotificationAssistantService.SERVICE_INTERFACE;
            c.secureSettingName = Settings.Secure.ENABLED_NOTIFICATION_ASSISTANT;
            c.bindPermission = Manifest.permission.BIND_NOTIFICATION_ASSISTANT_SERVICE;
            c.settingsAction = Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS;
            c.clientLabel = R.string.notification_ranker_binding_label;
            return c;
        }

        @Override
        protected IInterface asInterface(IBinder binder) {
            return INotificationListener.Stub.asInterface(binder);
        }

        @Override
        protected boolean checkType(IInterface service) {
            return service instanceof INotificationListener;
        }

        @Override
        protected void onServiceAdded(ManagedServiceInfo info) {
            mListeners.registerGuestService(info);
        }

        @Override
        @GuardedBy("mNotificationLock")
        protected void onServiceRemovedLocked(ManagedServiceInfo removed) {
            mListeners.unregisterService(removed.service, removed.userid);
        }

        public void onNotificationEnqueued(final NotificationRecord r) {
            final StatusBarNotification sbn = r.sbn;
            TrimCache trimCache = new TrimCache(sbn);

            // There should be only one, but it's a list, so while we enforce
            // singularity elsewhere, we keep it general here, to avoid surprises.
            for (final ManagedServiceInfo info : NotificationAssistants.this.getServices()) {
                boolean sbnVisible = isVisibleToListener(sbn, info);
                if (!sbnVisible) {
                    continue;
                }

                final int importance = r.getImportance();
                final boolean fromUser = r.isImportanceFromUser();
                final StatusBarNotification sbnToPost =  trimCache.ForListener(info);
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        notifyEnqueued(info, sbnToPost);
                    }
                });
            }
        }

        private void notifyEnqueued(final ManagedServiceInfo info,
                final StatusBarNotification sbn) {
            final INotificationListener assistant = (INotificationListener) info.service;
            StatusBarNotificationHolder sbnHolder = new StatusBarNotificationHolder(sbn);
            try {
                assistant.onNotificationEnqueued(sbnHolder);
            } catch (RemoteException ex) {
                Log.e(TAG, "unable to notify assistant (enqueued): " + assistant, ex);
            }
        }

        /**
         * asynchronously notify the assistant that a notification has been snoozed until a
         * context
         */
        @GuardedBy("mNotificationLock")
        public void notifyAssistantSnoozedLocked(final StatusBarNotification sbn,
                final String snoozeCriterionId) {
            TrimCache trimCache = new TrimCache(sbn);
            for (final ManagedServiceInfo info : getServices()) {
                final StatusBarNotification sbnToPost =  trimCache.ForListener(info);
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        final INotificationListener assistant =
                                (INotificationListener) info.service;
                        StatusBarNotificationHolder sbnHolder
                                = new StatusBarNotificationHolder(sbnToPost);
                        try {
                            assistant.onNotificationSnoozedUntilContext(
                                    sbnHolder, snoozeCriterionId);
                        } catch (RemoteException ex) {
                            Log.e(TAG, "unable to notify assistant (snoozed): " + assistant, ex);
                        }
                    }
                });
            }
        }

        public boolean isEnabled() {
            return !getServices().isEmpty();
        }
    }

    public class NotificationListeners extends ManagedServices {

        private final ArraySet<ManagedServiceInfo> mLightTrimListeners = new ArraySet<>();

        public NotificationListeners() {
            super(getContext(), mHandler, mNotificationLock, mUserProfiles);
        }

        @Override
        protected Config getConfig() {
            Config c = new Config();
            c.caption = "notification listener";
            c.serviceInterface = NotificationListenerService.SERVICE_INTERFACE;
            c.secureSettingName = Settings.Secure.ENABLED_NOTIFICATION_LISTENERS;
            c.bindPermission = android.Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE;
            c.settingsAction = Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS;
            c.clientLabel = R.string.notification_listener_binding_label;
            return c;
        }

        @Override
        protected IInterface asInterface(IBinder binder) {
            return INotificationListener.Stub.asInterface(binder);
        }

        @Override
        protected boolean checkType(IInterface service) {
            return service instanceof INotificationListener;
        }

        @Override
        public void onServiceAdded(ManagedServiceInfo info) {
            final INotificationListener listener = (INotificationListener) info.service;
            final NotificationRankingUpdate update;
            synchronized (mNotificationLock) {
                update = makeRankingUpdateLocked(info);
            }
            try {
                listener.onListenerConnected(update);
            } catch (RemoteException e) {
                // we tried
            }
        }

        @Override
        @GuardedBy("mNotificationLock")
        protected void onServiceRemovedLocked(ManagedServiceInfo removed) {
            if (removeDisabledHints(removed)) {
                updateListenerHintsLocked();
                updateEffectsSuppressorLocked();
            }
            mLightTrimListeners.remove(removed);
        }

        @GuardedBy("mNotificationLock")
        public void setOnNotificationPostedTrimLocked(ManagedServiceInfo info, int trim) {
            if (trim == TRIM_LIGHT) {
                mLightTrimListeners.add(info);
            } else {
                mLightTrimListeners.remove(info);
            }
        }

        public int getOnNotificationPostedTrim(ManagedServiceInfo info) {
            return mLightTrimListeners.contains(info) ? TRIM_LIGHT : TRIM_FULL;
        }

        /**
         * asynchronously notify all listeners about a new notification
         *
         * <p>
         * Also takes care of removing a notification that has been visible to a listener before,
         * but isn't anymore.
         */
        @GuardedBy("mNotificationLock")
        public void notifyPostedLocked(StatusBarNotification sbn, StatusBarNotification oldSbn) {
            // Lazily initialized snapshots of the notification.
            TrimCache trimCache = new TrimCache(sbn);

            for (final ManagedServiceInfo info : getServices()) {
                boolean sbnVisible = isVisibleToListener(sbn, info);
                boolean oldSbnVisible = oldSbn != null ? isVisibleToListener(oldSbn, info) : false;
                // This notification hasn't been and still isn't visible -> ignore.
                if (!oldSbnVisible && !sbnVisible) {
                    continue;
                }
                final NotificationRankingUpdate update = makeRankingUpdateLocked(info);

                // This notification became invisible -> remove the old one.
                if (oldSbnVisible && !sbnVisible) {
                    final StatusBarNotification oldSbnLightClone = oldSbn.cloneLight();
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            notifyRemoved(info, oldSbnLightClone, update, REASON_USER_STOPPED);
                        }
                    });
                    continue;
                }

                final StatusBarNotification sbnToPost =  trimCache.ForListener(info);
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        notifyPosted(info, sbnToPost, update);
                    }
                });
            }
        }

        /**
         * asynchronously notify all listeners about a removed notification
         */
        @GuardedBy("mNotificationLock")
        public void notifyRemovedLocked(StatusBarNotification sbn, int reason) {
            // make a copy in case changes are made to the underlying Notification object
            // NOTE: this copy is lightweight: it doesn't include heavyweight parts of the
            // notification
            final StatusBarNotification sbnLight = sbn.cloneLight();
            for (final ManagedServiceInfo info : getServices()) {
                if (!isVisibleToListener(sbn, info)) {
                    continue;
                }
                final NotificationRankingUpdate update = makeRankingUpdateLocked(info);
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        notifyRemoved(info, sbnLight, update, reason);
                    }
                });
            }
        }

        /**
         * asynchronously notify all listeners about a reordering of notifications
         */
        @GuardedBy("mNotificationLock")
        public void notifyRankingUpdateLocked() {
            for (final ManagedServiceInfo serviceInfo : getServices()) {
                if (!serviceInfo.isEnabledForCurrentProfiles()) {
                    continue;
                }
                final NotificationRankingUpdate update = makeRankingUpdateLocked(serviceInfo);
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        notifyRankingUpdate(serviceInfo, update);
                    }
                });
            }
        }

        @GuardedBy("mNotificationLock")
        public void notifyListenerHintsChangedLocked(final int hints) {
            for (final ManagedServiceInfo serviceInfo : getServices()) {
                if (!serviceInfo.isEnabledForCurrentProfiles()) {
                    continue;
                }
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        notifyListenerHintsChanged(serviceInfo, hints);
                    }
                });
            }
        }

        public void notifyInterruptionFilterChanged(final int interruptionFilter) {
            for (final ManagedServiceInfo serviceInfo : getServices()) {
                if (!serviceInfo.isEnabledForCurrentProfiles()) {
                    continue;
                }
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        notifyInterruptionFilterChanged(serviceInfo, interruptionFilter);
                    }
                });
            }
        }

        protected void notifyNotificationChannelChanged(final String pkg, final UserHandle user,
                final NotificationChannel channel, final int modificationType) {
            if (channel == null) {
                return;
            }
            for (final ManagedServiceInfo serviceInfo : getServices()) {
                if (!serviceInfo.enabledAndUserMatches(UserHandle.getCallingUserId())) {
                    continue;
                }

                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (hasCompanionDevice(serviceInfo)) {
                            notifyNotificationChannelChanged(
                                    serviceInfo, pkg, user, channel, modificationType);
                        }
                    }
                });
            }
        }

        protected void notifyNotificationChannelGroupChanged(
                final String pkg, final UserHandle user, final NotificationChannelGroup group,
                final int modificationType) {
            if (group == null) {
                return;
            }
            for (final ManagedServiceInfo serviceInfo : getServices()) {
                if (!serviceInfo.enabledAndUserMatches(UserHandle.getCallingUserId())) {
                    continue;
                }

                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (hasCompanionDevice(serviceInfo)) {
                            notifyNotificationChannelGroupChanged(
                                    serviceInfo, pkg, user, group, modificationType);
                        }
                    }
                });
            }
        }

        private void notifyPosted(final ManagedServiceInfo info,
                final StatusBarNotification sbn, NotificationRankingUpdate rankingUpdate) {
            final INotificationListener listener = (INotificationListener) info.service;
            StatusBarNotificationHolder sbnHolder = new StatusBarNotificationHolder(sbn);
            try {
                listener.onNotificationPosted(sbnHolder, rankingUpdate);
            } catch (RemoteException ex) {
                Log.e(TAG, "unable to notify listener (posted): " + listener, ex);
            }
        }

        private void notifyRemoved(ManagedServiceInfo info, StatusBarNotification sbn,
                NotificationRankingUpdate rankingUpdate, int reason) {
            if (!info.enabledAndUserMatches(sbn.getUserId())) {
                return;
            }
            final INotificationListener listener = (INotificationListener) info.service;
            StatusBarNotificationHolder sbnHolder = new StatusBarNotificationHolder(sbn);
            try {
                listener.onNotificationRemoved(sbnHolder, rankingUpdate, reason);
            } catch (RemoteException ex) {
                Log.e(TAG, "unable to notify listener (removed): " + listener, ex);
            }
        }

        private void notifyRankingUpdate(ManagedServiceInfo info,
                                         NotificationRankingUpdate rankingUpdate) {
            final INotificationListener listener = (INotificationListener) info.service;
            try {
                listener.onNotificationRankingUpdate(rankingUpdate);
            } catch (RemoteException ex) {
                Log.e(TAG, "unable to notify listener (ranking update): " + listener, ex);
            }
        }

        private void notifyListenerHintsChanged(ManagedServiceInfo info, int hints) {
            final INotificationListener listener = (INotificationListener) info.service;
            try {
                listener.onListenerHintsChanged(hints);
            } catch (RemoteException ex) {
                Log.e(TAG, "unable to notify listener (listener hints): " + listener, ex);
            }
        }

        private void notifyInterruptionFilterChanged(ManagedServiceInfo info,
                int interruptionFilter) {
            final INotificationListener listener = (INotificationListener) info.service;
            try {
                listener.onInterruptionFilterChanged(interruptionFilter);
            } catch (RemoteException ex) {
                Log.e(TAG, "unable to notify listener (interruption filter): " + listener, ex);
            }
        }

        void notifyNotificationChannelChanged(ManagedServiceInfo info,
                final String pkg, final UserHandle user, final NotificationChannel channel,
                final int modificationType) {
            final INotificationListener listener = (INotificationListener) info.service;
            try {
                listener.onNotificationChannelModification(pkg, user, channel, modificationType);
            } catch (RemoteException ex) {
                Log.e(TAG, "unable to notify listener (channel changed): " + listener, ex);
            }
        }

        private void notifyNotificationChannelGroupChanged(ManagedServiceInfo info,
                final String pkg, final UserHandle user, final NotificationChannelGroup group,
                final int modificationType) {
            final INotificationListener listener = (INotificationListener) info.service;
            try {
                listener.onNotificationChannelGroupModification(pkg, user, group, modificationType);
            } catch (RemoteException ex) {
                Log.e(TAG, "unable to notify listener (channel group changed): " + listener, ex);
            }
        }

        public boolean isListenerPackage(String packageName) {
            if (packageName == null) {
                return false;
            }
            // TODO: clean up locking object later
            synchronized (mNotificationLock) {
                for (final ManagedServiceInfo serviceInfo : getServices()) {
                    if (packageName.equals(serviceInfo.component.getPackageName())) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    public static final class DumpFilter {
        public boolean filtered = false;
        public String pkgFilter;
        public boolean zen;
        public long since;
        public boolean stats;
        public boolean redact = true;
        public boolean proto = false;

        public static DumpFilter parseFromArguments(String[] args) {
            final DumpFilter filter = new DumpFilter();
            for (int ai = 0; ai < args.length; ai++) {
                final String a = args[ai];
                if ("--proto".equals(args[0])) {
                    filter.proto = true;
                }
                if ("--noredact".equals(a) || "--reveal".equals(a)) {
                    filter.redact = false;
                } else if ("p".equals(a) || "pkg".equals(a) || "--package".equals(a)) {
                    if (ai < args.length-1) {
                        ai++;
                        filter.pkgFilter = args[ai].trim().toLowerCase();
                        if (filter.pkgFilter.isEmpty()) {
                            filter.pkgFilter = null;
                        } else {
                            filter.filtered = true;
                        }
                    }
                } else if ("--zen".equals(a) || "zen".equals(a)) {
                    filter.filtered = true;
                    filter.zen = true;
                } else if ("--stats".equals(a)) {
                    filter.stats = true;
                    if (ai < args.length-1) {
                        ai++;
                        filter.since = Long.parseLong(args[ai]);
                    } else {
                        filter.since = 0;
                    }
                }
            }
            return filter;
        }

        public boolean matches(StatusBarNotification sbn) {
            if (!filtered) return true;
            return zen ? true : sbn != null
                    && (matches(sbn.getPackageName()) || matches(sbn.getOpPkg()));
        }

        public boolean matches(ComponentName component) {
            if (!filtered) return true;
            return zen ? true : component != null && matches(component.getPackageName());
        }

        public boolean matches(String pkg) {
            if (!filtered) return true;
            return zen ? true : pkg != null && pkg.toLowerCase().contains(pkgFilter);
        }

        @Override
        public String toString() {
            return stats ? "stats" : zen ? "zen" : ('\'' + pkgFilter + '\'');
        }
    }

    /**
     * Wrapper for a StatusBarNotification object that allows transfer across a oneway
     * binder without sending large amounts of data over a oneway transaction.
     */
    private static final class StatusBarNotificationHolder
            extends IStatusBarNotificationHolder.Stub {
        private StatusBarNotification mValue;

        public StatusBarNotificationHolder(StatusBarNotification value) {
            mValue = value;
        }

        /** Get the held value and clear it. This function should only be called once per holder */
        @Override
        public StatusBarNotification get() {
            StatusBarNotification value = mValue;
            mValue = null;
            return value;
        }
    }

    private final class PolicyAccess {
        private static final String SEPARATOR = ":";
        private final String[] PERM = {
            android.Manifest.permission.ACCESS_NOTIFICATION_POLICY
        };

        public boolean isPackageGranted(String pkg) {
            return pkg != null && getGrantedPackages().contains(pkg);
        }

        public void put(String pkg, boolean granted) {
            if (pkg == null) return;
            final ArraySet<String> pkgs = getGrantedPackages();
            boolean changed;
            if (granted) {
                changed = pkgs.add(pkg);
            } else {
                changed = pkgs.remove(pkg);
            }
            if (!changed) return;
            final String setting = TextUtils.join(SEPARATOR, pkgs);
            final int currentUser = ActivityManager.getCurrentUser();
            Settings.Secure.putStringForUser(getContext().getContentResolver(),
                    Settings.Secure.ENABLED_NOTIFICATION_POLICY_ACCESS_PACKAGES,
                    setting,
                    currentUser);
            getContext().sendBroadcastAsUser(new Intent(NotificationManager
                    .ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED)
                .setPackage(pkg)
                .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY), new UserHandle(currentUser), null);
        }

        public ArraySet<String> getGrantedPackages() {
            final ArraySet<String> pkgs = new ArraySet<>();

            long identity = Binder.clearCallingIdentity();
            try {
                final String setting = Settings.Secure.getStringForUser(
                        getContext().getContentResolver(),
                        Settings.Secure.ENABLED_NOTIFICATION_POLICY_ACCESS_PACKAGES,
                        ActivityManager.getCurrentUser());
                if (setting != null) {
                    final String[] tokens = setting.split(SEPARATOR);
                    for (int i = 0; i < tokens.length; i++) {
                        String token = tokens[i];
                        if (token != null) {
                            token = token.trim();
                        }
                        if (TextUtils.isEmpty(token)) {
                            continue;
                        }
                        pkgs.add(token);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            return pkgs;
        }

        public String[] getRequestingPackages() throws RemoteException {
            final ParceledListSlice list = mPackageManager
                    .getPackagesHoldingPermissions(PERM, 0 /*flags*/,
                            ActivityManager.getCurrentUser());
            final List<PackageInfo> pkgs = list.getList();
            if (pkgs == null || pkgs.isEmpty()) return new String[0];
            final int N = pkgs.size();
            final String[] rt = new String[N];
            for (int i = 0; i < N; i++) {
                rt[i] = pkgs.get(i).packageName;
            }
            return rt;
        }
    }
}
