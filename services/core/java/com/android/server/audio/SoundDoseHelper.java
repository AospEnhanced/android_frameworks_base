/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.audio;

import static com.android.server.audio.AudioService.MAX_STREAM_VOLUME;
import static com.android.server.audio.AudioService.MIN_STREAM_VOLUME;
import static com.android.server.audio.AudioService.MSG_SET_DEVICE_VOLUME;
import static com.android.server.audio.AudioService.SAFE_MEDIA_VOLUME_MSG_START;

import android.annotation.NonNull;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.media.ISoundDose;
import android.media.ISoundDoseCallback;
import android.media.SoundDoseRecord;
import android.os.Binder;
import android.os.Message;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.MathUtils;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.server.audio.AudioService.AudioHandler;
import com.android.server.audio.AudioService.ISafeHearingVolumeController;
import com.android.server.audio.AudioServiceEvents.SoundDoseEvent;
import com.android.server.utils.EventLogger;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Safe media volume management.
 * MUSIC stream volume level is limited when headphones are connected according to safety
 * regulation. When the user attempts to raise the volume above the limit, a warning is
 * displayed and the user has to acknowledge before the volume is actually changed.
 * The volume index corresponding to the limit is stored in config_safe_media_volume_index
 * property. Platforms with a different limit must set this property accordingly in their
 * overlay.
 */
public class SoundDoseHelper {
    private static final String TAG = "AS.SoundDoseHelper";

    /*package*/ static final String ACTION_CHECK_MUSIC_ACTIVE =
            "com.android.server.audio.action.CHECK_MUSIC_ACTIVE";

    // mSafeMediaVolumeState indicates whether the media volume is limited over headphones.
    // It is SAFE_MEDIA_VOLUME_NOT_CONFIGURED at boot time until a network service is connected
    // or the configure time is elapsed. It is then set to SAFE_MEDIA_VOLUME_ACTIVE or
    // SAFE_MEDIA_VOLUME_DISABLED according to country option. If not SAFE_MEDIA_VOLUME_DISABLED, it
    // can be set to SAFE_MEDIA_VOLUME_INACTIVE by calling AudioService.disableSafeMediaVolume()
    // (when user opts out).
    // Note: when CSD calculation is enabled the state is set to SAFE_MEDIA_VOLUME_DISABLED
    private static final int SAFE_MEDIA_VOLUME_NOT_CONFIGURED = 0;
    private static final int SAFE_MEDIA_VOLUME_DISABLED = 1;
    private static final int SAFE_MEDIA_VOLUME_INACTIVE = 2;  // confirmed
    private static final int SAFE_MEDIA_VOLUME_ACTIVE = 3;  // unconfirmed

    private static final int MSG_CONFIGURE_SAFE_MEDIA = SAFE_MEDIA_VOLUME_MSG_START + 1;
    private static final int MSG_PERSIST_SAFE_VOLUME_STATE = SAFE_MEDIA_VOLUME_MSG_START + 2;
    private static final int MSG_PERSIST_MUSIC_ACTIVE_MS = SAFE_MEDIA_VOLUME_MSG_START + 3;
    private static final int MSG_PERSIST_CSD_VALUES = SAFE_MEDIA_VOLUME_MSG_START + 4;
    /*package*/ static final int MSG_CSD_UPDATE_ATTENUATION = SAFE_MEDIA_VOLUME_MSG_START + 5;

    private static final int UNSAFE_VOLUME_MUSIC_ACTIVE_MS_MAX = (20 * 3600 * 1000); // 20 hours

    // 30s after boot completed
    private static final int SAFE_VOLUME_CONFIGURE_TIMEOUT_MS = 30000;

    private static final int MUSIC_ACTIVE_POLL_PERIOD_MS = 60000;  // 1 minute polling interval
    private static final int REQUEST_CODE_CHECK_MUSIC_ACTIVE = 1;

    // timeouts for the CSD warnings, -1 means no timeout (dialog must be ack'd by user)
    private static final int CSD_WARNING_TIMEOUT_MS_DOSE_1X = 7000;
    private static final int CSD_WARNING_TIMEOUT_MS_DOSE_5X = 5000;
    private static final int CSD_WARNING_TIMEOUT_MS_ACCUMULATION_START = -1;
    private static final int CSD_WARNING_TIMEOUT_MS_MOMENTARY_EXPOSURE = 5000;

    private static final String PERSIST_CSD_RECORD_FIELD_SEPARATOR = ",";
    private static final String PERSIST_CSD_RECORD_SEPARATOR_CHAR = "|";
    private static final String PERSIST_CSD_RECORD_SEPARATOR = "\\|";

    private static final long GLOBAL_TIME_OFFSET_UNINITIALIZED = -1;

    private final EventLogger mLogger = new EventLogger(AudioService.LOG_NB_EVENTS_SOUND_DOSE,
            "CSD updates");

    private int mMcc = 0;

    private final Object mSafeMediaVolumeStateLock = new Object();
    private int mSafeMediaVolumeState;

    // Used when safe volume warning message display is requested by setStreamVolume(). In this
    // case, the new requested volume, stream type and device are stored in mPendingVolumeCommand
    // and used later when/if disableSafeMediaVolume() is called.
    private StreamVolumeCommand mPendingVolumeCommand;

