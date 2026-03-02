package com.penumbraos.bridge.types;

parcelable AccessoryBatteryInfo {
    int boosterBatteryLevel;
    boolean boosterBatteryCharging;
    boolean boosterBatteryConnected;
    /** Stock DMAccessoryState: 1=disconnected, 2=connected, 128=maybe_connected */
    int boosterConnectionState;
    boolean isOnChargePad;
    boolean isInChargeCase;
}
