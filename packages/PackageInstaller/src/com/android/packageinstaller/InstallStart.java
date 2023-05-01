/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.android.packageinstaller;

import static com.android.packageinstaller.PackageUtil.getMaxTargetSdkVersionForUid;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;

/**
 * Select which activity is the first visible activity of the installation and forward the intent to
 * it.
 */
public class InstallStart extends Activity {
    private static final String LOG_TAG = InstallStart.class.getSimpleName();

    private static final String DOWNLOADS_AUTHORITY = "downloads";
    private PackageManager mPackageManager;
    private boolean mAbortInstall = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPackageManager = getPackageManager();
        Intent intent = getIntent();
        String callingPackage = getCallingPackage();
        String callingAttributionTag = null;

        final boolean isSessionInstall =
                PackageInstaller.ACTION_CONFIRM_PRE_APPROVAL.equals(intent.getAction())
                        || PackageInstaller.ACTION_CONFIRM_INSTALL.equals(intent.getAction());

        // If the activity was started via a PackageInstaller session, we retrieve the calling
        // package from that session
        final int sessionId = (isSessionInstall
                ? intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
                : -1);
        if (callingPackage == null && sessionId != -1) {
            PackageInstaller packageInstaller = getPackageManager().getPackageInstaller();
            PackageInstaller.SessionInfo sessionInfo = packageInstaller.getSessionInfo(sessionId);
            callingPackage = (sessionInfo != null) ? sessionInfo.getInstallerPackageName() : null;
            callingAttributionTag =
                    (sessionInfo != null) ? sessionInfo.getInstallerAttributionTag() : null;
        }

        final ApplicationInfo sourceInfo = getSourceInfo(callingPackage);
        // Uid of the source package, coming from ActivityManager
        int callingUid = getLaunchedFromUid();
        if (callingUid == Process.INVALID_UID) {
            // Cannot reach ActivityManager. Aborting install.
            Log.e(LOG_TAG, "Could not determine the launching uid.");
        }
        // Uid of the source package, with a preference to uid from ApplicationInfo
        final int originatingUid = sourceInfo != null ? sourceInfo.uid : callingUid;

        if (callingUid == Process.INVALID_UID && sourceInfo == null) {
            mAbortInstall = true;
        }

