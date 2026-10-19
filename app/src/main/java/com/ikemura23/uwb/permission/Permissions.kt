package com.ikemura23.uwb.permission

import android.Manifest
import android.os.Build
import com.ikemura23.uwb.model.OobType

const val UWB_RANGING = Manifest.permission.UWB_RANGING
const val BLUETOOTH_SCAN = Manifest.permission.BLUETOOTH_SCAN
const val BLUETOOTH_CONNECT = Manifest.permission.BLUETOOTH_CONNECT
const val BLUETOOTH_ADVERTISE = Manifest.permission.BLUETOOTH_ADVERTISE
const val NEARBY_WIFI_DEVICES = Manifest.permission.NEARBY_WIFI_DEVICES
const val ACCESS_LOCAL_NETWORK = Manifest.permission.ACCESS_LOCAL_NETWORK

/** API level that introduced ACCESS_LOCAL_NETWORK (Android 17). */
private const val LOCAL_NETWORK_SDK = 37

private val BLUETOOTH_PERMISSIONS = listOf(BLUETOOTH_SCAN, BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE)

/**
 * Runtime permissions needed for [oob]. minSdk is 33, so ACCESS_FINE_LOCATION is not required
 * (Nearby Connections only needs it on API 32 and below). Nearby on Android 17+ (targetSdk 37)
 * also needs ACCESS_LOCAL_NETWORK, which does not exist on older releases.
 */
fun requiredRuntimePermissions(oob: OobType, sdkInt: Int = Build.VERSION.SDK_INT): List<String> =
    listOf(UWB_RANGING) + when (oob) {
        OobType.BLE -> BLUETOOTH_PERMISSIONS
        OobType.NEARBY -> BLUETOOTH_PERMISSIONS + NEARBY_WIFI_DEVICES +
            if (sdkInt >= LOCAL_NETWORK_SDK) listOf(ACCESS_LOCAL_NETWORK) else emptyList()
        OobType.WIFI_AWARE -> listOf(NEARBY_WIFI_DEVICES)
    }

fun missingPermissions(required: List<String>, granted: Map<String, Boolean>): List<String> =
    required.filter { granted[it] != true }
