package com.penumbraos.bridge_shell.provider

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ServiceManager
import android.util.Log
import com.penumbraos.bridge.IAccessoryProvider
import com.penumbraos.bridge.callback.IAccessoryCallback
import com.penumbraos.bridge.external.getApkClassLoader
import com.penumbraos.bridge.external.safeCallback
import com.penumbraos.bridge.types.AccessoryBatteryInfo
import java.lang.reflect.Method

private const val TAG = "AccessoryProvider"

/**
 * Provides battery and accessory state from humane.devicemanager.
 *
 * Uses raw binder transactions (not reflection) to subscribe to accessory
 * state changes. The humane.devicemanager service's subscribeToAccessoryState()
 * method expects an IDMAccessoryEventsCallback binder. We can't pass a
 * Proxy.newProxyInstance or reflected object because Method.invoke() enforces
 * parameter types. Instead, we transact directly on the raw IBinder using
 * the same Parcel protocol as IDeviceManager.Stub.Proxy.
 *
 * Stock subscription pattern (from decompiled PowerLevelManager.java):
 *   - Booster (type 0, mask 135): connection state + battery level changes
 *   - Charge pad (type 2, mask 3): connection state only
 *   - Charge case (type 1, mask 3): connection state only
 */
class AccessoryProvider(context: Context) : IAccessoryProvider.Stub() {

    private var deviceManagerService: Any? = null
    private var deviceManagerBinder: IBinder? = null

    private var iDeviceManagerClass: Class<*>
    private var iDeviceManagerAsInterfaceMethod: Method
    private var getBatteryStateMethod: Method
    private var isConnectedMethod: Method
    private var batteryStateClass: Class<*>

    // ─── Cached live state (updated by subscriptions) ───

    /** Booster battery level percent (0-100, or -1 if disconnected) */
    private var boosterLevel: Int = -1

    /** Whether booster is currently charging */
    private var boosterCharging: Boolean = false

    /**
     * Booster connection state (stock DMAccessoryState values):
     *   1 = DISCONNECTED
     *   2 = CONNECTED
     *   128 = MAYBE_CONNECTED
     */
    private var boosterConnectionState: Int = STATE_DISCONNECTED

    /** Whether the pin is sitting on the charge pad */
    private var isOnChargePad: Boolean = false

    /** Whether the pin is in the charge case */
    private var isInChargeCase: Boolean = false

    /** Whether we've subscribed to DeviceManager events */
    private var subscribed = false

    // ─── SDK callbacks ───

    private val sdkCallbacks = mutableListOf<IAccessoryCallback>()

    companion object {
        // Stock DMAccessoryState constants
        const val STATE_DISCONNECTED = 1
        const val STATE_CONNECTED = 2
        const val STATE_MAYBE_CONNECTED = 128

        // Stock state mask constants (from PowerLevelManager)
        const val BOOSTER_STATE_MASK = 135   // stock uses for type 0
        const val CASE_PAD_STATE_MASK = 3    // stock uses for types 1 and 2

        // IDeviceManager transaction codes (from decompiled Stub)
        const val TRANSACTION_subscribeToAccessoryState = 2
        const val IDEVICEMANAGER_DESCRIPTOR = "humane.devicemanager.IDeviceManager"
    }

    init {
        val classLoader = getApkClassLoader(context, "humane.experience.settings")

        iDeviceManagerClass =
            classLoader.loadClass("humane.devicemanager.IDeviceManager")
        val iDeviceManagerStubClass =
            classLoader.loadClass("humane.devicemanager.IDeviceManager\$Stub")
        batteryStateClass =
            classLoader.loadClass("humane.devicemanager.DMAccessoryBatteryState")

        iDeviceManagerAsInterfaceMethod =
            iDeviceManagerStubClass.getMethod("asInterface", IBinder::class.java)
        getBatteryStateMethod =
            iDeviceManagerClass.getMethod("getBatteryState", Int::class.java, batteryStateClass)
        isConnectedMethod = iDeviceManagerClass.getMethod("isConnected", Int::class.java)
    }

    // ─── IAccessoryProvider implementation ───

