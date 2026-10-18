package com.ikemura23.uwb.oob

import android.content.Context
import com.ikemura23.uwb.model.OobType

object OobTransportFactory {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context, type: OobType, onEvent: (OobEvent) -> Unit): OobTransport =
        when (type) {
            // Filled in by the BLE / Nearby / Wi-Fi Aware transport tasks.
            OobType.BLE, OobType.NEARBY, OobType.WIFI_AWARE -> throw IllegalStateException("未実装")
        }
}
