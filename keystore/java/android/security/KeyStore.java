/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.security;

import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.Application;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.security.keymaster.ExportResult;
import android.security.keymaster.KeyCharacteristics;
import android.security.keymaster.KeymasterArguments;
import android.security.keymaster.KeymasterBlob;
import android.security.keymaster.KeymasterCertificateChain;
import android.security.keymaster.KeymasterDefs;
import android.security.keymaster.OperationResult;
import android.security.keystore.KeyExpiredException;
import android.security.keystore.KeyNotYetValidException;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.StrongBoxUnavailableException;
import android.security.keystore.UserNotAuthenticatedException;
import android.util.Log;

import java.math.BigInteger;
import java.security.InvalidKeyException;
import java.util.List;
import java.util.Locale;

/**
 * @hide This should not be made public in its present form because it
 * assumes that private and secret key bytes are available and would
 * preclude the use of hardware crypto.
 */
public class KeyStore {
    private static final String TAG = "KeyStore";

    // ResponseCodes - see system/security/keystore/include/keystore/keystore.h
    public static final int NO_ERROR = 1;
    public static final int LOCKED = 2;
    public static final int UNINITIALIZED = 3;
    public static final int SYSTEM_ERROR = 4;
    public static final int PROTOCOL_ERROR = 5;
    public static final int PERMISSION_DENIED = 6;
    public static final int KEY_NOT_FOUND = 7;
    public static final int VALUE_CORRUPTED = 8;
    public static final int UNDEFINED_ACTION = 9;
    public static final int WRONG_PASSWORD = 10;
    public static final int CANNOT_ATTEST_IDS = -66;
    public static final int HARDWARE_TYPE_UNAVAILABLE = -68;

    /**
     * Per operation authentication is needed before this operation is valid.
     * This is returned from {@link #begin} when begin succeeds but the operation uses
     * per-operation authentication and must authenticate before calling {@link #update} or
     * {@link #finish}.
     */
    public static final int OP_AUTH_NEEDED = 15;

    // Used for UID field to indicate the calling UID.
    public static final int UID_SELF = -1;

    // Flags for "put" "import" and "generate"
    public static final int FLAG_NONE = 0;

    /**
     * Indicates that this key (or key pair) must be encrypted at rest. This will protect the key
     * (or key pair) with the secure lock screen credential (e.g., password, PIN, or pattern).
     *
     * <p>Note that this requires that the secure lock screen (e.g., password, PIN, pattern) is set
     * up, otherwise key (or key pair) generation or import will fail. Moreover, this key (or key
     * pair) will be deleted when the secure lock screen is disabled or reset (e.g., by the user or
     * a Device Administrator). Finally, this key (or key pair) cannot be used until the user
     * unlocks the secure lock screen after boot.
     *
     * @see KeyguardManager#isDeviceSecure()
     */
    public static final int FLAG_ENCRYPTED = 1;

    /**
     * Select Software keymaster device, which as of this writing is the lowest security
     * level available on an android device. If neither FLAG_STRONGBOX nor FLAG_SOFTWARE is provided
     * A TEE based keymaster implementation is implied.
     *
     * Need to be in sync with KeyStoreFlag in system/security/keystore/include/keystore/keystore.h
     * For historical reasons this corresponds to the KEYSTORE_FLAG_FALLBACK flag.
     */
    public static final int FLAG_SOFTWARE = 1 << 1;

    /**
     * A private flag that's only available to system server to indicate that this key is part of
     * device encryption flow so it receives special treatment from keystore. For example this key
     * will not be super encrypted, and it will be stored separately under an unique UID instead
     * of the caller UID i.e. SYSTEM.
     *
     * Need to be in sync with KeyStoreFlag in system/security/keystore/include/keystore/keystore.h
     */
    public static final int FLAG_CRITICAL_TO_DEVICE_ENCRYPTION = 1 << 3;

