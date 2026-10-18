package com.ikemura23.uwb.oob

import android.content.Context
import com.ikemura23.uwb.model.OobType
import com.ikemura23.uwb.oob.ble.BleOobTransport

object OobTransportFactory {
    fun create(context: Context, type: OobType, onEvent: (OobEvent) -> Unit): OobTransport =
        when (type) {
            OobType.BLE -> BleOobTransport(context, onEvent)
            // Filled in by the Nearby / Wi-Fi Aware transport tasks.
            OobType.NEARBY, OobType.WIFI_AWARE -> throw IllegalStateException("未実装")
        }
}
