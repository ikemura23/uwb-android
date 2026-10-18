package com.ikemura23.uwb.oob

import android.content.Context
import com.ikemura23.uwb.model.OobType
import com.ikemura23.uwb.oob.ble.BleOobTransport
import com.ikemura23.uwb.oob.nearby.NearbyOobTransport

object OobTransportFactory {
    fun create(context: Context, type: OobType, onEvent: (OobEvent) -> Unit): OobTransport =
        when (type) {
            OobType.BLE -> BleOobTransport(context, onEvent)
            OobType.NEARBY -> NearbyOobTransport(context, onEvent)
            // Filled in by the Wi-Fi Aware transport task.
            OobType.WIFI_AWARE -> throw IllegalStateException("未実装")
        }
}