    /**
     * Select Strongbox keymaster device, which as of this writing the the highest security level
     * available an android devices. If neither FLAG_STRONGBOX nor FLAG_SOFTWARE is provided
     * A TEE based keymaster implementation is implied.
     *
     * Need to be in sync with KeyStoreFlag in system/security/keystore/include/keystore/keystore.h
     */
    public static final int FLAG_STRONGBOX = 1 << 4;

    // States
    public enum State { UNLOCKED, LOCKED, UNINITIALIZED };

    private int mError = NO_ERROR;

    private final IKeystoreService mBinder;
    private final Context mContext;

    private IBinder mToken;

    private KeyStore(IKeystoreService binder) {
        mBinder = binder;
        mContext = getApplicationContext();
    }

    public static Context getApplicationContext() {
        Application application = ActivityThread.currentApplication();
        if (application == null) {
            throw new IllegalStateException(
                    "Failed to obtain application Context from ActivityThread");
        }
        return application;
    }

    public static KeyStore getInstance() {
        IKeystoreService keystore = IKeystoreService.Stub.asInterface(ServiceManager
                .getService("android.security.keystore"));
        return new KeyStore(keystore);
    }

    private synchronized IBinder getToken() {
        if (mToken == null) {
            mToken = new Binder();
        }
        return mToken;
    }

    public State state(int userId) {
        final int ret;
        try {
            ret = mBinder.getState(userId);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            throw new AssertionError(e);
        }

        switch (ret) {
            case NO_ERROR: return State.UNLOCKED;
            case LOCKED: return State.LOCKED;
            case UNINITIALIZED: return State.UNINITIALIZED;
            default: throw new AssertionError(mError);
        }
    }

    public State state() {
        return state(UserHandle.myUserId());
    }

    public boolean isUnlocked() {
        return state() == State.UNLOCKED;
    }

    public byte[] get(String key, int uid) {
        try {
            key = key != null ? key : "";
            return mBinder.get(key, uid);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return null;
        } catch (android.os.ServiceSpecificException e) {
            Log.w(TAG, "KeyStore exception", e);
            return null;
        }
    }

    public byte[] get(String key) {
        return get(key, UID_SELF);
    }

    public boolean put(String key, byte[] value, int uid, int flags) {
        return insert(key, value, uid, flags) == NO_ERROR;
    }

    public int insert(String key, byte[] value, int uid, int flags) {
        try {
            if (value == null) {
                value = new byte[0];
            }
            return mBinder.insert(key, value, uid, flags);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return SYSTEM_ERROR;
        }
    }

    public boolean delete(String key, int uid) {
        try {
            int ret = mBinder.del(key, uid);
            return (ret == NO_ERROR || ret == KEY_NOT_FOUND);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return false;
        }
    }

    public boolean delete(String key) {
        return delete(key, UID_SELF);
    }

    public boolean contains(String key, int uid) {
        try {
            return mBinder.exist(key, uid) == NO_ERROR;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return false;
        }
    }

    public boolean contains(String key) {
        return contains(key, UID_SELF);
    }

    /**
     * List all entries in the keystore for {@code uid} starting with {@code prefix}.
     */
    public String[] list(String prefix, int uid) {
        try {
            return mBinder.list(prefix, uid);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return null;
        } catch (android.os.ServiceSpecificException e) {
            Log.w(TAG, "KeyStore exception", e);
            return null;
        }
    }

    public String[] list(String prefix) {
        return list(prefix, UID_SELF);
    }

    public boolean reset() {
        try {
            return mBinder.reset() == NO_ERROR;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return false;
        }
    }

    /**
     * Attempt to lock the keystore for {@code user}.
     *
     * @param userId Android user to lock.
     * @return whether {@code user}'s keystore was locked.
     */
    public boolean lock(int userId) {
        try {
            return mBinder.lock(userId) == NO_ERROR;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return false;
        }
    }

    public boolean lock() {
        return lock(UserHandle.myUserId());
    }