    // mSafeMediaVolumeIndex is the cached value of config_safe_media_volume_index property
    private int mSafeMediaVolumeIndex;
    // mSafeUsbMediaVolumeDbfs is the cached value of the config_safe_media_volume_usb_mB
    // property, divided by 100.0.
    private float mSafeUsbMediaVolumeDbfs;

    // mSafeUsbMediaVolumeIndex is used for USB Headsets and is the music volume UI index
    // corresponding to a gain of mSafeUsbMediaVolumeDbfs (defaulting to -37dB) in audio
    // flinger mixer.
    // We remove -22 dBs from the theoretical -15dB to account for the EQ + bass boost
    // amplification when both effects are on with all band gains at maximum.
    // This level corresponds to a loudness of 85 dB SPL for the warning to be displayed when
    // the headset is compliant to EN 60950 with a max loudness of 100dB SPL.
    private int mSafeUsbMediaVolumeIndex;
    // mSafeMediaVolumeDevices lists the devices for which safe media volume is enforced,
    private final Set<Integer> mSafeMediaVolumeDevices = new HashSet<>(
            Arrays.asList(AudioSystem.DEVICE_OUT_WIRED_HEADSET,
                    AudioSystem.DEVICE_OUT_WIRED_HEADPHONE, AudioSystem.DEVICE_OUT_USB_HEADSET));

    private final Set<Integer> mSafeMediaCsdDevices = new HashSet<>(
            Arrays.asList(AudioSystem.DEVICE_OUT_WIRED_HEADSET,
                    AudioSystem.DEVICE_OUT_WIRED_HEADPHONE, AudioSystem.DEVICE_OUT_USB_HEADSET,
                    AudioSystem.DEVICE_OUT_BLE_HEADSET, AudioSystem.DEVICE_OUT_BLE_BROADCAST,
                    AudioSystem.DEVICE_OUT_HEARING_AID,
                    AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP_HEADPHONES,
                    AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP));

    // mMusicActiveMs is the cumulative time of music activity since safe volume was disabled.
    // When this time reaches UNSAFE_VOLUME_MUSIC_ACTIVE_MS_MAX, the safe media volume is re-enabled
    // automatically. mMusicActiveMs is rounded to a multiple of MUSIC_ACTIVE_POLL_PERIOD_MS.
    private int mMusicActiveMs;
    private long mLastMusicActiveTimeMs = 0;
    private PendingIntent mMusicActiveIntent = null;
    private final AlarmManager mAlarmManager;

    @NonNull private final AudioService mAudioService;
    @NonNull private final SettingsAdapter mSettings;
    @NonNull private final AudioHandler mAudioHandler;
    @NonNull private final ISafeHearingVolumeController mVolumeController;

    private final boolean mEnableCsd;

    private final Object mCsdStateLock = new Object();

    private final AtomicReference<ISoundDose> mSoundDose = new AtomicReference<>();

    @GuardedBy("mCsdStateLock")
    private float mCurrentCsd = 0.f;
    // dose at which the next dose reached warning occurs
    @GuardedBy("mCsdStateLock")
    private float mNextCsdWarning = 1.0f;
    @GuardedBy("mCsdStateLock")
    private final List<SoundDoseRecord> mDoseRecords = new ArrayList<>();

    // time in seconds reported by System.currentTimeInMillis used as an offset to convert between
    // boot time and global time
    @GuardedBy("mCsdStateLock")
    private long mGlobalTimeOffsetInSecs = GLOBAL_TIME_OFFSET_UNINITIALIZED;

    private final Context mContext;

    private final ISoundDoseCallback.Stub mSoundDoseCallback = new ISoundDoseCallback.Stub() {
        public void onMomentaryExposure(float currentMel, int deviceId) {
            Log.w(TAG, "DeviceId " + deviceId + " triggered momentary exposure with value: "
                    + currentMel);
            mLogger.enqueue(SoundDoseEvent.getMomentaryExposureEvent(currentMel));
            if (mEnableCsd) {
                mVolumeController.postDisplayCsdWarning(
                        AudioManager.CSD_WARNING_MOMENTARY_EXPOSURE,
                        getTimeoutMsForWarning(AudioManager.CSD_WARNING_MOMENTARY_EXPOSURE));
            }
        }

        public void onNewCsdValue(float currentCsd, SoundDoseRecord[] records) {
            if (!mEnableCsd) {
                Log.w(TAG, "onNewCsdValue: csd not supported, ignoring value");
                return;
            }

            Log.i(TAG, "onNewCsdValue: " + currentCsd);
            synchronized (mCsdStateLock) {
                if (mCurrentCsd < currentCsd) {
                    // dose increase: going over next threshold?
                    if ((mCurrentCsd < mNextCsdWarning) && (currentCsd >= mNextCsdWarning)) {
                        if (mNextCsdWarning == 5.0f) {
                            // 500% dose repeat
                            mVolumeController.postDisplayCsdWarning(
                                    AudioManager.CSD_WARNING_DOSE_REPEATED_5X,
                                    getTimeoutMsForWarning(
                                            AudioManager.CSD_WARNING_DOSE_REPEATED_5X));
                            // on the 5x dose warning, the volume reduction happens right away
                            mAudioService.postLowerVolumeToRs1();
                        } else {
                            mVolumeController.postDisplayCsdWarning(
                                    AudioManager.CSD_WARNING_DOSE_REACHED_1X,
                                    getTimeoutMsForWarning(
                                            AudioManager.CSD_WARNING_DOSE_REACHED_1X));
                        }
                        mNextCsdWarning += 1.0f;
                    }
                } else {
                    // dose decrease: dropping below previous threshold of warning?
                    if ((currentCsd < mNextCsdWarning - 1.0f) && (
                            mNextCsdWarning >= 2.0f)) {
                        mNextCsdWarning -= 1.0f;
                    }
                }
                mCurrentCsd = currentCsd;
                updateSoundDoseRecords_l(records, currentCsd);
            }
        }
    };

