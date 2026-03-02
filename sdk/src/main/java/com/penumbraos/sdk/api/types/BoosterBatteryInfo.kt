package com.penumbraos.sdk.api.types

import com.penumbraos.bridge.types.AccessoryBatteryInfo

data class BoosterBatteryInfo(
    val batteryLevel: Int,
    val isCharging: Boolean,
    val isConnected: Boolean,
    /** Stock DMAccessoryState: 1=disconnected, 2=connected, 128=maybe_connected */
    val connectionState: Int = 1,
    /** Whether the pin is sitting on the charge pad */
    val isOnChargePad: Boolean = false,
    /** Whether the pin is in the charge case */
    val isInChargeCase: Boolean = false
) {
    companion object {
        fun fromAidl(
            aidlInfo: AccessoryBatteryInfo,
        ): BoosterBatteryInfo {
            return BoosterBatteryInfo(
                batteryLevel = aidlInfo.boosterBatteryLevel,
                isCharging = aidlInfo.boosterBatteryCharging,
                isConnected = aidlInfo.boosterBatteryConnected,
                connectionState = aidlInfo.boosterConnectionState,
                isOnChargePad = aidlInfo.isOnChargePad,
                isInChargeCase = aidlInfo.isInChargeCase
            )
        }
    }
}
