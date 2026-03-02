package com.penumbraos.bridge_shell.provider

import android.content.Context
import android.os.IBinder
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
 * Uses reflection to call IDeviceManager methods (isConnected, getBatteryState).
 *
 * SELinux on the AI Pin blocks binder `transfer` from shell context to
 * device_manager, so we cannot use push-based subscriptions
 * (subscribeToAccessoryState). Instead, we poll the device manager at a
 * regular interval and notify SDK callbacks when state changes.
 *
 * Stock subscription pattern (from decompiled PowerLevelManager.java):
 *   - Booster (type 0): connection state + battery level
 *   - Charge pad (type 2): connection state only
 *   - Charge case (type 1): connection state only
 */
class AccessoryProvider(context: Context) : IAccessoryProvider.Stub() {

    private var deviceManagerService: Any? = null
    private var deviceManagerBinder: IBinder? = null

    private var iDeviceManagerClass: Class<*>
    private var iDeviceManagerAsInterfaceMethod: Method
    private var getBatteryStateMethod: Method
    private var isConnectedMethod: Method
    private var batteryStateClass: Class<*>

    // ─── Cached live state (updated by polling) ───

    /** Booster battery level percent (0-100, or -1 if disconnected) */
    @Volatile private var boosterLevel: Int = -1

    /** Whether booster is currently charging */
    @Volatile private var boosterCharging: Boolean = false

    /**
     * Booster connection state (stock DMAccessoryState values):
     *   1 = DISCONNECTED
     *   2 = CONNECTED
     *   128 = MAYBE_CONNECTED
     */
    @Volatile private var boosterConnectionState: Int = STATE_DISCONNECTED

    /** Whether the pin is sitting on the charge pad */
    @Volatile private var isOnChargePad: Boolean = false

    /** Whether the pin is in the charge case */
    @Volatile private var isInChargeCase: Boolean = false

    // ─── SDK callbacks ───

    private val sdkCallbacks = mutableListOf<IAccessoryCallback>()

    // ─── Polling ───

    private var pollingThread: Thread? = null
    @Volatile private var pollingActive = false

    companion object {
        // Stock DMAccessoryState constants
        const val STATE_DISCONNECTED = 1
        const val STATE_CONNECTED = 2
        const val STATE_MAYBE_CONNECTED = 128

        /** Polling interval in milliseconds */
        const val POLL_INTERVAL_MS = 5_000L
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
        ensureConnected()
        if (deviceManagerService == null) return null

        return try {
            pollAllAccessoryState()
            buildBatteryInfo()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting battery info", e)
            handleConnectionError()
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

        // Start polling on first callback registration
        startPolling()

        // Send initial state immediately
        try {
            ensureConnected()
            if (deviceManagerService != null) {
                pollAllAccessoryState()
            }
            callback.onBatteryInfoChanged(buildBatteryInfo())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send initial battery info", e)
            handleConnectionError()
            // Still send what we have (defaults)
            try { callback.onBatteryInfoChanged(buildBatteryInfo()) } catch (_: Exception) {}
        }
    }

    override fun deregisterCallback(callback: IAccessoryCallback) {
        sdkCallbacks.remove(callback)
        Log.i(TAG, "Deregistered callback (remaining: ${sdkCallbacks.size})")

        // Stop polling when no callbacks remain
        if (sdkCallbacks.isEmpty()) {
            stopPolling()
        }
    }

    // ─── Polling loop ───

    /**
     * Start a background polling thread that queries the device manager
     * every [POLL_INTERVAL_MS] and notifies callbacks on state changes.
     */
    private fun startPolling() {
        if (pollingActive) return
        pollingActive = true

        pollingThread = Thread({
            Log.i(TAG, "Polling thread started (interval=${POLL_INTERVAL_MS}ms)")
            while (pollingActive) {
                try {
                    Thread.sleep(POLL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
                if (!pollingActive) break

                try {
                    ensureConnected()
                    if (deviceManagerService != null) {
                        val changed = pollAllAccessoryState()
                        if (changed) {
                            notifyCallbacks()
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Poll cycle failed: ${e.message}")
                    handleConnectionError()
                }
            }
            Log.i(TAG, "Polling thread stopped")
        }, "AccessoryProvider-poll").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun stopPolling() {
        pollingActive = false
        pollingThread?.interrupt()
        pollingThread = null
        Log.i(TAG, "Polling stopped")
    }

    /**
     * Poll all accessory types and update cached state.
     * Returns true if any state changed since last poll.
     */
    private fun pollAllAccessoryState(): Boolean {
        val svc = deviceManagerService ?: return false
        var changed = false

        try {
            // Poll booster (type 0)
            val oldBoosterConn = boosterConnectionState
            val oldBoosterLevel = boosterLevel
            val oldBoosterCharging = boosterCharging

            val boosterConnStatus = isConnectedMethod.invoke(svc, 0) as Int
            boosterConnectionState = boosterConnStatus

            if (boosterConnStatus == STATE_CONNECTED) {
                val batteryStateObject = batteryStateClass.getConstructor().newInstance()
                val result = getBatteryStateMethod.invoke(svc, 0, batteryStateObject) as Int
                if (result == 0) {
                    boosterLevel = batteryStateClass.getField("levelPercent").getInt(batteryStateObject)
                    boosterCharging = batteryStateClass.getField("isCharging").getBoolean(batteryStateObject)
                }
            } else {
                boosterLevel = 0
                boosterCharging = false
            }

            if (oldBoosterConn != boosterConnectionState ||
                oldBoosterLevel != boosterLevel ||
                oldBoosterCharging != boosterCharging) {
                changed = true
            }

            // Poll charge pad (type 2)
            val oldPad = isOnChargePad
            val padStatus = isConnectedMethod.invoke(svc, 2) as Int
            isOnChargePad = (padStatus == STATE_CONNECTED)
            if (oldPad != isOnChargePad) changed = true

            // Poll charge case (type 1)
            val oldCase = isInChargeCase
            val caseStatus = isConnectedMethod.invoke(svc, 1) as Int
            isInChargeCase = (caseStatus == STATE_CONNECTED)
            if (oldCase != isInChargeCase) changed = true

        } catch (e: Exception) {
            Log.w(TAG, "pollAllAccessoryState failed: ${e.message}")
            handleConnectionError()
            throw e
        }

        return changed
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

        if (dead.isEmpty()) {
            Log.d(TAG, "Notified ${sdkCallbacks.size} callbacks: " +
                "booster=${boosterLevel}% conn=${boosterConnectionState} " +
                "charging=$boosterCharging pad=$isOnChargePad case=$isInChargeCase")
        }
    }

    // ─── Connection management ───

    private fun ensureConnected() {
        if (deviceManagerService != null && deviceManagerBinder?.isBinderAlive == true) return
        connectToDeviceManager()
    }

    private fun handleConnectionError() {
        deviceManagerService = null
        deviceManagerBinder = null
    }

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
