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

package com.android.server.biometrics.sensors;

import android.annotation.NonNull;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.biometrics.BiometricManager;
import android.hardware.face.FaceEnrollOptions;
import android.hardware.fingerprint.FingerprintEnrollOptions;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.R;

/**
 * Biometric notification helper class.
 */
public class BiometricNotificationUtils {

    private static final String TAG = "BiometricNotificationUtils";
    private static final String FACE_RE_ENROLL_NOTIFICATION_TAG = "FaceReEnroll";
    private static final String BAD_CALIBRATION_NOTIFICATION_TAG = "FingerprintBadCalibration";
    private static final String KEY_RE_ENROLL_FACE = "re_enroll_face_unlock";
    private static final String FACE_SETTINGS_ACTION = "android.settings.FACE_SETTINGS";
    private static final String FACE_ENROLL_ACTION = "android.settings.FACE_ENROLL";
    private static final String FINGERPRINT_ENROLL_ACTION = "android.settings.FINGERPRINT_ENROLL";
    private static final String FINGERPRINT_SETTINGS_ACTION =
            "android.settings.FINGERPRINT_SETTINGS";
    private static final String SETTINGS_PACKAGE = "com.android.settings";
    private static final String FACE_ENROLL_CHANNEL = "FaceEnrollNotificationChannel";
    private static final String FACE_RE_ENROLL_CHANNEL = "FaceReEnrollNotificationChannel";
    private static final String FINGERPRINT_ENROLL_CHANNEL = "FingerprintEnrollNotificationChannel";
    private static final String FINGERPRINT_BAD_CALIBRATION_CHANNEL =
            "FingerprintBadCalibrationNotificationChannel";
    private static final long NOTIFICATION_INTERVAL_MS = 24 * 60 * 60 * 1000;
    private static long sLastAlertTime = 0;
    private static final String ACTION_BIOMETRIC_FRR_DISMISS = "action_biometric_frr_dismiss";
    // Dismissal action for FRR notification.
    private static final Intent DISMISS_FRR_INTENT = new Intent(ACTION_BIOMETRIC_FRR_DISMISS);

    public static final int NOTIFICATION_ID = 1;
    public static final String FACE_ENROLL_NOTIFICATION_TAG = "FaceEnroll";
    public static final String FINGERPRINT_ENROLL_NOTIFICATION_TAG = "FingerprintEnroll";
    /**
     * Shows a face re-enrollment notification.
     */
    public static void showReEnrollmentNotification(@NonNull Context context) {
        final NotificationManager notificationManager =
                context.getSystemService(NotificationManager.class);

        final String name =
                context.getString(R.string.face_recalibrate_notification_name);
        final String title =
                context.getString(R.string.face_recalibrate_notification_title);
        final String content =
                context.getString(R.string.face_recalibrate_notification_content);

        final Intent intent = new Intent(FACE_SETTINGS_ACTION);
        intent.setPackage(SETTINGS_PACKAGE);

        final PendingIntent pendingIntent = PendingIntent.getActivityAsUser(context,
                0 /* requestCode */, intent, PendingIntent.FLAG_IMMUTABLE /* flags */,
                null /* options */, UserHandle.CURRENT);

        showNotificationHelper(context, name, title, content, pendingIntent, FACE_RE_ENROLL_CHANNEL,
                Notification.CATEGORY_SYSTEM, FACE_RE_ENROLL_NOTIFICATION_TAG,
                Notification.VISIBILITY_SECRET, false);
    }

    /**
     * Shows a face enrollment notification.
     */
    public static void showFaceEnrollNotification(@NonNull Context context) {
        Slog.d(TAG, "Showing Face Enroll Notification");

        final String name =
                context.getString(R.string.device_unlock_notification_name);
        final String title =
                context.getString(R.string.alternative_unlock_setup_notification_title);
        final String content =
                context.getString(R.string.alternative_face_setup_notification_content);

        final Intent intent = new Intent(FACE_ENROLL_ACTION);
        intent.setPackage(SETTINGS_PACKAGE);
        intent.putExtra(BiometricManager.EXTRA_ENROLL_REASON,
                FaceEnrollOptions.ENROLL_REASON_RE_ENROLL_NOTIFICATION);

        final PendingIntent pendingIntent = PendingIntent.getActivityAsUser(context,
                0 /* requestCode */, intent, PendingIntent.FLAG_IMMUTABLE /* flags */,
                null /* options */, UserHandle.CURRENT);

        showNotificationHelper(context, name, title, content, pendingIntent, FACE_ENROLL_CHANNEL,
                Notification.CATEGORY_RECOMMENDATION, FACE_ENROLL_NOTIFICATION_TAG,
                Notification.VISIBILITY_PUBLIC, true);

    }

