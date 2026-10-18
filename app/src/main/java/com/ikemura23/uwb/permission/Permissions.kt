package com.ikemura23.uwb.permission

import android.Manifest
import com.ikemura23.uwb.model.OobType

const val UWB_RANGING = Manifest.permission.UWB_RANGING
const val BLUETOOTH_SCAN = Manifest.permission.BLUETOOTH_SCAN
const val BLUETOOTH_CONNECT = Manifest.permission.BLUETOOTH_CONNECT
const val BLUETOOTH_ADVERTISE = Manifest.permission.BLUETOOTH_ADVERTISE
const val NEARBY_WIFI_DEVICES = Manifest.permission.NEARBY_WIFI_DEVICES

private val BLUETOOTH_PERMISSIONS = listOf(BLUETOOTH_SCAN, BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE)

/**
 * Runtime permissions needed for [oob]. minSdk is 33, so ACCESS_FINE_LOCATION is not required
 * (Nearby Connections only needs it on API 32 and below).
 */
fun requiredRuntimePermissions(oob: OobType): List<String> = listOf(UWB_RANGING) + when (oob) {
    OobType.BLE -> BLUETOOTH_PERMISSIONS
    OobType.NEARBY -> BLUETOOTH_PERMISSIONS + NEARBY_WIFI_DEVICES
    OobType.WIFI_AWARE -> listOf(NEARBY_WIFI_DEVICES)
}

fun missingPermissions(required: List<String>, granted: Map<String, Boolean>): List<String> =
    required.filter { granted[it] != true }