    SoundDoseHelper(@NonNull AudioService audioService, Context context,
            @NonNull AudioHandler audioHandler,
            @NonNull SettingsAdapter settings,
            @NonNull ISafeHearingVolumeController volumeController) {
        mAudioService = audioService;
        mAudioHandler = audioHandler;
        mSettings = settings;
        mVolumeController = volumeController;

        mContext = context;

        mEnableCsd = mContext.getResources().getBoolean(R.bool.config_audio_csd_enabled_default);
        if (mEnableCsd) {
            mSafeMediaVolumeState = SAFE_MEDIA_VOLUME_DISABLED;
        } else {
            mSafeMediaVolumeState = mSettings.getGlobalInt(audioService.getContentResolver(),
                    Settings.Global.AUDIO_SAFE_VOLUME_STATE, SAFE_MEDIA_VOLUME_NOT_CONFIGURED);
        }

        initCsd();

        // The default safe volume index read here will be replaced by the actual value when
        // the mcc is read by onConfigureSafeMedia()
        // For now we use the same index for RS2 initial warning with CSD
        mSafeMediaVolumeIndex = mContext.getResources().getInteger(
                R.integer.config_safe_media_volume_index) * 10;

        mAlarmManager = (AlarmManager) mContext.getSystemService(
                Context.ALARM_SERVICE);
    }

    float getRs2Value() {
        if (!mEnableCsd) {
            return 0.f;
        }

        final ISoundDose soundDose = mSoundDose.get();
        if (soundDose == null) {
            Log.w(TAG, "Sound dose interface not initialized");
            return 0.f;
        }

        try {
            return soundDose.getOutputRs2();
        } catch (RemoteException e) {
            Log.e(TAG, "Exception while getting the RS2 exposure value", e);
            return 0.f;
        }
    }

    void setRs2Value(float rs2Value) {
        if (!mEnableCsd) {
            return;
        }

        final ISoundDose soundDose = mSoundDose.get();
        if (soundDose == null) {
            Log.w(TAG, "Sound dose interface not initialized");
            return;
        }

        try {
            soundDose.setOutputRs2(rs2Value);
        } catch (RemoteException e) {
            Log.e(TAG, "Exception while setting the RS2 exposure value", e);
        }
    }

    float getCsd() {
        if (!mEnableCsd) {
            return -1.f;
        }

        final ISoundDose soundDose = mSoundDose.get();
        if (soundDose == null) {
            Log.w(TAG, "Sound dose interface not initialized");
            return -1.f;
        }

        try {
            return soundDose.getCsd();
        } catch (RemoteException e) {
            Log.e(TAG, "Exception while getting the CSD value", e);
            return -1.f;
        }
    }

    void setCsd(float csd) {
        if (!mEnableCsd) {
            return;
        }

        final ISoundDose soundDose = mSoundDose.get();
        if (soundDose == null) {
            Log.w(TAG, "Sound dose interface not initialized");
            return;
        }

        try {
            final SoundDoseRecord record = new SoundDoseRecord();
            record.timestamp = System.currentTimeMillis();
            record.value = csd;
            final SoundDoseRecord[] recordArray = new SoundDoseRecord[] { record };
            soundDose.resetCsd(csd, recordArray);
        } catch (RemoteException e) {
            Log.e(TAG, "Exception while setting the CSD value", e);
        }
    }

    void forceUseFrameworkMel(boolean useFrameworkMel) {
        if (!mEnableCsd) {
            return;
        }

        final ISoundDose soundDose = mSoundDose.get();
        if (soundDose == null) {
            Log.w(TAG, "Sound dose interface not initialized");
            return;
        }

        try {
            soundDose.forceUseFrameworkMel(useFrameworkMel);
        } catch (RemoteException e) {
            Log.e(TAG, "Exception while forcing the internal MEL computation", e);
        }
    }

    void forceComputeCsdOnAllDevices(boolean computeCsdOnAllDevices) {
        if (!mEnableCsd) {
            return;
        }

        final ISoundDose soundDose = mSoundDose.get();
        if (soundDose == null) {
            Log.w(TAG, "Sound dose interface not initialized");
            return;
        }

        try {
            soundDose.forceComputeCsdOnAllDevices(computeCsdOnAllDevices);
        } catch (RemoteException e) {
            Log.e(TAG, "Exception while forcing CSD computation on all devices", e);
        }
    }

    boolean isCsdEnabled() {
        return mEnableCsd;
    }

