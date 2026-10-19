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

    @Test fun nearbyNeedsLocalNetworkOnApi37() {
        assertEquals(true, ACCESS_LOCAL_NETWORK in requiredRuntimePermissions(OobType.NEARBY, sdkInt = 37))
    }

    @Test fun nearbyDoesNotRequestLocalNetworkBeforeApi37() {
        assertEquals(false, ACCESS_LOCAL_NETWORK in requiredRuntimePermissions(OobType.NEARBY, sdkInt = 36))
    }

    @Test fun localNetworkOnlyForNearby() {
        assertEquals(false, ACCESS_LOCAL_NETWORK in requiredRuntimePermissions(OobType.BLE, sdkInt = 37))
        assertEquals(false, ACCESS_LOCAL_NETWORK in requiredRuntimePermissions(OobType.WIFI_AWARE, sdkInt = 37))
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
