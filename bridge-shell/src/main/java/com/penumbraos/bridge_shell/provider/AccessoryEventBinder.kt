package com.penumbraos.bridge_shell.provider

import android.os.Binder
import android.os.Parcel
import android.util.Log

private const val TAG = "AccessoryEventBinder"
private const val DESCRIPTOR = "humane.devicemanager.IDMAccessoryEventsCallback"
private const val TRANSACTION_onAccessoryEvent = 1
private const val TRANSACTION_onBatteryLevelChangeEvent = 2
private const val INTERFACE_TRANSACTION = 1598968902 // IBinder.INTERFACE_TRANSACTION

/**
 * Raw Binder implementation of humane.devicemanager.IDMAccessoryEventsCallback.
 *
 * This bypasses reflection-based proxy issues by implementing the exact same
 * binder wire protocol as the stock IDMAccessoryEventsCallback.Stub. The
 * humane.devicemanager service doesn't check the Java class — it only
 * transacts on the binder using the DESCRIPTOR and transaction codes.
 *
 * From decompiled IDMAccessoryEventsCallback.Stub.onTransact():
 *   - Transaction 1 (onAccessoryEvent): reads two ints (from, to)
 *   - Transaction 2 (onBatteryLevelChangeEvent): reads two ints (fromLevel, toLevel)
 *   - Both enforce interface token "humane.devicemanager.IDMAccessoryEventsCallback"
 */
class AccessoryEventBinder(
    private val onAccessoryEvent: (from: Int, to: Int) -> Unit,
    private val onBatteryLevelChangeEvent: (fromLevel: Int, toLevel: Int) -> Unit,
) : Binder() {

    init {
        attachInterface(null, DESCRIPTOR)
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        return when (code) {
            INTERFACE_TRANSACTION -> {
                reply?.writeString(DESCRIPTOR)
                true
            }
            TRANSACTION_onAccessoryEvent -> {
                data.enforceInterface(DESCRIPTOR)
                val from = data.readInt()
                val to = data.readInt()
                Log.d(TAG, "onAccessoryEvent: from=$from to=$to")
                onAccessoryEvent(from, to)
                true
            }
            TRANSACTION_onBatteryLevelChangeEvent -> {
                data.enforceInterface(DESCRIPTOR)
                val fromLevel = data.readInt()
                val toLevel = data.readInt()
                Log.d(TAG, "onBatteryLevelChangeEvent: from=$fromLevel to=$toLevel")
                onBatteryLevelChangeEvent(fromLevel, toLevel)
                true
            }
            else -> super.onTransact(code, data, reply, flags)
        }
    }
}