    override fun getBatteryInfo(): AccessoryBatteryInfo? {
        if (deviceManagerService == null || deviceManagerBinder?.isBinderAlive != true) {
            Log.w(TAG, "Device Manager service not connected")
            connectToDeviceManager()
        }

        if (deviceManagerService == null || deviceManagerBinder?.isBinderAlive != true) {
            Log.w(TAG, "Device Manager service not connected. Giving up")
            return null
        }

        // If subscribed, return cached state (more accurate — push-updated)
        if (subscribed) {
            return buildBatteryInfo()
        }

        // Fallback: poll (original behavior)
        return try {
            val connectionStatus = isConnectedMethod.invoke(deviceManagerService, 0) as Int
            val isConnected = connectionStatus != STATE_DISCONNECTED

            val batteryStateObject = batteryStateClass.getConstructor().newInstance()
            val result =
                getBatteryStateMethod.invoke(deviceManagerService, 0, batteryStateObject) as Int

            if (result == 0) {
                val levelPercentField = batteryStateClass.getField("levelPercent")
                val b1Level = levelPercentField.getInt(batteryStateObject)

                val isChargingField = batteryStateClass.getField("isCharging")
                val isCharging = isChargingField.getBoolean(batteryStateObject)

                AccessoryBatteryInfo().apply {
                    boosterBatteryLevel = if (isConnected) b1Level else -1
                    boosterBatteryCharging = isConnected && isCharging
                    boosterBatteryConnected = isConnected
                    boosterConnectionState = connectionStatus
                    this.isOnChargePad = this@AccessoryProvider.isOnChargePad
                    this.isInChargeCase = this@AccessoryProvider.isInChargeCase
                }
            } else {
                Log.e(TAG, "Failed to get B1 battery state, result: $result")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing B1 battery state", e)
            deviceManagerService = null
            deviceManagerBinder = null
            null
        }
    }

    override fun registerCallback(callback: IAccessoryCallback) {
        callback.asBinder().linkToDeath(object : IBinder.DeathRecipient {
            override fun binderDied() {
                deregisterCallback(callback)
            }
        }, 0)

        sdkCallbacks.add(callback)
        Log.i(TAG, "Registered callback (total: ${sdkCallbacks.size})")

        // Start subscriptions on first callback registration
        subscribeToDeviceManager()

        // Send initial state immediately
        try {
            callback.onBatteryInfoChanged(buildBatteryInfo())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send initial battery info", e)
        }
    }

    override fun deregisterCallback(callback: IAccessoryCallback) {
        sdkCallbacks.remove(callback)
        Log.i(TAG, "Deregistered callback (remaining: ${sdkCallbacks.size})")
    }

    // ─── Raw binder subscription ───

    /**
     * Subscribe to accessory state changes using raw binder transactions.
     *
     * This bypasses the reflected subscribeToAccessoryState() method, which fails
     * because Method.invoke() enforces that the callback parameter's class matches
     * IDMAccessoryEventsCallback from Humane's classloader. Our AccessoryEventBinder
     * is a raw Binder that speaks the same wire protocol.
     *
     * From decompiled IDeviceManager.Stub.Proxy.subscribeToAccessoryState():
     *   _data.writeInterfaceToken("humane.devicemanager.IDeviceManager");
     *   _data.writeInt(type);
     *   _data.writeInt(states);
     *   _data.writeStrongInterface(cb);  // → writeStrongBinder(cb.asBinder())
     *   mRemote.transact(2, _data, _reply, 0);
     */
    private fun subscribeRaw(
        accessoryType: Int,
        stateMask: Int,
        callbackBinder: Binder
    ): Int {
        val binder = deviceManagerBinder ?: return -1
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(IDEVICEMANAGER_DESCRIPTOR)
            data.writeInt(accessoryType)
            data.writeInt(stateMask)
            data.writeStrongBinder(callbackBinder)
            binder.transact(TRANSACTION_subscribeToAccessoryState, data, reply, 0)
            reply.readException()
            return reply.readInt()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Subscribe to all accessory events (booster, charge pad, charge case).
     * Matches stock PowerLevelManager initialization pattern exactly.
     */
    private fun subscribeToDeviceManager() {
        if (subscribed) return

        connectToDeviceManager()
        if (deviceManagerBinder == null) {
            Log.w(TAG, "Cannot subscribe — Device Manager not available")
            return
        }

        try {
            // Get initial booster state (stock: PowerLevelManager.getInitialBoosterState)
            val connectionStatus = isConnectedMethod.invoke(deviceManagerService, 0) as Int
            boosterConnectionState = connectionStatus

            if (connectionStatus == STATE_CONNECTED) {
                refreshBoosterBattery()
            } else if (connectionStatus == STATE_MAYBE_CONNECTED) {
                boosterLevel = 0
            } else {
                boosterLevel = 0
            }

            // Get initial charge case state (stock: PowerLevelManager.getInitialChargeCaseState)
            val chargeCaseStatus = isConnectedMethod.invoke(deviceManagerService, 1) as Int
            isInChargeCase = (chargeCaseStatus == STATE_CONNECTED)

            // Subscribe to booster (type 0, mask 135) — connection + battery changes
            val boosterCallback = AccessoryEventBinder(
                onAccessoryEvent = { from, to ->
                    when (to) {
                        STATE_CONNECTED -> {
                            boosterConnectionState = STATE_CONNECTED
                            // Stock: on connect, immediately fetch fresh battery level
                            refreshBoosterBattery()
                        }
                        STATE_DISCONNECTED -> {
                            boosterConnectionState = STATE_DISCONNECTED
                            boosterLevel = 0
                            boosterCharging = false
                        }
                        STATE_MAYBE_CONNECTED -> {
                            boosterConnectionState = STATE_MAYBE_CONNECTED
                            boosterLevel = 0
                            boosterCharging = false
                        }
                    }
                    notifyCallbacks()
                },
                onBatteryLevelChangeEvent = { _, toLevel ->
                    boosterLevel = toLevel
                    notifyCallbacks()
                }
            )
            val boosterResult = subscribeRaw(0, BOOSTER_STATE_MASK, boosterCallback)
            Log.i(TAG, "Subscribed to booster (type 0), result=$boosterResult")

            // Subscribe to charge pad (type 2, mask 3) — connection only
            val chargePadCallback = AccessoryEventBinder(
                onAccessoryEvent = { from, to ->
                    isOnChargePad = (to == STATE_CONNECTED)
                    Log.d(TAG, "Charge pad: ${if (isOnChargePad) "connected" else "disconnected"}")
                    notifyCallbacks()
                },
                onBatteryLevelChangeEvent = { _, _ -> }
            )
            val padResult = subscribeRaw(2, CASE_PAD_STATE_MASK, chargePadCallback)
            Log.i(TAG, "Subscribed to charge pad (type 2), result=$padResult")

            // Subscribe to charge case (type 1, mask 3) — connection only
            val chargeCaseCallback = AccessoryEventBinder(
                onAccessoryEvent = { from, to ->
                    isInChargeCase = (to == STATE_CONNECTED)
                    Log.d(TAG, "Charge case: ${if (isInChargeCase) "connected" else "disconnected"}")
                    notifyCallbacks()
                },
                onBatteryLevelChangeEvent = { _, _ -> }
            )
            val caseResult = subscribeRaw(1, CASE_PAD_STATE_MASK, chargeCaseCallback)
            Log.i(TAG, "Subscribed to charge case (type 1), result=$caseResult")

            subscribed = true
            Log.i(TAG, "Subscribed to all accessory state events")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to subscribe to accessory events", e)
        }
    }

    /**
     * Fetch current booster battery level and charging status via reflection.
     * Stock: PowerLevelManager calls getBatteryState(0, state) after connect.
     */
    private fun refreshBoosterBattery() {
        try {
            val batteryStateObject = batteryStateClass.getConstructor().newInstance()
            val result =
                getBatteryStateMethod.invoke(deviceManagerService, 0, batteryStateObject) as Int
            if (result == 0) {
                val levelPercentField = batteryStateClass.getField("levelPercent")
                boosterLevel = levelPercentField.getInt(batteryStateObject)

                val isChargingField = batteryStateClass.getField("isCharging")
                boosterCharging = isChargingField.getBoolean(batteryStateObject)

                Log.d(TAG, "Booster battery: ${boosterLevel}%, charging=$boosterCharging")
            } else {
                Log.w(TAG, "getBatteryState failed, result=$result")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing booster battery", e)
        }
    }

    // ─── Callback notification ───

    private fun buildBatteryInfo(): AccessoryBatteryInfo {
        val isConnected = boosterConnectionState == STATE_CONNECTED
        return AccessoryBatteryInfo().apply {
            boosterBatteryLevel = if (isConnected) boosterLevel else -1
            boosterBatteryCharging = isConnected && boosterCharging
            boosterBatteryConnected = isConnected
            boosterConnectionState = this@AccessoryProvider.boosterConnectionState
            this.isOnChargePad = this@AccessoryProvider.isOnChargePad
            this.isInChargeCase = this@AccessoryProvider.isInChargeCase
        }
    }

    private fun notifyCallbacks() {
        val info = buildBatteryInfo()
        val dead = mutableListOf<IAccessoryCallback>()

        sdkCallbacks.forEach { cb ->
            safeCallback(TAG, {
                cb.onBatteryInfoChanged(info)
            }, onDeadObject = {
                dead.add(cb)
            })
        }

        dead.forEach { deregisterCallback(it) }
    }

    // ─── Connection ───

    private fun connectToDeviceManager() {
        try {
            val binder = ServiceManager.getService("humane.devicemanager")
            if (binder != null && binder.isBinderAlive) {
                deviceManagerService = iDeviceManagerAsInterfaceMethod.invoke(null, binder)
                deviceManagerBinder = binder

                Log.d(TAG, "Connected to Device Manager service")
            } else {
                Log.e(TAG, "Device Manager service not available")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to Device Manager service", e)
        }
    }
}