    /**
     * Attempt to unlock the keystore for {@code user} with the password {@code password}.
     * This is required before keystore entries created with FLAG_ENCRYPTED can be accessed or
     * created.
     *
     * @param userId Android user ID to operate on
     * @param password user's keystore password. Should be the most recent value passed to
     * {@link #onUserPasswordChanged} for the user.
     *
     * @return whether the keystore was unlocked.
     */
    public boolean unlock(int userId, String password) {
        try {
            password = password != null ? password : "";
            mError = mBinder.unlock(userId, password);
            return mError == NO_ERROR;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return false;
        }
    }

    public boolean unlock(String password) {
        return unlock(UserHandle.getUserId(Process.myUid()), password);
    }

    /**
     * Check if the keystore for {@code userId} is empty.
     */
    public boolean isEmpty(int userId) {
        try {
            return mBinder.isEmpty(userId) != 0;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return false;
        }
    }

    public boolean isEmpty() {
        return isEmpty(UserHandle.myUserId());
    }

    public boolean generate(String key, int uid, int keyType, int keySize, int flags,
            byte[][] args) {
        try {
            return mBinder.generate(key, uid, keyType, keySize, flags,
                    new KeystoreArguments(args)) == NO_ERROR;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return false;
        }
    }

    public boolean importKey(String keyName, byte[] key, int uid, int flags) {
        try {
            return mBinder.import_key(keyName, key, uid, flags) == NO_ERROR;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return false;
        }
    }

    public byte[] sign(String key, byte[] data) {
        try {
            return mBinder.sign(key, data);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return null;
        } catch (android.os.ServiceSpecificException e) {
            Log.w(TAG, "KeyStore exception", e);
            return null;
        }

    }

    public boolean verify(String key, byte[] data, byte[] signature) {
        try {
            signature = signature != null ? signature : new byte[0];
            return mBinder.verify(key, data, signature) == NO_ERROR;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return false;
        } catch (android.os.ServiceSpecificException e) {
            Log.w(TAG, "KeyStore exception", e);
            return false;
        }

    }

    public String grant(String key, int uid) {
        try {
            String grantAlias =  mBinder.grant(key, uid);
            if (grantAlias == "") return null;
            return grantAlias;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return null;
        }
    }

    public boolean ungrant(String key, int uid) {
        try {
            return mBinder.ungrant(key, uid) == NO_ERROR;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return false;
        }
    }

    /**
     * Returns the last modification time of the key in milliseconds since the
     * epoch. Will return -1L if the key could not be found or other error.
     */
    public long getmtime(String key, int uid) {
        try {
            final long millis = mBinder.getmtime(key, uid);
            if (millis == -1L) {
                return -1L;
            }

            return millis * 1000L;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return -1L;
        }
    }

    public long getmtime(String key) {
        return getmtime(key, UID_SELF);
    }

    // TODO: remove this when it's removed from Settings
    public boolean isHardwareBacked() {
        return isHardwareBacked("RSA");
    }

    public boolean isHardwareBacked(String keyType) {
        try {
            return mBinder.is_hardware_backed(keyType.toUpperCase(Locale.US)) == NO_ERROR;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return false;
        }
    }

    public boolean clearUid(int uid) {
        try {
            return mBinder.clear_uid(uid) == NO_ERROR;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return false;
        }
    }

    public int getLastError() {
        return mError;
    }

    public boolean addRngEntropy(byte[] data, int flags) {
        try {
            return mBinder.addRngEntropy(data, flags) == NO_ERROR;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return false;
        }
    }

    public int generateKey(String alias, KeymasterArguments args, byte[] entropy, int uid,
            int flags, KeyCharacteristics outCharacteristics) {
        try {
            entropy = entropy != null ? entropy : new byte[0];
            args = args != null ? args : new KeymasterArguments();
            return mBinder.generateKey(alias, args, entropy, uid, flags, outCharacteristics);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return SYSTEM_ERROR;
        }
    }

    public int generateKey(String alias, KeymasterArguments args, byte[] entropy, int flags,
            KeyCharacteristics outCharacteristics) {
        return generateKey(alias, args, entropy, UID_SELF, flags, outCharacteristics);
    }