        boolean isDocumentsManager = checkPermission(Manifest.permission.MANAGE_DOCUMENTS,
                -1, callingUid) == PackageManager.PERMISSION_GRANTED;
        boolean isTrustedSource = false;
        if (sourceInfo != null && sourceInfo.isPrivilegedApp()) {
            isTrustedSource = intent.getBooleanExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, false) || (
                    originatingUid != Process.INVALID_UID && checkPermission(
                            Manifest.permission.INSTALL_PACKAGES, -1 /* pid */, originatingUid)
                            == PackageManager.PERMISSION_GRANTED);
        }

        if (!isTrustedSource && !isSystemDownloadsProvider(callingUid) && !isDocumentsManager
                && originatingUid != Process.INVALID_UID) {
            final int targetSdkVersion = getMaxTargetSdkVersionForUid(this, originatingUid);
            if (targetSdkVersion < 0) {
                Log.w(LOG_TAG, "Cannot get target sdk version for uid " + originatingUid);
                // Invalid originating uid supplied. Abort install.
                mAbortInstall = true;
            } else if (targetSdkVersion >= Build.VERSION_CODES.O && !isUidRequestingPermission(
                    originatingUid, Manifest.permission.REQUEST_INSTALL_PACKAGES)) {
                Log.e(LOG_TAG, "Requesting uid " + originatingUid + " needs to declare permission "
                        + Manifest.permission.REQUEST_INSTALL_PACKAGES);
                mAbortInstall = true;
            }
        }

        if (sessionId != -1 && !isCallerSessionOwner(originatingUid, sessionId)) {
            mAbortInstall = true;
        }

        final String installerPackageNameFromIntent = getIntent().getStringExtra(
                Intent.EXTRA_INSTALLER_PACKAGE_NAME);
        if (installerPackageNameFromIntent != null) {
            final String callingPkgName = getLaunchedFromPackage();
            if (!TextUtils.equals(installerPackageNameFromIntent, callingPkgName)
                    && mPackageManager.checkPermission(Manifest.permission.INSTALL_PACKAGES,
                    callingPkgName) != PackageManager.PERMISSION_GRANTED) {
                Log.e(LOG_TAG, "The given installer package name " + installerPackageNameFromIntent
                        + " is invalid. Remove it.");
                EventLog.writeEvent(0x534e4554, "236687884", getLaunchedFromUid(),
                        "Invalid EXTRA_INSTALLER_PACKAGE_NAME");
                getIntent().removeExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME);
            }
        }

        if (mAbortInstall) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        Intent nextActivity = new Intent(intent);
        nextActivity.setFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT
                | Intent.FLAG_GRANT_READ_URI_PERMISSION);

        // The the installation source as the nextActivity thinks this activity is the source, hence
        // set the originating UID and sourceInfo explicitly
        nextActivity.putExtra(PackageInstallerActivity.EXTRA_CALLING_PACKAGE, callingPackage);
        nextActivity.putExtra(PackageInstallerActivity.EXTRA_CALLING_ATTRIBUTION_TAG,
                callingAttributionTag);
        nextActivity.putExtra(PackageInstallerActivity.EXTRA_ORIGINAL_SOURCE_INFO, sourceInfo);
        nextActivity.putExtra(Intent.EXTRA_ORIGINATING_UID, originatingUid);

        if (isSessionInstall) {
            nextActivity.setClass(this, PackageInstallerActivity.class);
        } else {
            Uri packageUri = intent.getData();

            if (packageUri != null
                    && packageUri.getScheme().equals(ContentResolver.SCHEME_CONTENT)
                    && canPackageQuery(callingUid, packageUri)) {
                // [IMPORTANT] This path is deprecated, but should still work. Only necessary
                // features should be added.

                // Stage a session with this file to prevent it from being changed underneath
                // this process.
                nextActivity.setClass(this, InstallStaging.class);
            } else if (packageUri != null && PackageInstallerActivity.SCHEME_PACKAGE.equals(
                    packageUri.getScheme())) {
                nextActivity.setClass(this, PackageInstallerActivity.class);
            } else {
                Intent result = new Intent();
                result.putExtra(Intent.EXTRA_INSTALL_RESULT,
                        PackageManager.INSTALL_FAILED_INVALID_URI);
                setResult(RESULT_FIRST_USER, result);

                nextActivity = null;
            }
        }

        if (nextActivity != null) {
            try {
                startActivity(nextActivity);
            } catch (SecurityException e) {
                Intent result = new Intent();
                result.putExtra(Intent.EXTRA_INSTALL_RESULT,
                        PackageManager.INSTALL_FAILED_INVALID_URI);
                setResult(RESULT_FIRST_USER, result);
            }
        }
        finish();
    }

    private boolean isUidRequestingPermission(int uid, String permission) {
        final String[] packageNames = mPackageManager.getPackagesForUid(uid);
        if (packageNames == null) {
            return false;
        }
        for (final String packageName : packageNames) {
            final PackageInfo packageInfo;
            try {
                packageInfo = mPackageManager.getPackageInfo(packageName,
                        PackageManager.GET_PERMISSIONS);
            } catch (PackageManager.NameNotFoundException e) {
                // Ignore and try the next package
                continue;
            }
            if (packageInfo.requestedPermissions != null
                    && Arrays.asList(packageInfo.requestedPermissions).contains(permission)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the ApplicationInfo for the installation source (the calling package), if available
     */
    private ApplicationInfo getSourceInfo(@Nullable String callingPackage) {
        if (callingPackage != null) {
            try {
                return getPackageManager().getApplicationInfo(callingPackage, 0);
            } catch (PackageManager.NameNotFoundException ex) {
                // ignore
            }
        }
        return null;
    }

    private boolean isSystemDownloadsProvider(int uid) {
        final ProviderInfo downloadProviderPackage = getPackageManager().resolveContentProvider(
                DOWNLOADS_AUTHORITY, 0);
        if (downloadProviderPackage == null) {
            // There seems to be no currently enabled downloads provider on the system.
            return false;
        }
        final ApplicationInfo appInfo = downloadProviderPackage.applicationInfo;
        return ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                && uid == appInfo.uid);
    }

    @NonNull
    private boolean canPackageQuery(int callingUid, Uri packageUri) {
        ProviderInfo info = mPackageManager.resolveContentProvider(packageUri.getAuthority(),
                PackageManager.ComponentInfoFlags.of(0));
        if (info == null) {
            return false;
        }
        String targetPackage = info.packageName;

        String[] callingPackages = mPackageManager.getPackagesForUid(callingUid);
        if (callingPackages == null) {
            return false;
        }
        for (String callingPackage: callingPackages) {
            try {
                if (mPackageManager.canPackageQuery(callingPackage, targetPackage)) {
                    return true;
                }
            } catch (PackageManager.NameNotFoundException e) {
                // no-op
            }
        }
        return false;
    }

    private boolean isCallerSessionOwner(int originatingUid, int sessionId) {
        PackageInstaller packageInstaller = getPackageManager().getPackageInstaller();
        int installerUid = packageInstaller.getSessionInfo(sessionId).getInstallerUid();
        return (originatingUid == Process.ROOT_UID) || (originatingUid == installerUid);
    }
}