    /*package*/ int safeMediaVolumeIndex(int device) {
        if (!mSafeMediaVolumeDevices.contains(device)) {
            return MAX_STREAM_VOLUME[AudioSystem.STREAM_MUSIC];
        }
        if (device == AudioSystem.DEVICE_OUT_USB_HEADSET) {
            return mSafeUsbMediaVolumeIndex;
        } else {
            return mSafeMediaVolumeIndex;
        }
    }

    /*package*/ void restoreMusicActiveMs() {
        synchronized (mSafeMediaVolumeStateLock) {
            mMusicActiveMs = MathUtils.constrain(
                    mSettings.getSecureIntForUser(mAudioService.getContentResolver(),
                            Settings.Secure.UNSAFE_VOLUME_MUSIC_ACTIVE_MS, 0,
                            UserHandle.USER_CURRENT),
                    0, UNSAFE_VOLUME_MUSIC_ACTIVE_MS_MAX);
        }
    }

    /*package*/ void enforceSafeMediaVolumeIfActive(String caller) {
        synchronized (mSafeMediaVolumeStateLock) {
            if (mSafeMediaVolumeState == SAFE_MEDIA_VOLUME_ACTIVE) {
                enforceSafeMediaVolume(caller);
            }
        }
    }

    /*package*/ void enforceSafeMediaVolume(String caller) {
        AudioService.VolumeStreamState streamState = mAudioService.getVssVolumeForStream(
                AudioSystem.STREAM_MUSIC);
        Set<Integer> devices = mSafeMediaVolumeDevices;

        for (int device : devices) {
            int index = streamState.getIndex(device);
            int safeIndex = safeMediaVolumeIndex(device);
            if (index > safeIndex) {
                streamState.setIndex(safeIndex, device, caller, true /*hasModifyAudioSettings*/);
                mAudioHandler.sendMessageAtTime(
                        mAudioHandler.obtainMessage(MSG_SET_DEVICE_VOLUME, device, /*arg2=*/0,
                                streamState), /*delay=*/0);
            }
        }
    }

    /*package*/ boolean checkSafeMediaVolume(int streamType, int index, int device) {
        boolean result;
        synchronized (mSafeMediaVolumeStateLock) {
            result = checkSafeMediaVolume_l(streamType, index, device);
        }
        return result;
    }

    @GuardedBy("mSafeMediaVolumeStateLock")
    private boolean checkSafeMediaVolume_l(int streamType, int index, int device) {
        return (mSafeMediaVolumeState != SAFE_MEDIA_VOLUME_ACTIVE)
                    || (AudioService.mStreamVolumeAlias[streamType] != AudioSystem.STREAM_MUSIC)
                    || (!mSafeMediaVolumeDevices.contains(device))
                    || (index <= safeMediaVolumeIndex(device))
                    || mEnableCsd;
    }

    /*package*/ boolean willDisplayWarningAfterCheckVolume(int streamType, int index, int device,
            int flags) {
        synchronized (mSafeMediaVolumeStateLock) {
            if (!checkSafeMediaVolume_l(streamType, index, device)) {
                mVolumeController.postDisplaySafeVolumeWarning(flags);
                mPendingVolumeCommand = new StreamVolumeCommand(
                        streamType, index, flags, device);
                return true;
            }
        }
        return false;
    }

    /*package*/ void disableSafeMediaVolume(String callingPackage) {
        synchronized (mSafeMediaVolumeStateLock) {
            final long identity = Binder.clearCallingIdentity();
            setSafeMediaVolumeEnabled(false, callingPackage);
            Binder.restoreCallingIdentity(identity);

            if (mPendingVolumeCommand != null) {
                mAudioService.onSetStreamVolume(mPendingVolumeCommand.mStreamType,
                        mPendingVolumeCommand.mIndex,
                        mPendingVolumeCommand.mFlags,
                        mPendingVolumeCommand.mDevice,
                        callingPackage, true /*hasModifyAudioSettings*/,
                        true /*canChangeMute*/);
                mPendingVolumeCommand = null;
            }
        }
    }

    /*package*/ void scheduleMusicActiveCheck() {
        synchronized (mSafeMediaVolumeStateLock) {
            cancelMusicActiveCheck();
            if (!mEnableCsd) {
                mMusicActiveIntent = PendingIntent.getBroadcast(mContext,
                        REQUEST_CODE_CHECK_MUSIC_ACTIVE,
                        new Intent(ACTION_CHECK_MUSIC_ACTIVE),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                mAlarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime()
                                + MUSIC_ACTIVE_POLL_PERIOD_MS, mMusicActiveIntent);
            }
        }
    }

