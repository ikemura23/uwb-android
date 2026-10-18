package com.ikemura23.uwb.model

enum class OobType(val label: String, val implemented: Boolean) {
    BLE("BLE", true),
    NEARBY("Nearby Connections", true),
    WIFI_AWARE("Wi-Fi Aware", false),
    ;

    companion object {
        val DEFAULT = BLE
    }
}