    public int getKeyCharacteristics(String alias, KeymasterBlob clientId, KeymasterBlob appId,
            int uid, KeyCharacteristics outCharacteristics) {
        try {
            clientId = clientId != null ? clientId : new KeymasterBlob(new byte[0]);
            appId = appId != null ? appId : new KeymasterBlob(new byte[0]);
            return mBinder.getKeyCharacteristics(alias, clientId, appId, uid, outCharacteristics);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return SYSTEM_ERROR;
        }
    }

    public int getKeyCharacteristics(String alias, KeymasterBlob clientId, KeymasterBlob appId,
            KeyCharacteristics outCharacteristics) {
        return getKeyCharacteristics(alias, clientId, appId, UID_SELF, outCharacteristics);
    }

    public int importKey(String alias, KeymasterArguments args, int format, byte[] keyData,
            int uid, int flags, KeyCharacteristics outCharacteristics) {
        try {
            return mBinder.importKey(alias, args, format, keyData, uid, flags,
                    outCharacteristics);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return SYSTEM_ERROR;
        }
    }

    public int importKey(String alias, KeymasterArguments args, int format, byte[] keyData,
            int flags, KeyCharacteristics outCharacteristics) {
        return importKey(alias, args, format, keyData, UID_SELF, flags, outCharacteristics);
    }

    public int importWrappedKey(String wrappedKeyAlias, byte[] wrappedKey,
            String wrappingKeyAlias,
            byte[] maskingKey, KeymasterArguments args, long rootSid, long fingerprintSid, int uid,
            KeyCharacteristics outCharacteristics) {
        try {
            return mBinder.importWrappedKey(wrappedKeyAlias, wrappedKey, wrappingKeyAlias,
                    maskingKey, args, rootSid, fingerprintSid, outCharacteristics);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return SYSTEM_ERROR;
        }
    }

    public ExportResult exportKey(String alias, int format, KeymasterBlob clientId,
            KeymasterBlob appId, int uid) {
        try {
            clientId = clientId != null ? clientId : new KeymasterBlob(new byte[0]);
            appId = appId != null ? appId : new KeymasterBlob(new byte[0]);
            return mBinder.exportKey(alias, format, clientId, appId, uid);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return null;
        }
    }
    public ExportResult exportKey(String alias, int format, KeymasterBlob clientId,
            KeymasterBlob appId) {
        return exportKey(alias, format, clientId, appId, UID_SELF);
    }

    public OperationResult begin(String alias, int purpose, boolean pruneable,
            KeymasterArguments args, byte[] entropy, int uid) {
        try {
            args = args != null ? args : new KeymasterArguments();
            entropy = entropy != null ? entropy : new byte[0];
            return mBinder.begin(getToken(), alias, purpose, pruneable, args, entropy, uid);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return null;
        }
    }

    public OperationResult begin(String alias, int purpose, boolean pruneable,
            KeymasterArguments args, byte[] entropy) {
        entropy = entropy != null ? entropy : new byte[0];
        args = args != null ? args : new KeymasterArguments();
        return begin(alias, purpose, pruneable, args, entropy, UID_SELF);
    }

    public OperationResult update(IBinder token, KeymasterArguments arguments, byte[] input) {
        try {
            arguments = arguments != null ? arguments : new KeymasterArguments();
            input = input != null ? input : new byte[0];
            return mBinder.update(token, arguments, input);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return null;
        }
    }

    public OperationResult finish(IBinder token, KeymasterArguments arguments, byte[] signature,
            byte[] entropy) {
        try {
            arguments = arguments != null ? arguments : new KeymasterArguments();
            entropy = entropy != null ? entropy : new byte[0];
            signature = signature != null ? signature : new byte[0];
            return mBinder.finish(token, arguments, signature, entropy);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return null;
        }
    }

    public OperationResult finish(IBinder token, KeymasterArguments arguments, byte[] signature) {
        return finish(token, arguments, signature, null);
    }

    public int abort(IBinder token) {
        try {
            return mBinder.abort(token);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return SYSTEM_ERROR;
        }
    }