    /*package*/ void onCheckMusicActive(String caller, boolean isStreamActive) {
        synchronized (mSafeMediaVolumeStateLock) {
            if (mSafeMediaVolumeState == SAFE_MEDIA_VOLUME_INACTIVE) {
                int device = mAudioService.getDeviceForStream(AudioSystem.STREAM_MUSIC);
                if (mSafeMediaVolumeDevices.contains(device) && isStreamActive) {
                    scheduleMusicActiveCheck();
                    int index = mAudioService.getVssVolumeForDevice(AudioSystem.STREAM_MUSIC,
                            device);
                    if (index > safeMediaVolumeIndex(device)) {
                        // Approximate cumulative active music time
                        long curTimeMs = SystemClock.elapsedRealtime();
                        if (mLastMusicActiveTimeMs != 0) {
                            mMusicActiveMs += (int) (curTimeMs - mLastMusicActiveTimeMs);
                        }
                        mLastMusicActiveTimeMs = curTimeMs;
                        Log.i(TAG, "onCheckMusicActive() mMusicActiveMs: " + mMusicActiveMs);
                        if (mMusicActiveMs > UNSAFE_VOLUME_MUSIC_ACTIVE_MS_MAX) {
                            setSafeMediaVolumeEnabled(true, caller);
                            mMusicActiveMs = 0;
                        }
                        saveMusicActiveMs();
                    }
                } else {
                    cancelMusicActiveCheck();
                    mLastMusicActiveTimeMs = 0;
                }
            }
        }
    }

    /*package*/ void configureSafeMedia(boolean forced, String caller) {
        int msg = MSG_CONFIGURE_SAFE_MEDIA;
        mAudioHandler.removeMessages(msg);

        long time = 0;
        if (forced) {
            time = (SystemClock.uptimeMillis() + (SystemProperties.getBoolean(
                    "audio.safemedia.bypass", false) ? 0 : SAFE_VOLUME_CONFIGURE_TIMEOUT_MS));
        }
        mAudioHandler.sendMessageAtTime(
                mAudioHandler.obtainMessage(msg, /*arg1=*/forced ? 1 : 0, /*arg2=*/0, caller),
                time);
    }

    /*package*/ void initSafeUsbMediaVolumeIndex() {
        // mSafeUsbMediaVolumeIndex must be initialized after createStreamStates() because it
        // relies on audio policy having correct ranges for volume indexes.
        mSafeUsbMediaVolumeIndex = getSafeUsbMediaVolumeIndex();
    }

    /*package*/ int getSafeMediaVolumeIndex(int device) {
        if (mSafeMediaVolumeState == SAFE_MEDIA_VOLUME_ACTIVE && mSafeMediaVolumeDevices.contains(
                device)) {
            return safeMediaVolumeIndex(device);
        } else {
            return -1;
        }
    }

    /*package*/ boolean raiseVolumeDisplaySafeMediaVolume(int streamType, int index, int device,
            int flags) {
        if (checkSafeMediaVolume(streamType, index, device)) {
            return false;
        }

        mVolumeController.postDisplaySafeVolumeWarning(flags);
        return true;
    }

    /*package*/ boolean safeDevicesContains(int device) {
        return mSafeMediaVolumeDevices.contains(device);
    }

    /*package*/ void invalidatPendingVolumeCommand() {
        synchronized (mSafeMediaVolumeStateLock) {
            mPendingVolumeCommand = null;
        }
    }

    /*package*/ void handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_CONFIGURE_SAFE_MEDIA:
                onConfigureSafeMedia((msg.arg1 == 1), (String) msg.obj);
                break;
            case MSG_PERSIST_SAFE_VOLUME_STATE:
                onPersistSafeVolumeState(msg.arg1);
                break;
            case MSG_PERSIST_MUSIC_ACTIVE_MS:
                final int musicActiveMs = msg.arg1;
                mSettings.putSecureIntForUser(mAudioService.getContentResolver(),
                        Settings.Secure.UNSAFE_VOLUME_MUSIC_ACTIVE_MS, musicActiveMs,
                        UserHandle.USER_CURRENT);
                break;
            case MSG_PERSIST_CSD_VALUES:
                onPersistSoundDoseRecords();
                break;
            case MSG_CSD_UPDATE_ATTENUATION:
                final int device = msg.arg1;
                final boolean isAbsoluteVolume = (msg.arg2 == 1);
                final AudioService.VolumeStreamState streamState =
                        (AudioService.VolumeStreamState) msg.obj;

