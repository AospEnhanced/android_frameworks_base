/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.os;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SuppressAutoDoc;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.UserHandleAware;
import android.annotation.WorkerThread;
import android.app.ActivityManager;
import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import com.android.internal.R;
import com.android.internal.util.Preconditions;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.Executor;

/**
 * Class that provides a privileged API to capture and consume bugreports.
 *
 * <p>This class may only be used by apps that currently have carrier privileges (see {@link
 * android.telephony.TelephonyManager#hasCarrierPrivileges}) on an active SIM or priv-apps
 * explicitly allowed by the device manufacturer.
 *
 * <p>Only one bugreport can be generated by the system at a time.
 */
@SystemService(Context.BUGREPORT_SERVICE)
public final class BugreportManager {

    private static final String TAG = "BugreportManager";

    private final Context mContext;
    private final IDumpstate mBinder;

    /** @hide */
    public BugreportManager(@NonNull Context context, IDumpstate binder) {
        mContext = context;
        mBinder = binder;
    }

    /**
     * An interface describing the callback for bugreport progress and status.
     *
     * <p>Callers will receive {@link #onProgress} calls as the bugreport progresses, followed by a
     * terminal call to either {@link #onFinished} or {@link #onError}.
     *
     * <p>If an issue is encountered while starting the bugreport asynchronously, callers will
     * receive an {@link #onError} call without any {@link #onProgress} callbacks.
     */
    public abstract static class BugreportCallback {
        /**
         * Possible error codes taking a bugreport can encounter.
         *
         * @hide
         */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(
                prefix = {"BUGREPORT_ERROR_"},
                value = {
                    BUGREPORT_ERROR_INVALID_INPUT,
                    BUGREPORT_ERROR_RUNTIME,
                    BUGREPORT_ERROR_USER_DENIED_CONSENT,
                    BUGREPORT_ERROR_USER_CONSENT_TIMED_OUT,
                    BUGREPORT_ERROR_ANOTHER_REPORT_IN_PROGRESS,
                    BUGREPORT_ERROR_NO_BUGREPORT_TO_RETRIEVE
                })
        public @interface BugreportErrorCode {}

        /**
         * The input options were invalid. For example, the destination file the app provided could
         * not be written by the system.
         */
        public static final int BUGREPORT_ERROR_INVALID_INPUT =
                IDumpstateListener.BUGREPORT_ERROR_INVALID_INPUT;

        /** A runtime error occurred. */
        public static final int BUGREPORT_ERROR_RUNTIME =
                IDumpstateListener.BUGREPORT_ERROR_RUNTIME_ERROR;

        /** User denied consent to share the bugreport. */
        public static final int BUGREPORT_ERROR_USER_DENIED_CONSENT =
                IDumpstateListener.BUGREPORT_ERROR_USER_DENIED_CONSENT;

        /** The request to get user consent timed out. */
        public static final int BUGREPORT_ERROR_USER_CONSENT_TIMED_OUT =
                IDumpstateListener.BUGREPORT_ERROR_USER_CONSENT_TIMED_OUT;

        /** There is currently a bugreport running. The caller should try again later. */
        public static final int BUGREPORT_ERROR_ANOTHER_REPORT_IN_PROGRESS =
                IDumpstateListener.BUGREPORT_ERROR_ANOTHER_REPORT_IN_PROGRESS;

        /** There is no bugreport to retrieve for the caller. */
        public static final int BUGREPORT_ERROR_NO_BUGREPORT_TO_RETRIEVE =
                IDumpstateListener.BUGREPORT_ERROR_NO_BUGREPORT_TO_RETRIEVE;

        /**
         * Called when there is a progress update.
         *
         * @param progress the progress in [0.0, 100.0]
         */
        public void onProgress(@FloatRange(from = 0f, to = 100f) float progress) {}