    /**
     * Check if the operation referenced by {@code token} is currently authorized.
     *
     * @param token An operation token returned by a call to
     * {@link #begin(String, int, boolean, KeymasterArguments, byte[], KeymasterArguments) begin}.
     */
    public boolean isOperationAuthorized(IBinder token) {
        try {
            return mBinder.isOperationAuthorized(token);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return false;
        }
    }

    /**
     * Add an authentication record to the keystore authorization table.
     *
     * @param authToken The packed bytes of a hw_auth_token_t to be provided to keymaster.
     * @return {@code KeyStore.NO_ERROR} on success, otherwise an error value corresponding to
     * a {@code KeymasterDefs.KM_ERROR_} value or {@code KeyStore} ResponseCode.
     */
    public int addAuthToken(byte[] authToken) {
        try {
            return mBinder.addAuthToken(authToken);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return SYSTEM_ERROR;
        }
    }

    /**
     * Notify keystore that a user's password has changed.
     *
     * @param userId the user whose password changed.
     * @param newPassword the new password or "" if the password was removed.
     */
    public boolean onUserPasswordChanged(int userId, String newPassword) {
        // Parcel.cpp doesn't support deserializing null strings and treats them as "". Make that
        // explicit here.
        if (newPassword == null) {
            newPassword = "";
        }
        try {
            return mBinder.onUserPasswordChanged(userId, newPassword) == NO_ERROR;
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return false;
        }
    }

    /**
     * Notify keystore that a user was added.
     *
     * @param userId the new user.
     * @param parentId the parent of the new user, or -1 if the user has no parent. If parentId is
     * specified then the new user's keystore will be intialized with the same secure lockscreen
     * password as the parent.
     */
    public void onUserAdded(int userId, int parentId) {
        try {
            mBinder.onUserAdded(userId, parentId);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
        }
    }

    /**
     * Notify keystore that a user was added.
     *
     * @param userId the new user.
     */
    public void onUserAdded(int userId) {
        onUserAdded(userId, -1);
    }

    /**
     * Notify keystore that a user was removed.
     *
     * @param userId the removed user.
     */
    public void onUserRemoved(int userId) {
        try {
            mBinder.onUserRemoved(userId);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
        }
    }

    public boolean onUserPasswordChanged(String newPassword) {
        return onUserPasswordChanged(UserHandle.getUserId(Process.myUid()), newPassword);
    }

    /**
     * Notify keystore about the latest user locked state. This is to support keyguard-bound key.
     */
    public void onUserLockedStateChanged(int userHandle, boolean locked) {
        try {
            mBinder.onKeyguardVisibilityChanged(locked, userHandle);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to update user locked state " + userHandle, e);
        }
    }

    public int attestKey(
            String alias, KeymasterArguments params, KeymasterCertificateChain outChain) {
        // Prevent Google Play Services from using key attestation for SafetyNet
        if (mContext.getPackageName().equals("com.google.android.gms")) {
            return KeymasterDefs.KM_ERROR_UNIMPLEMENTED;
        }

        try {
            if (params == null) {
                params = new KeymasterArguments();
            }
            if (outChain == null) {
                outChain = new KeymasterCertificateChain();
            }
            return mBinder.attestKey(alias, params, outChain);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return SYSTEM_ERROR;
        }
    }

    public int attestDeviceIds(KeymasterArguments params, KeymasterCertificateChain outChain) {
        try {
            if (params == null) {
                params = new KeymasterArguments();
            }
            if (outChain == null) {
                outChain = new KeymasterCertificateChain();
            }
            return mBinder.attestDeviceIds(params, outChain);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return SYSTEM_ERROR;
        }
    }

    /**
     * Notify keystore that the device went off-body.
     */
    public void onDeviceOffBody() {
        try {
            mBinder.onDeviceOffBody();
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
        }
    }