                updateDoseAttenuation(streamState.getIndex(device), device,
                        streamState.getStreamType(), isAbsoluteVolume);
                break;
            default:
                Log.e(TAG, "Unexpected msg to handle: " + msg.what);
                break;
        }
    }

    /*package*/ void dump(PrintWriter pw) {
        pw.print("  mEnableCsd="); pw.println(mEnableCsd);
        pw.print("  mSafeMediaVolumeState=");
        pw.println(safeMediaVolumeStateToString(mSafeMediaVolumeState));
        pw.print("  mSafeMediaVolumeIndex="); pw.println(mSafeMediaVolumeIndex);
        pw.print("  mSafeUsbMediaVolumeIndex="); pw.println(mSafeUsbMediaVolumeIndex);
        pw.print("  mSafeUsbMediaVolumeDbfs="); pw.println(mSafeUsbMediaVolumeDbfs);
        pw.print("  mMusicActiveMs="); pw.println(mMusicActiveMs);
        pw.print("  mMcc="); pw.println(mMcc);
        pw.print("  mPendingVolumeCommand="); pw.println(mPendingVolumeCommand);
        pw.println();
        mLogger.dump(pw);
        pw.println();
    }

    /*package*/void reset() {
        Log.d(TAG, "Reset the sound dose helper");
        mSoundDose.set(AudioSystem.getSoundDoseInterface(mSoundDoseCallback));

        synchronized (mCsdStateLock) {
            try {
                final ISoundDose soundDose = mSoundDose.get();
                if (soundDose != null && soundDose.asBinder().isBinderAlive()) {
                    if (mCurrentCsd != 0.f) {
                        Log.d(TAG,
                                "Resetting the saved sound dose value " + mCurrentCsd);
                        SoundDoseRecord[] records = mDoseRecords.toArray(
                                new SoundDoseRecord[0]);
                        soundDose.resetCsd(mCurrentCsd, records);
                    }
                }
            } catch (RemoteException e) {
                // noop
            }
        }
    }

    private void updateDoseAttenuation(int newIndex, int device, int streamType,
            boolean isAbsoluteVolume) {
        if (!mEnableCsd) {
            return;
        }

        final ISoundDose soundDose = mSoundDose.get();
        if (soundDose == null) {
            Log.w(TAG,  "Can not apply attenuation. ISoundDose itf is null.");
            return;
        }

        try {
            if (!isAbsoluteVolume) {
                // remove any possible previous attenuation
                soundDose.updateAttenuation(/* attenuationDB= */0.f, device);
                return;
            }

            if (AudioService.mStreamVolumeAlias[streamType] == AudioSystem.STREAM_MUSIC
                    && mSafeMediaCsdDevices.contains(device)) {
                soundDose.updateAttenuation(
                        AudioSystem.getStreamVolumeDB(AudioSystem.STREAM_MUSIC,
                                (newIndex + 5) / 10,
                                device), device);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Could not apply the attenuation for MEL calculation with volume index "
                    + newIndex, e);
        }
    }

    private void initCsd() {
        if (!mEnableCsd) {
            return;
        }

        Log.v(TAG, "Initializing sound dose");

        synchronized (mCsdStateLock) {
            if (mGlobalTimeOffsetInSecs == GLOBAL_TIME_OFFSET_UNINITIALIZED) {
                mGlobalTimeOffsetInSecs = System.currentTimeMillis() / 1000L;
            }

            float prevCsd = mCurrentCsd;
            // Restore persisted values
            mCurrentCsd = parseGlobalSettingFloat(
                    Settings.Global.AUDIO_SAFE_CSD_CURRENT_VALUE, /* defaultValue= */0.f);
            if (mCurrentCsd != prevCsd) {
                mNextCsdWarning = parseGlobalSettingFloat(
                        Settings.Global.AUDIO_SAFE_CSD_NEXT_WARNING, /* defaultValue= */1.f);
                final List<SoundDoseRecord> records = persistedStringToRecordList(
                        mSettings.getGlobalString(mAudioService.getContentResolver(),
                                Settings.Global.AUDIO_SAFE_CSD_DOSE_RECORDS),
                        mGlobalTimeOffsetInSecs);
                if (records != null) {
                    mDoseRecords.addAll(records);
                }
            }
        }

        reset();
    }

    private void onConfigureSafeMedia(boolean force, String caller) {
        synchronized (mSafeMediaVolumeStateLock) {
            int mcc = mContext.getResources().getConfiguration().mcc;
            if ((mMcc != mcc) || ((mMcc == 0) && force)) {
                mSafeMediaVolumeIndex = mContext.getResources().getInteger(
                        com.android.internal.R.integer.config_safe_media_volume_index) * 10;

                mSafeUsbMediaVolumeIndex = getSafeUsbMediaVolumeIndex();

                boolean safeMediaVolumeEnabled =
                        SystemProperties.getBoolean("audio.safemedia.force", false)
                                || mContext.getResources().getBoolean(
                                com.android.internal.R.bool.config_safe_media_volume_enabled);

                boolean safeMediaVolumeBypass =
                        SystemProperties.getBoolean("audio.safemedia.bypass", false);

                // The persisted state is either "disabled" or "active": this is the state applied
                // next time we boot and cannot be "inactive"
                int persistedState;
                if (safeMediaVolumeEnabled && !safeMediaVolumeBypass && !mEnableCsd) {
                    persistedState = SAFE_MEDIA_VOLUME_ACTIVE;
                    // The state can already be "inactive" here if the user has forced it before
                    // the 30 seconds timeout for forced configuration. In this case we don't reset
                    // it to "active".
                    if (mSafeMediaVolumeState != SAFE_MEDIA_VOLUME_INACTIVE) {
                        if (mMusicActiveMs == 0) {
                            mSafeMediaVolumeState = SAFE_MEDIA_VOLUME_ACTIVE;
                            enforceSafeMediaVolume(caller);
                        } else {
                            // We have existing playback time recorded, already confirmed.
                            mSafeMediaVolumeState = SAFE_MEDIA_VOLUME_INACTIVE;
                            mLastMusicActiveTimeMs = 0;
                        }
                    }
                } else {
                    persistedState = SAFE_MEDIA_VOLUME_DISABLED;
                    mSafeMediaVolumeState = SAFE_MEDIA_VOLUME_DISABLED;
                }
                mMcc = mcc;
                mAudioHandler.sendMessageAtTime(
                        mAudioHandler.obtainMessage(MSG_PERSIST_SAFE_VOLUME_STATE,
                                persistedState, /*arg2=*/0,
                                /*obj=*/null), /*delay=*/0);
            }
        }
    }

    private int getTimeoutMsForWarning(@AudioManager.CsdWarning int csdWarning) {
        switch (csdWarning) {
            case AudioManager.CSD_WARNING_DOSE_REACHED_1X:
                return CSD_WARNING_TIMEOUT_MS_DOSE_1X;
            case AudioManager.CSD_WARNING_DOSE_REPEATED_5X:
                return CSD_WARNING_TIMEOUT_MS_DOSE_5X;
            case AudioManager.CSD_WARNING_MOMENTARY_EXPOSURE:
                return CSD_WARNING_TIMEOUT_MS_MOMENTARY_EXPOSURE;
            case AudioManager.CSD_WARNING_ACCUMULATION_START:
                return CSD_WARNING_TIMEOUT_MS_ACCUMULATION_START;
        }
        Log.e(TAG, "Invalid CSD warning " + csdWarning, new Exception());
        return -1;
    }

    @GuardedBy("mSafeMediaVolumeStateLock")
    private void setSafeMediaVolumeEnabled(boolean on, String caller) {
        if ((mSafeMediaVolumeState != SAFE_MEDIA_VOLUME_NOT_CONFIGURED) && (mSafeMediaVolumeState
                != SAFE_MEDIA_VOLUME_DISABLED)) {
            if (on && (mSafeMediaVolumeState == SAFE_MEDIA_VOLUME_INACTIVE)) {
                mSafeMediaVolumeState = SAFE_MEDIA_VOLUME_ACTIVE;
                enforceSafeMediaVolume(caller);
            } else if (!on && (mSafeMediaVolumeState == SAFE_MEDIA_VOLUME_ACTIVE)) {
                mSafeMediaVolumeState = SAFE_MEDIA_VOLUME_INACTIVE;
                mMusicActiveMs = 1;  // nonzero = confirmed
                mLastMusicActiveTimeMs = 0;
                saveMusicActiveMs();
                scheduleMusicActiveCheck();
            }
        }
    }

    @GuardedBy("mSafeMediaVolumeStateLock")
    private void cancelMusicActiveCheck() {
        if (mMusicActiveIntent != null) {
            mAlarmManager.cancel(mMusicActiveIntent);
            mMusicActiveIntent = null;
        }
    }

    @GuardedBy("mSafeMediaVolumeStateLock")
    private void saveMusicActiveMs() {
        mAudioHandler.obtainMessage(MSG_PERSIST_MUSIC_ACTIVE_MS, mMusicActiveMs, 0).sendToTarget();
    }

    private int getSafeUsbMediaVolumeIndex() {
        // determine UI volume index corresponding to the wanted safe gain in dBFS
        int min = MIN_STREAM_VOLUME[AudioSystem.STREAM_MUSIC];
        int max = MAX_STREAM_VOLUME[AudioSystem.STREAM_MUSIC];

        mSafeUsbMediaVolumeDbfs = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_safe_media_volume_usb_mB) / 100.0f;

        while (Math.abs(max - min) > 1) {
            int index = (max + min) / 2;
            float gainDB = AudioSystem.getStreamVolumeDB(
                    AudioSystem.STREAM_MUSIC, index, AudioSystem.DEVICE_OUT_USB_HEADSET);
            if (Float.isNaN(gainDB)) {
                //keep last min in case of read error
                break;
            } else if (gainDB == mSafeUsbMediaVolumeDbfs) {
                min = index;
                break;
            } else if (gainDB < mSafeUsbMediaVolumeDbfs) {
                min = index;
            } else {
                max = index;
            }
        }
        return min * 10;
    }

    private void onPersistSafeVolumeState(int state) {
        mSettings.putGlobalInt(mAudioService.getContentResolver(),
                Settings.Global.AUDIO_SAFE_VOLUME_STATE,
                state);
    }

    private static String safeMediaVolumeStateToString(int state) {
        switch(state) {
            case SAFE_MEDIA_VOLUME_NOT_CONFIGURED: return "SAFE_MEDIA_VOLUME_NOT_CONFIGURED";
            case SAFE_MEDIA_VOLUME_DISABLED: return "SAFE_MEDIA_VOLUME_DISABLED";
            case SAFE_MEDIA_VOLUME_INACTIVE: return "SAFE_MEDIA_VOLUME_INACTIVE";
            case SAFE_MEDIA_VOLUME_ACTIVE: return "SAFE_MEDIA_VOLUME_ACTIVE";
        }
        return null;
    }

    @GuardedBy("mCsdStateLock")
    private void updateSoundDoseRecords_l(SoundDoseRecord[] newRecords, float currentCsd) {
        long totalDuration = 0;
        for (SoundDoseRecord record : newRecords) {
            Log.i(TAG, "  new record: " + record);
            totalDuration += record.duration;

            if (record.value < 0) {
                // Negative value means the record timestamp exceeded the CSD rolling window size
                // and needs to be removed
                if (!mDoseRecords.removeIf(
                        r -> r.value == -record.value && r.timestamp == record.timestamp
                                && r.averageMel == record.averageMel
                                && r.duration == record.duration)) {
                    Log.w(TAG, "Could not find cached record to remove: " + record);
                }
            } else {
                mDoseRecords.add(record);
            }
        }

        mAudioHandler.sendMessageAtTime(mAudioHandler.obtainMessage(MSG_PERSIST_CSD_VALUES,
                /* arg1= */0, /* arg2= */0, /* obj= */null), /* delay= */0);

        mLogger.enqueue(SoundDoseEvent.getDoseUpdateEvent(currentCsd, totalDuration));
    }

    @SuppressWarnings("GuardedBy")  // avoid limitation with intra-procedural analysis of lambdas
    private void onPersistSoundDoseRecords() {
        synchronized (mCsdStateLock) {
            if (mGlobalTimeOffsetInSecs == GLOBAL_TIME_OFFSET_UNINITIALIZED) {
                mGlobalTimeOffsetInSecs = System.currentTimeMillis() / 1000L;
            }

            mSettings.putGlobalString(mAudioService.getContentResolver(),
                    Settings.Global.AUDIO_SAFE_CSD_CURRENT_VALUE,
                    Float.toString(mCurrentCsd));
            mSettings.putGlobalString(mAudioService.getContentResolver(),
                    Settings.Global.AUDIO_SAFE_CSD_NEXT_WARNING,
                    Float.toString(mNextCsdWarning));
            mSettings.putGlobalString(mAudioService.getContentResolver(),
                    Settings.Global.AUDIO_SAFE_CSD_DOSE_RECORDS,
                    mDoseRecords.stream().map(
                            record -> SoundDoseHelper.recordToPersistedString(record,
                                    mGlobalTimeOffsetInSecs)).collect(
                            Collectors.joining(PERSIST_CSD_RECORD_SEPARATOR_CHAR)));
        }
    }

    private static String recordToPersistedString(SoundDoseRecord record,
            long globalTimeOffsetInSecs) {
        return convertToGlobalTime(record.timestamp, globalTimeOffsetInSecs)
                + PERSIST_CSD_RECORD_FIELD_SEPARATOR + record.duration
                + PERSIST_CSD_RECORD_FIELD_SEPARATOR + record.value
                + PERSIST_CSD_RECORD_FIELD_SEPARATOR + record.averageMel;
    }

    private static long convertToGlobalTime(long bootTimeInSecs, long globalTimeOffsetInSecs) {
        return bootTimeInSecs + globalTimeOffsetInSecs;
    }

    private static long convertToBootTime(long globalTimeInSecs, long globalTimeOffsetInSecs) {
        return globalTimeInSecs - globalTimeOffsetInSecs;
    }

    private static List<SoundDoseRecord> persistedStringToRecordList(String records,
            long globalTimeOffsetInSecs) {
        if (records == null || records.isEmpty()) {
            return null;
        }
        return Arrays.stream(TextUtils.split(records, PERSIST_CSD_RECORD_SEPARATOR)).map(
                record -> SoundDoseHelper.persistedStringToRecord(record,
                        globalTimeOffsetInSecs)).filter(Objects::nonNull).collect(
                Collectors.toList());
    }

    private static SoundDoseRecord persistedStringToRecord(String record,
            long globalTimeOffsetInSecs) {
        if (record == null || record.isEmpty()) {
            return null;
        }
        final String[] fields = TextUtils.split(record, PERSIST_CSD_RECORD_FIELD_SEPARATOR);
        if (fields.length != 4) {
            Log.w(TAG, "Expecting 4 fields for a SoundDoseRecord, parsed " + fields.length);
            return null;
        }

        final SoundDoseRecord sdRecord = new SoundDoseRecord();
        try {
            sdRecord.timestamp = convertToBootTime(Long.parseLong(fields[0]),
                    globalTimeOffsetInSecs);
            sdRecord.duration = Integer.parseInt(fields[1]);
            sdRecord.value = Float.parseFloat(fields[2]);
            sdRecord.averageMel = Float.parseFloat(fields[3]);
        } catch (NumberFormatException e) {
            Log.e(TAG, "Unable to parse persisted SoundDoseRecord: " + record, e);
            return null;
        }

        return sdRecord;
    }

    private float parseGlobalSettingFloat(String audioSafeCsdCurrentValue, float defaultValue) {
        String stringValue = mSettings.getGlobalString(mAudioService.getContentResolver(),
                audioSafeCsdCurrentValue);
        if (stringValue == null || stringValue.isEmpty()) {
            Log.v(TAG, "No value stored in settings " + audioSafeCsdCurrentValue);
            return defaultValue;
        }

        float value;
        try {
            value = Float.parseFloat(stringValue);
        } catch (NumberFormatException e) {
            Log.e(TAG, "Error parsing float from settings " + audioSafeCsdCurrentValue, e);
            value = defaultValue;
        }

        return value;
    }

    // StreamVolumeCommand contains the information needed to defer the process of
    // setStreamVolume() in case the user has to acknowledge the safe volume warning message.
    private static class StreamVolumeCommand {
        public final int mStreamType;
        public final int mIndex;
        public final int mFlags;
        public final int mDevice;

        StreamVolumeCommand(int streamType, int index, int flags, int device) {
            mStreamType = streamType;
            mIndex = index;
            mFlags = flags;
            mDevice = device;
        }

        @Override
        public String toString() {
            return new StringBuilder().append("{streamType=").append(mStreamType).append(",index=")
                    .append(mIndex).append(",flags=").append(mFlags).append(",device=")
                    .append(mDevice).append('}').toString();
        }
    }
}