        /**
         * Called when taking bugreport resulted in an error.
         *
         * <p>If {@code BUGREPORT_ERROR_USER_DENIED_CONSENT} is passed, then the user did not
         * consent to sharing the bugreport with the calling app.
         *
         * <p>If {@code BUGREPORT_ERROR_USER_CONSENT_TIMED_OUT} is passed, then the consent timed
         * out, but the bugreport could be available in the internal directory of dumpstate for
         * manual retrieval.
         *
         * <p>If {@code BUGREPORT_ERROR_ANOTHER_REPORT_IN_PROGRESS} is passed, then the caller
         * should try later, as only one bugreport can be in progress at a time.
         */
        public void onError(@BugreportErrorCode int errorCode) {}

        /**
         * Called when taking bugreport finishes successfully.
         *
         * <p>This callback will be invoked if the
         * {@code BugreportParams#BUGREPORT_FLAG_DEFER_CONSENT} flag is not set.
         */
        public void onFinished() {}

        /** Called when taking bugreport finishes successfully.
         *
         * <p>This callback will only be invoked if the
         * {@link BugreportParams#BUGREPORT_FLAG_DEFER_CONSENT} flag is set. Otherwise, the
         * {@link #onFinished()} callback will be invoked.
         *
         * @param bugreportFile the absolute path of the generated bugreport file.
         * @hide

         */
        @SystemApi
        public void onFinished(@NonNull String bugreportFile) {}

        /**
         * Called when it is ready for calling app to show UI, showing any extra UI before this
         * callback can interfere with bugreport generation.
         */
        public void onEarlyReportFinished() {}
    }

