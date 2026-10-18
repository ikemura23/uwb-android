package com.ikemura23.uwb.oob.ble

import java.util.UUID

/** App-specific fixed GATT UUIDs for the UWB OOB exchange. */
object BleUuids {
    val SERVICE: UUID = UUID.fromString("4f5732ed-7015-49dc-93e7-44b8ea2e1317")

    /** 子機 → 親機 (WRITE) */
    val CONTROLEE_INFO: UUID = UUID.fromString("8dcc9bc8-3119-46c1-81d9-5f5284c06d74")

    /** 親機 → 子機 (READ) */
    val CONTROLLER_INFO: UUID = UUID.fromString("c1fb8178-0f3c-4374-b564-67b5ba1cdab8")
}