    // Keep in sync with confirmationui/1.0/types.hal.
    public static final int CONFIRMATIONUI_OK = 0;
    public static final int CONFIRMATIONUI_CANCELED = 1;
    public static final int CONFIRMATIONUI_ABORTED = 2;
    public static final int CONFIRMATIONUI_OPERATION_PENDING = 3;
    public static final int CONFIRMATIONUI_IGNORED = 4;
    public static final int CONFIRMATIONUI_SYSTEM_ERROR = 5;
    public static final int CONFIRMATIONUI_UNIMPLEMENTED = 6;
    public static final int CONFIRMATIONUI_UNEXPECTED = 7;
    public static final int CONFIRMATIONUI_UIERROR = 0x10000;
    public static final int CONFIRMATIONUI_UIERROR_MISSING_GLYPH = 0x10001;
    public static final int CONFIRMATIONUI_UIERROR_MESSAGE_TOO_LONG = 0x10002;
    public static final int CONFIRMATIONUI_UIERROR_MALFORMED_UTF8_ENCODING = 0x10003;

    /**
     * Requests keystore call into the confirmationui HAL to display a prompt.
     *
     * @param listener the binder to use for callbacks.
     * @param promptText the prompt to display.
     * @param extraData extra data / nonce from application.
     * @param locale the locale as a BCP 47 langauge tag.
     * @param uiOptionsAsFlags the UI options to use, as flags.
     * @return one of the {@code CONFIRMATIONUI_*} constants, for
     * example {@code KeyStore.CONFIRMATIONUI_OK}.
     */
    public int presentConfirmationPrompt(IBinder listener, String promptText, byte[] extraData,
                                         String locale, int uiOptionsAsFlags) {
        try {
            return mBinder.presentConfirmationPrompt(listener, promptText, extraData, locale,
                                                     uiOptionsAsFlags);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return CONFIRMATIONUI_SYSTEM_ERROR;
        }
    }

    /**
     * Requests keystore call into the confirmationui HAL to cancel displaying a prompt.
     *
     * @param listener the binder passed to the {@link #presentConfirmationPrompt} method.
     * @return one of the {@code CONFIRMATIONUI_*} constants, for
     * example {@code KeyStore.CONFIRMATIONUI_OK}.
     */
    public int cancelConfirmationPrompt(IBinder listener) {
        try {
            return mBinder.cancelConfirmationPrompt(listener);
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return CONFIRMATIONUI_SYSTEM_ERROR;
        }
    }

    /**
     * Requests keystore to check if the confirmationui HAL is available.
     *
     * @return whether the confirmationUI HAL is available.
     */
    public boolean isConfirmationPromptSupported() {
        try {
            return mBinder.isConfirmationPromptSupported();
        } catch (RemoteException e) {
            Log.w(TAG, "Cannot connect to keystore", e);
            return false;
        }
    }

    /**
     * Returns a {@link KeyStoreException} corresponding to the provided keystore/keymaster error
     * code.
     */
    public static KeyStoreException getKeyStoreException(int errorCode) {
        if (errorCode > 0) {
            // KeyStore layer error
            switch (errorCode) {
                case NO_ERROR:
                    return new KeyStoreException(errorCode, "OK");
                case LOCKED:
                    return new KeyStoreException(errorCode, "User authentication required");
                case UNINITIALIZED:
                    return new KeyStoreException(errorCode, "Keystore not initialized");
                case SYSTEM_ERROR:
                    return new KeyStoreException(errorCode, "System error");
                case PERMISSION_DENIED:
                    return new KeyStoreException(errorCode, "Permission denied");
                case KEY_NOT_FOUND:
                    return new KeyStoreException(errorCode, "Key not found");
                case VALUE_CORRUPTED:
                    return new KeyStoreException(errorCode, "Key blob corrupted");
                case OP_AUTH_NEEDED:
                    return new KeyStoreException(errorCode, "Operation requires authorization");
                default:
                    return new KeyStoreException(errorCode, String.valueOf(errorCode));
            }
        } else {
            // Keymaster layer error
            switch (errorCode) {
                case KeymasterDefs.KM_ERROR_INVALID_AUTHORIZATION_TIMEOUT:
                    // The name of this parameter significantly differs between Keymaster and
                    // framework APIs. Use the framework wording to make life easier for developers.
                    return new KeyStoreException(errorCode,
                            "Invalid user authentication validity duration");
                default:
                    return new KeyStoreException(errorCode,
                            KeymasterDefs.getErrorMessage(errorCode));
            }
        }
    }