    /**
     * Speculatively pre-dumps UI data for a bugreport request that might come later.
     *
     * <p>Triggers the dump of certain critical UI data, e.g. traces stored in short
     * ring buffers that might get lost by the time the actual bugreport is requested.
     *
     * <p>{@link #startBugreport} will then pick the pre-dumped data if both of the following
     * conditions are met:
     * - {@link android.os.BugreportParams#BUGREPORT_FLAG_USE_PREDUMPED_UI_DATA} is specified.
     * - {@link #preDumpUiData} and {@link #startBugreport} were called by the same UID.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.DUMP)
    @WorkerThread
    public void preDumpUiData() {
        try {
            mBinder.preDumpUiData(mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Starts a bugreport.
     *
     * <p>This starts a bugreport in the background. However the call itself can take several
     * seconds to return in the worst case. {@code callback} will receive progress and status
     * updates.
     *
     * <p>The bugreport artifacts will be copied over to the given file descriptors only if the user
     * consents to sharing with the calling app. If
     * {@link BugreportParams#BUGREPORT_FLAG_DEFER_CONSENT} is set, user consent will be deferred
     * and no files will be copied to the given file descriptors.
     *
     * <p>{@link BugreportManager} takes ownership of {@code bugreportFd} and {@code screenshotFd}.
     *
     * @param bugreportFd file to write the bugreport. This should be opened in write-only, append
     *     mode.
     * @param screenshotFd file to write the screenshot, if necessary. This should be opened in
     *     write-only, append mode.
     * @param params options that specify what kind of a bugreport should be taken
     * @param callback callback for progress and status updates
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.DUMP)
    @WorkerThread
    public void startBugreport(
            @NonNull ParcelFileDescriptor bugreportFd,
            @Nullable ParcelFileDescriptor screenshotFd,
            @NonNull BugreportParams params,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull BugreportCallback callback) {
        try {
            Preconditions.checkNotNull(bugreportFd);
            Preconditions.checkNotNull(params);
            Preconditions.checkNotNull(executor);
            Preconditions.checkNotNull(callback);

            boolean deferConsent =
                    (params.getFlags() & BugreportParams.BUGREPORT_FLAG_DEFER_CONSENT) != 0;
            boolean isScreenshotRequested = screenshotFd != null || deferConsent;
            if (screenshotFd == null) {
                // Binder needs a valid File Descriptor to be passed
                screenshotFd =
                        ParcelFileDescriptor.open(
                                new File("/dev/null"), ParcelFileDescriptor.MODE_READ_ONLY);
            }
            DumpstateListener dsListener =
                    new DumpstateListener(executor, callback, isScreenshotRequested, deferConsent);
            // Note: mBinder can get callingUid from the binder transaction.
            mBinder.startBugreport(
                    -1 /* callingUid */,
                    mContext.getOpPackageName(),
                    bugreportFd.getFileDescriptor(),
                    screenshotFd.getFileDescriptor(),
                    params.getMode(),
                    params.getFlags(),
                    dsListener,
                    isScreenshotRequested);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (FileNotFoundException e) {
            Log.wtf(TAG, "Not able to find /dev/null file: ", e);
        } finally {
            // We can close the file descriptors here because binder would have duped them.
            IoUtils.closeQuietly(bugreportFd);
            if (screenshotFd != null) {
                IoUtils.closeQuietly(screenshotFd);
            }
        }
    }

    /**
     * Retrieves a previously generated bugreport.
     *
     * <p>The previously generated bugreport must have been generated by calling {@link
     * #startBugreport(ParcelFileDescriptor, ParcelFileDescriptor, BugreportParams,
     * Executor, BugreportCallback)} with the {@link BugreportParams#BUGREPORT_FLAG_DEFER_CONSENT}
     * flag set. The bugreport file returned by the {@link BugreportCallback#onFinished(String)}
     * callback for a previously generated bugreport must be passed to this method. A caller may
     * only retrieve bugreports that they have previously requested.
     *
     * <p>The bugreport artifacts will be copied over to the given file descriptor only if the user
     * consents to sharing with the calling app.
     *
     * <p>{@link BugreportManager} takes ownership of {@code bugreportFd}.
     *
     * <p>The caller can reattempt to retrieve the bugreport multiple times if the user has not
     * consented on previous attempts.
     *
     * @param bugreportFile the identifier for a bugreport that was previously generated for this
     *      caller using {@code startBugreport}.
     * @param bugreportFd file to copy over the previous bugreport. This should be opened in
     *      write-only, append mode.
     * @param executor the executor to execute callback methods.
     * @param callback callback for progress and status updates.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.DUMP)
    @WorkerThread
    @UserHandleAware
    public void retrieveBugreport(
            @NonNull String bugreportFile,
            @NonNull ParcelFileDescriptor bugreportFd,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull BugreportCallback callback
    ) {
        try {
            Preconditions.checkNotNull(bugreportFile);
            Preconditions.checkNotNull(bugreportFd);
            Preconditions.checkNotNull(executor);
            Preconditions.checkNotNull(callback);
            DumpstateListener dsListener = new DumpstateListener(executor, callback, false, false);
            mBinder.retrieveBugreport(Binder.getCallingUid(), mContext.getOpPackageName(),
                    mContext.getUserId(),
                    bugreportFd.getFileDescriptor(),
                    bugreportFile,
                    /* keepBugreportOnRetrieval = */ false,
                    dsListener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } finally {
            IoUtils.closeQuietly(bugreportFd);
        }
    }

    /**
     * Starts a connectivity bugreport.
     *
     * <p>The connectivity bugreport is a specialized version of bugreport that only includes
     * information specifically for debugging connectivity-related issues (e.g. telephony, wi-fi,
     * and IP networking issues). It is intended primarily for use by OEMs and network providers
     * such as mobile network operators. In addition to generally excluding information that isn't
     * targeted to connectivity debugging, this type of bugreport excludes PII and sensitive
     * information that isn't strictly necessary for connectivity debugging.
     *
     * <p>The calling app MUST have a context-specific reason for requesting a connectivity
     * bugreport, such as detecting a connectivity-related issue. This API SHALL NOT be used to
     * perform random sampling from a fleet of public end-user devices.
     *
     * <p>Calling this API will cause the system to ask the user for consent every single time. The
     * bugreport artifacts will be copied over to the given file descriptors only if the user
     * consents to sharing with the calling app.
     *
     * <p>This starts a bugreport in the background. However the call itself can take several
     * seconds to return in the worst case. {@code callback} will receive progress and status
     * updates.
     *
     * <p>Requires that the calling app has carrier privileges (see {@link
     * android.telephony.TelephonyManager#hasCarrierPrivileges}) on any active subscription.
     *
     * @param bugreportFd file to write the bugreport. This should be opened in write-only, append
     *     mode.
     * @param callback callback for progress and status updates.
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @WorkerThread
    public void startConnectivityBugreport(
            @NonNull ParcelFileDescriptor bugreportFd,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull BugreportCallback callback) {
        startBugreport(
                bugreportFd,
                null /* screenshotFd */,
                new BugreportParams(BugreportParams.BUGREPORT_MODE_TELEPHONY),
                executor,
                callback);
    }

    /**
     * Cancels the currently running bugreport.
     *
     * <p>Apps are only able to cancel their own bugreports. App A cannot cancel a bugreport started
     * by app B.
     *
     * <p>Requires permission: {@link android.Manifest.permission#DUMP} or that the calling app has
     * carrier privileges (see {@link android.telephony.TelephonyManager#hasCarrierPrivileges}) on
     * any active subscription.
     *
     * @throws SecurityException if trying to cancel another app's bugreport in progress
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @WorkerThread
    public void cancelBugreport() {
        try {
            mBinder.cancelBugreport(-1 /* callingUid */, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Requests a bugreport.
     *
     * <p>This requests the platform/system to take a bugreport and makes the final bugreport
     * available to the user. The user may choose to share it with another app, but the bugreport is
     * never given back directly to the app that requested it.
     *
     * @param params {@link BugreportParams} that specify what kind of a bugreport should be taken,
     *     please note that not all kinds of bugreport allow for a progress notification
     * @param shareTitle title on the final share notification
     * @param shareDescription description on the final share notification
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.DUMP)
    public void requestBugreport(
            @NonNull BugreportParams params,
            @Nullable CharSequence shareTitle,
            @Nullable CharSequence shareDescription) {
        try {
            String title = shareTitle == null ? null : shareTitle.toString();
            String description = shareDescription == null ? null : shareDescription.toString();
            ActivityManager.getService()
                    .requestBugReportWithDescription(title, description, params.getMode());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private final class DumpstateListener extends IDumpstateListener.Stub {
        private final Executor mExecutor;
        private final BugreportCallback mCallback;
        private final boolean mIsScreenshotRequested;
        private final boolean mIsConsentDeferred;

        DumpstateListener(
                Executor executor, BugreportCallback callback, boolean isScreenshotRequested,
                boolean isConsentDeferred) {
            mExecutor = executor;
            mCallback = callback;
            mIsScreenshotRequested = isScreenshotRequested;
            mIsConsentDeferred = isConsentDeferred;
        }

        @Override
        public void onProgress(int progress) throws RemoteException {
            final long identity = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mCallback.onProgress(progress));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void onError(int errorCode) throws RemoteException {
            final long identity = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mCallback.onError(errorCode));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void onFinished(String bugreportFile) throws RemoteException {
            final long identity = Binder.clearCallingIdentity();
            try {
                if (mIsConsentDeferred) {
                    mExecutor.execute(() -> mCallback.onFinished(bugreportFile));
                } else {
                    mExecutor.execute(() -> mCallback.onFinished());
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void onScreenshotTaken(boolean success) throws RemoteException {
            if (!mIsScreenshotRequested) {
                return;
            }

            Handler mainThreadHandler = new Handler(Looper.getMainLooper());
            mainThreadHandler.post(
                    () -> {
                        int message =
                                success
                                        ? R.string.bugreport_screenshot_success_toast
                                        : R.string.bugreport_screenshot_failure_toast;
                        Toast.makeText(mContext, message, Toast.LENGTH_LONG).show();
                    });
        }

        @Override
        public void onUiIntensiveBugreportDumpsFinished() throws RemoteException {
            final long identity = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mCallback.onEarlyReportFinished());
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }
}
