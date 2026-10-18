package com.ikemura23.uwb.permission

import com.ikemura23.uwb.model.OobType
import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionsTest {
    @Test fun bleNeedsUwbAndBluetooth() {
        assertEquals(
            setOf(UWB_RANGING, BLUETOOTH_SCAN, BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE),
            requiredRuntimePermissions(OobType.BLE).toSet(),
        )
    }

    @Test fun nearbyNeedsUwbBluetoothAndNearbyWifi() {
        assertEquals(
            setOf(
                UWB_RANGING, BLUETOOTH_SCAN, BLUETOOTH_CONNECT, BLUETOOTH_ADVERTISE, NEARBY_WIFI_DEVICES,
            ),
            requiredRuntimePermissions(OobType.NEARBY).toSet(),
        )
    }

    @Test fun nearbyDoesNotNeedFineLocationOnApi33() {
        assertEquals(false, "android.permission.ACCESS_FINE_LOCATION" in requiredRuntimePermissions(OobType.NEARBY))
    }

    @Test fun wifiAwareNeedsUwbAndNearbyWifi() {
        assertEquals(
            setOf(UWB_RANGING, NEARBY_WIFI_DEVICES),
            requiredRuntimePermissions(OobType.WIFI_AWARE).toSet(),
        )
    }

    @Test fun missingPermissionsListsDenied() {
        assertEquals(listOf("b"), missingPermissions(listOf("a", "b"), mapOf("a" to true, "b" to false)))
    }

    @Test fun missingPermissionsTreatsAbsentAsMissing() {
        assertEquals(listOf("a"), missingPermissions(listOf("a"), emptyMap()))
    }
}