    /**
     * Returns an {@link InvalidKeyException} corresponding to the provided
     * {@link KeyStoreException}.
     */
    public InvalidKeyException getInvalidKeyException(
            String keystoreKeyAlias, int uid, KeyStoreException e) {
        switch (e.getErrorCode()) {
            case LOCKED:
                return new UserNotAuthenticatedException();
            case KeymasterDefs.KM_ERROR_KEY_EXPIRED:
                return new KeyExpiredException();
            case KeymasterDefs.KM_ERROR_KEY_NOT_YET_VALID:
                return new KeyNotYetValidException();
            case KeymasterDefs.KM_ERROR_KEY_USER_NOT_AUTHENTICATED:
            case OP_AUTH_NEEDED:
            {
                // We now need to determine whether the key/operation can become usable if user
                // authentication is performed, or whether it can never become usable again.
                // User authentication requirements are contained in the key's characteristics. We
                // need to check whether these requirements can be be satisfied by asking the user
                // to authenticate.
                KeyCharacteristics keyCharacteristics = new KeyCharacteristics();
                int getKeyCharacteristicsErrorCode =
                        getKeyCharacteristics(keystoreKeyAlias, null, null, uid,
                                keyCharacteristics);
                if (getKeyCharacteristicsErrorCode != NO_ERROR) {
                    return new InvalidKeyException(
                            "Failed to obtained key characteristics",
                            getKeyStoreException(getKeyCharacteristicsErrorCode));
                }
                List<BigInteger> keySids =
                        keyCharacteristics.getUnsignedLongs(KeymasterDefs.KM_TAG_USER_SECURE_ID);
                if (keySids.isEmpty()) {
                    // Key is not bound to any SIDs -- no amount of authentication will help here.
                    return new KeyPermanentlyInvalidatedException();
                }
                long rootSid = GateKeeper.getSecureUserId();
                if ((rootSid != 0) && (keySids.contains(KeymasterArguments.toUint64(rootSid)))) {
                    // One of the key's SIDs is the current root SID -- user can be authenticated
                    // against that SID.
                    return new UserNotAuthenticatedException();
                }

                long fingerprintOnlySid = getFingerprintOnlySid();
                if ((fingerprintOnlySid != 0)
                        && (keySids.contains(KeymasterArguments.toUint64(fingerprintOnlySid)))) {
                    // One of the key's SIDs is the current fingerprint SID -- user can be
                    // authenticated against that SID.
                    return new UserNotAuthenticatedException();
                }

                // None of the key's SIDs can ever be authenticated
                return new KeyPermanentlyInvalidatedException();
            }
            case UNINITIALIZED:
                return new KeyPermanentlyInvalidatedException();
            default:
                return new InvalidKeyException("Keystore operation failed", e);
        }
    }

    private long getFingerprintOnlySid() {
        final PackageManager packageManager = mContext.getPackageManager();
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) {
            return 0;
        }
        FingerprintManager fingerprintManager = mContext.getSystemService(FingerprintManager.class);
        if (fingerprintManager == null) {
            return 0;
        }

        // TODO: Restore USE_FINGERPRINT permission check in
        // FingerprintManager.getAuthenticatorId once the ID is no longer needed here.
        return fingerprintManager.getAuthenticatorId();
    }

    /**
     * Returns an {@link InvalidKeyException} corresponding to the provided keystore/keymaster error
     * code.
     */
    public InvalidKeyException getInvalidKeyException(String keystoreKeyAlias, int uid,
            int errorCode) {
        return getInvalidKeyException(keystoreKeyAlias, uid, getKeyStoreException(errorCode));
    }
}