    /**
     * Shows a fingerprint enrollment notification.
     */
    public static void showFingerprintEnrollNotification(@NonNull Context context) {
        Slog.d(TAG, "Showing Fingerprint Enroll Notification");
        final String name =
                context.getString(R.string.device_unlock_notification_name);
        final String title =
                context.getString(R.string.alternative_unlock_setup_notification_title);
        final String content =
                context.getString(R.string.alternative_fp_setup_notification_content);

        final Intent intent = new Intent(FINGERPRINT_ENROLL_ACTION);
        intent.setPackage(SETTINGS_PACKAGE);
        intent.putExtra(BiometricManager.EXTRA_ENROLL_REASON,
                FingerprintEnrollOptions.ENROLL_REASON_RE_ENROLL_NOTIFICATION);

        final PendingIntent pendingIntent = PendingIntent.getActivityAsUser(context,
                0 /* requestCode */, intent, PendingIntent.FLAG_IMMUTABLE /* flags */,
                null /* options */, UserHandle.CURRENT);

        showNotificationHelper(context, name, title, content, pendingIntent,
                Notification.CATEGORY_RECOMMENDATION, FINGERPRINT_ENROLL_CHANNEL,
                FINGERPRINT_ENROLL_NOTIFICATION_TAG, Notification.VISIBILITY_PUBLIC, true);

    }

    /**
     * Shows a fingerprint bad calibration notification.
     */
    public static void showBadCalibrationNotification(@NonNull Context context) {
        final long currentTime = SystemClock.elapsedRealtime();
        final long timeSinceLastAlert = currentTime - sLastAlertTime;

        // Only show the notification if not previously shown or a day has
        // passed since the last notification.
        if (sLastAlertTime != 0 && (timeSinceLastAlert < NOTIFICATION_INTERVAL_MS)) {
            Slog.v(TAG, "Skipping calibration notification : " + timeSinceLastAlert);
            return;
        }

        sLastAlertTime = currentTime;

        final String name =
                context.getString(R.string.fingerprint_recalibrate_notification_name);
        final String title =
                context.getString(R.string.fingerprint_recalibrate_notification_title);
        final String content =
                context.getString(R.string.fingerprint_recalibrate_notification_content);

        final Intent intent = new Intent(FINGERPRINT_SETTINGS_ACTION);
        intent.setPackage(SETTINGS_PACKAGE);

        final PendingIntent pendingIntent = PendingIntent.getActivityAsUser(context,
                0 /* requestCode */, intent, PendingIntent.FLAG_IMMUTABLE /* flags */,
                null /* options */, UserHandle.CURRENT);

        showNotificationHelper(context, name, title, content, pendingIntent,
                Notification.CATEGORY_SYSTEM, FINGERPRINT_BAD_CALIBRATION_CHANNEL,
                BAD_CALIBRATION_NOTIFICATION_TAG, Notification.VISIBILITY_SECRET, false);
    }

    private static void showNotificationHelper(Context context, String name, String title,
                String content, PendingIntent pendingIntent, String category,
                String channelName, String notificationTag, int visibility,
                boolean listenToDismissEvent) {
        Slog.v(TAG," listenToDismissEvent = " + listenToDismissEvent);
        final PendingIntent dismissIntent = PendingIntent.getActivityAsUser(context,
                0 /* requestCode */, DISMISS_FRR_INTENT, PendingIntent.FLAG_IMMUTABLE /* flags */,
                null /* options */, UserHandle.CURRENT);
        final NotificationManager notificationManager =
                context.getSystemService(NotificationManager.class);
        final NotificationChannel channel = new NotificationChannel(channelName, name,
                NotificationManager.IMPORTANCE_HIGH);
        final Notification.Builder builder = new Notification.Builder(context, channelName)
                .setSmallIcon(R.drawable.ic_lock)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(new Notification.BigTextStyle().bigText(content))
                .setSubText(name)
                .setOnlyAlertOnce(true)
                .setLocalOnly(true)
                .setAutoCancel(true)
                .setCategory(category)
                .setContentIntent(pendingIntent)
                .setVisibility(visibility);

        if (listenToDismissEvent) {
            builder.setDeleteIntent(dismissIntent);
        }
        final Notification notification = builder.build();

        notificationManager.createNotificationChannel(channel);
        notificationManager.notifyAsUser(notificationTag, NOTIFICATION_ID, notification,
                UserHandle.CURRENT);

    }

    /**
     * Cancels a face re-enrollment notification
     */
    public static void cancelFaceReEnrollNotification(@NonNull Context context) {
        final NotificationManager notificationManager =
                context.getSystemService(NotificationManager.class);
        notificationManager.cancelAsUser(FACE_RE_ENROLL_NOTIFICATION_TAG, NOTIFICATION_ID,
                UserHandle.CURRENT);
    }

    /**
     * Cancels a face enrollment notification
     */
    public static void cancelFaceEnrollNotification(@NonNull Context context) {
        final NotificationManager notificationManager =
                context.getSystemService(NotificationManager.class);
        notificationManager.cancelAsUser(FACE_ENROLL_NOTIFICATION_TAG, NOTIFICATION_ID,
                UserHandle.CURRENT);
    }

    /**
     * Cancels a fingerprint enrollment notification
     */
    public static void cancelFingerprintEnrollNotification(@NonNull Context context) {
        final NotificationManager notificationManager =
                context.getSystemService(NotificationManager.class);
        notificationManager.cancelAsUser(FINGERPRINT_ENROLL_NOTIFICATION_TAG, NOTIFICATION_ID,
                UserHandle.CURRENT);
    }

    /**
     * Cancels a fingerprint bad calibration notification
     */
    public static void cancelBadCalibrationNotification(@NonNull Context context) {
        final NotificationManager notificationManager =
                context.getSystemService(NotificationManager.class);
        notificationManager.cancelAsUser(BAD_CALIBRATION_NOTIFICATION_TAG, NOTIFICATION_ID,
                UserHandle.CURRENT);
    }

}
