/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.bluetooth;

import android.Manifest;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * This class provides the APIs to control the Bluetooth Pan
 * Profile.
 *
 * <p>BluetoothPan is a proxy object for controlling the Bluetooth
 * Service via IPC. Use {@link BluetoothAdapter#getProfileProxy} to get
 * the BluetoothPan proxy object.
 *
 * <p>Each method is protected with its appropriate permission.
 *
 * @hide
 */
@SystemApi
public final class BluetoothPan implements BluetoothProfile {
    private static final String TAG = "BluetoothPan";
    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    /**
     * Intent used to broadcast the change in connection state of the Pan
     * profile.
     *
     * <p>This intent will have 4 extras:
     * <ul>
     * <li> {@link #EXTRA_STATE} - The current state of the profile. </li>
     * <li> {@link #EXTRA_PREVIOUS_STATE}- The previous state of the profile.</li>
     * <li> {@link BluetoothDevice#EXTRA_DEVICE} - The remote device. </li>
     * <li> {@link #EXTRA_LOCAL_ROLE} - Which local role the remote device is
     * bound to. </li>
     * </ul>
     *
     * <p>{@link #EXTRA_STATE} or {@link #EXTRA_PREVIOUS_STATE} can be any of
     * {@link #STATE_DISCONNECTED}, {@link #STATE_CONNECTING},
     * {@link #STATE_CONNECTED}, {@link #STATE_DISCONNECTING}.
     *
     * <p> {@link #EXTRA_LOCAL_ROLE} can be one of {@link #LOCAL_NAP_ROLE} or
     * {@link #LOCAL_PANU_ROLE}
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission to
     * receive.
     */
    @SuppressLint("ActionValue")
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_CONNECTION_STATE_CHANGED =
            "android.bluetooth.pan.profile.action.CONNECTION_STATE_CHANGED";

    /**
     * Extra for {@link #ACTION_CONNECTION_STATE_CHANGED} intent
     * The local role of the PAN profile that the remote device is bound to.
     * It can be one of {@link #LOCAL_NAP_ROLE} or {@link #LOCAL_PANU_ROLE}.
     */
    @SuppressLint("ActionValue")
    public static final String EXTRA_LOCAL_ROLE = "android.bluetooth.pan.extra.LOCAL_ROLE";

    /**
     * Intent used to broadcast the change in tethering state of the Pan
     * Profile
     *
     * <p>This intent will have 1 extra:
     * <ul>
     * <li> {@link #EXTRA_TETHERING_STATE} - The current state of Bluetooth
     * tethering. </li>
     * </ul>
     *
     * <p> {@link #EXTRA_TETHERING_STATE} can be any of {@link #TETHERING_STATE_OFF} or
     * {@link #TETHERING_STATE_ON}
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission to
     * receive.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_TETHERING_STATE_CHANGED =
            "android.bluetooth.action.TETHERING_STATE_CHANGED";

    /**
     * Extra for {@link #ACTION_TETHERING_STATE_CHANGED} intent
     * The tethering state of the PAN profile.
     * It can be one of {@link #TETHERING_STATE_OFF} or {@link #TETHERING_STATE_ON}.
     */
    public static final String EXTRA_TETHERING_STATE =
            "android.bluetooth.extra.TETHERING_STATE";

    /** @hide */
    @IntDef({PAN_ROLE_NONE, LOCAL_NAP_ROLE, LOCAL_PANU_ROLE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface LocalPanRole {}

    public static final int PAN_ROLE_NONE = 0;
    /**
     * The local device is acting as a Network Access Point.
     */
    public static final int LOCAL_NAP_ROLE = 1;

    /**
     * The local device is acting as a PAN User.
     */
    public static final int LOCAL_PANU_ROLE = 2;

    /** @hide */
    @IntDef({PAN_ROLE_NONE, REMOTE_NAP_ROLE, REMOTE_PANU_ROLE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface RemotePanRole {}

    public static final int REMOTE_NAP_ROLE = 1;

    public static final int REMOTE_PANU_ROLE = 2;

    /** @hide **/
    @IntDef({TETHERING_STATE_OFF, TETHERING_STATE_ON})
    @Retention(RetentionPolicy.SOURCE)
    public @interface TetheringState{}

    public static final int TETHERING_STATE_OFF = 1;

    public static final int TETHERING_STATE_ON = 2;
    /**
     * Return codes for the connect and disconnect Bluez / Dbus calls.
     *
     * @hide
     */
    public static final int PAN_DISCONNECT_FAILED_NOT_CONNECTED = 1000;

    /**
     * @hide
     */
    public static final int PAN_CONNECT_FAILED_ALREADY_CONNECTED = 1001;

    /**
     * @hide
     */
    public static final int PAN_CONNECT_FAILED_ATTEMPT_FAILED = 1002;

    /**
     * @hide
     */
    public static final int PAN_OPERATION_GENERIC_FAILURE = 1003;

    /**
     * @hide
     */
    public static final int PAN_OPERATION_SUCCESS = 1004;

    private final Context mContext;

    private BluetoothAdapter mAdapter;
    private final BluetoothProfileConnector<IBluetoothPan> mProfileConnector =
            new BluetoothProfileConnector(this, BluetoothProfile.PAN,
                    "BluetoothPan", IBluetoothPan.class.getName()) {
                @Override
                public IBluetoothPan getServiceInterface(IBinder service) {
                    return IBluetoothPan.Stub.asInterface(Binder.allowBlocking(service));
                }
    };


    /**
     * Create a BluetoothPan proxy object for interacting with the local
     * Bluetooth Service which handles the Pan profile
     *
     * @hide
     */
    @UnsupportedAppUsage
    /*package*/ BluetoothPan(Context context, ServiceListener listener) {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mContext = context;
        mProfileConnector.connect(context, listener);
    }

    /**
     * Closes the connection to the service and unregisters callbacks
     */
    @UnsupportedAppUsage
    void close() {
        if (VDBG) log("close()");
        mProfileConnector.disconnect();
    }

    private IBluetoothPan getService() {
        return mProfileConnector.getService();
    }

    /** @hide */
    protected void finalize() {
        close();
    }

    /**
     * Initiate connection to a profile of the remote bluetooth device.
     *
     * <p> This API returns false in scenarios like the profile on the
     * device is already connected or Bluetooth is not turned on.
     * When this API returns true, it is guaranteed that
     * connection state intent for the profile will be broadcasted with
     * the state. Users can get the connection state of the profile
     * from this intent.
     *
     * @param device Remote Bluetooth Device
     * @return false on immediate error, true otherwise
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean connect(BluetoothDevice device) {
        if (DBG) log("connect(" + device + ")");
        final IBluetoothPan service = getService();
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                return service.connect(device);
            } catch (RemoteException e) {
                Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
                return false;
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
        return false;
    }

    /**
     * Initiate disconnection from a profile
     *
     * <p> This API will return false in scenarios like the profile on the
     * Bluetooth device is not in connected state etc. When this API returns,
     * true, it is guaranteed that the connection state change
     * intent will be broadcasted with the state. Users can get the
     * disconnection state of the profile from this intent.
     *
     * <p> If the disconnection is initiated by a remote device, the state
     * will transition from {@link #STATE_CONNECTED} to
     * {@link #STATE_DISCONNECTED}. If the disconnect is initiated by the
     * host (local) device the state will transition from
     * {@link #STATE_CONNECTED} to state {@link #STATE_DISCONNECTING} to
     * state {@link #STATE_DISCONNECTED}. The transition to
     * {@link #STATE_DISCONNECTING} can be used to distinguish between the
     * two scenarios.
     *
     * @param device Remote Bluetooth Device
     * @return false on immediate error, true otherwise
     * @hide
     */
    @UnsupportedAppUsage
    public boolean disconnect(BluetoothDevice device) {
        if (DBG) log("disconnect(" + device + ")");
        final IBluetoothPan service = getService();
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                return service.disconnect(device);
            } catch (RemoteException e) {
                Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
                return false;
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
        return false;
    }

    /**
     * Set connection policy of the profile
     *
     * <p> The device should already be paired.
     * Connection policy can be one of {@link #CONNECTION_POLICY_ALLOWED},
     * {@link #CONNECTION_POLICY_FORBIDDEN}, {@link #CONNECTION_POLICY_UNKNOWN}
     *
     * @param device Paired bluetooth device
     * @param connectionPolicy is the connection policy to set to for this profile
     * @return true if connectionPolicy is set, false on error
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.BLUETOOTH_PRIVILEGED)
    public boolean setConnectionPolicy(@NonNull BluetoothDevice device,
            @ConnectionPolicy int connectionPolicy) {
        if (DBG) log("setConnectionPolicy(" + device + ", " + connectionPolicy + ")");
        try {
            final IBluetoothPan service = getService();
            if (service != null && isEnabled()
                    && isValidDevice(device)) {
                if (connectionPolicy != BluetoothProfile.CONNECTION_POLICY_FORBIDDEN
                        && connectionPolicy != BluetoothProfile.CONNECTION_POLICY_ALLOWED) {
                    return false;
                }
                return service.setConnectionPolicy(device, connectionPolicy);
            }
            if (service == null) Log.w(TAG, "Proxy not attached to service");
            return false;
        } catch (RemoteException e) {
            Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            return false;
        }
    }

    /**
     * {@inheritDoc}
     * @hide
     */
    @SystemApi
    @Override
    @RequiresPermission(Manifest.permission.BLUETOOTH_PRIVILEGED)
    public @NonNull List<BluetoothDevice> getConnectedDevices() {
        if (VDBG) log("getConnectedDevices()");
        final IBluetoothPan service = getService();
        if (service != null && isEnabled()) {
            try {
                return service.getConnectedDevices();
            } catch (RemoteException e) {
                Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
                return new ArrayList<BluetoothDevice>();
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
        return new ArrayList<BluetoothDevice>();
    }

    /**
     * {@inheritDoc}
     * @hide
     */
    @Override
    @RequiresPermission(Manifest.permission.BLUETOOTH)
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        if (VDBG) log("getDevicesMatchingStates()");
        final IBluetoothPan service = getService();
        if (service != null && isEnabled()) {
            try {
                return service.getDevicesMatchingConnectionStates(states);
            } catch (RemoteException e) {
                Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
                return new ArrayList<BluetoothDevice>();
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
        return new ArrayList<BluetoothDevice>();
    }

    /**
     * {@inheritDoc}
     * @hide
     */
    @SystemApi
    @Override
    @RequiresPermission(Manifest.permission.BLUETOOTH_PRIVILEGED)
    public int getConnectionState(@NonNull BluetoothDevice device) {
        if (VDBG) log("getState(" + device + ")");
        final IBluetoothPan service = getService();
        if (service != null && isEnabled() && isValidDevice(device)) {
            try {
                return service.getConnectionState(device);
            } catch (RemoteException e) {
                Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
                return BluetoothProfile.STATE_DISCONNECTED;
            }
        }
        if (service == null) Log.w(TAG, "Proxy not attached to service");
        return BluetoothProfile.STATE_DISCONNECTED;
    }

    /**
     * Turns on/off bluetooth tethering
     *
     * @param value is whether to enable or disable bluetooth tethering
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.BLUETOOTH_PRIVILEGED)
    public void setBluetoothTethering(boolean value) {
        String pkgName = mContext.getOpPackageName();
        if (DBG) log("setBluetoothTethering(" + value + "), calling package:" + pkgName);
        final IBluetoothPan service = getService();
        if (service != null && isEnabled()) {
            try {
                service.setBluetoothTethering(value, pkgName, null);
            } catch (RemoteException e) {
                Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            }
        }
    }

    /**
     * Determines whether tethering is enabled
     *
     * @return true if tethering is on, false if not or some error occurred
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.BLUETOOTH_PRIVILEGED)
    public boolean isTetheringOn() {
        if (VDBG) log("isTetheringOn()");
        final IBluetoothPan service = getService();
        if (service != null && isEnabled()) {
            try {
                return service.isTetheringOn();
            } catch (RemoteException e) {
                Log.e(TAG, "Stack:" + Log.getStackTraceString(new Throwable()));
            }
        }
        return false;
    }

    @UnsupportedAppUsage
    private boolean isEnabled() {
        return mAdapter.getState() == BluetoothAdapter.STATE_ON;
    }

    @UnsupportedAppUsage
    private static boolean isValidDevice(BluetoothDevice device) {
        return device != null && BluetoothAdapter.checkBluetoothAddress(device.getAddress());
    }

    @UnsupportedAppUsage
    private static void log(String msg) {
        Log.d(TAG, msg);
    }
}
