package com.ikemura23.uwb.oob.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.ikemura23.uwb.oob.ControleeInfo
import com.ikemura23.uwb.oob.ControllerInfo
import com.ikemura23.uwb.oob.OobCodec
import com.ikemura23.uwb.oob.OobEvent
import com.ikemura23.uwb.oob.OobTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BLE GATT OOB: 親機 = GATT サーバー + アドバタイズ、子機 = スキャン + GATT クライアント.
 * Permissions are checked before the experiment starts.
 */
@SuppressLint("MissingPermission")
class BleOobTransport(
    private val context: Context,
    private val onEvent: (OobEvent) -> Unit,
) : OobTransport {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val linkEstablished = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    @Volatile private var pending: CompletableDeferred<*>? = null
    @Volatile private var gattServer: BluetoothGattServer? = null
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var advertiseCallback: AdvertiseCallback? = null
    @Volatile private var scanCallback: ScanCallback? = null

    // 親機 state, written from GATT binder threads.
    @Volatile private var receivedControlee: ControleeInfo? = null
    @Volatile private var controllerResponse: ByteArray? = null

    // ---- 親機 ----

    override suspend fun exchangeAsController(build: (ControleeInfo) -> ControllerInfo): ControleeInfo {
        val adapter = requireAdapter()
        val result = CompletableDeferred<ControleeInfo>().also { pending = it }
        // Completed once a Read Response with ControllerInfo has been handed to the stack.
        val responseSent = CompletableDeferred<ControleeInfo>()

        val callback = object : BluetoothGattServerCallback() {
            override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                if (closed.get()) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    result.completeExceptionally(IllegalStateException("GATT サービス追加に失敗しました (status=$status)"))
                    return
                }
                log("GATT サービス追加完了")
                startAdvertising(adapter, result)
            }

            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    log("子機が接続: ${device.address}")
                    emitLinkEstablished()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    // Disconnect after our Read Response = the 子機 got ControllerInfo and closed.
                    val info = if (responseSent.isCompleted) receivedControlee else null
                    if (info != null) {
                        if (result.complete(info)) log("子機の切断を確認")
                        return
                    }
                    result.completeExceptionally(IllegalStateException("交換完了前に子機が切断しました (status=$status)"))
                }
            }

            override fun onMtuChanged(device: BluetoothDevice, newMtu: Int) = log("MTU 変更: $newMtu")

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
            ) {
                val server = gattServer ?: return
                if (characteristic.uuid != BleUuids.CONTROLEE_INFO || preparedWrite || offset != 0) {
                    if (responseNeeded) server.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                    return
                }
                try {
                    val info = OobCodec.decodeControlee(value)
                    log("子機情報を受信 (${value.size} bytes)")
                    controllerResponse = OobCodec.encode(build(info))
                    receivedControlee = info
                    if (responseNeeded) server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                } catch (e: Exception) {
                    if (responseNeeded) server.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                    result.completeExceptionally(e)
                }
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
            ) {
                val server = gattServer ?: return
                val value = controllerResponse
                val info = receivedControlee
                if (characteristic.uuid != BleUuids.CONTROLLER_INFO || value == null || info == null ||
                    offset > value.size
                ) {
                    server.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                    return
                }
                server.sendResponse(
                    device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size),
                )
                if (responseSent.complete(info)) log("親機情報を送信 (${value.size} bytes)、子機の切断待ち")
            }
        }

        log("GATT サーバー起動")
        val server = manager.openGattServer(context, callback)
            ?: throw IllegalStateException("GATT サーバーを開けませんでした")
        gattServer = server
        val service = BluetoothGattService(BleUuids.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(
                BluetoothGattCharacteristic(
                    BleUuids.CONTROLEE_INFO,
                    BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE,
                ),
            )
            addCharacteristic(
                BluetoothGattCharacteristic(
                    BleUuids.CONTROLLER_INFO,
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ,
                ),
            )
        }
        check(server.addService(service)) { "GATT サービスを追加できませんでした" }
        return coroutineScope {
            // Fallback if the 子機's disconnect never arrives (still inside the runner's timeout).
            val fallback = launch {
                val info = responseSent.await()
                delay(DISCONNECT_WAIT_MS)
                if (result.complete(info)) log("切断待ちタイムアウト、交換完了とみなす")
            }
            try {
                awaitPending(result)
            } finally {
                fallback.cancel()
            }
        }
    }

    private fun startAdvertising(adapter: BluetoothAdapter, result: CompletableDeferred<*>) {
        if (closed.get()) return
        val advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            result.completeExceptionally(IllegalStateException("Bluetooth が OFF です"))
            return
        }
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) = log("アドバタイズ開始")
            override fun onStartFailure(errorCode: Int) {
                result.completeExceptionally(IllegalStateException("アドバタイズ開始に失敗しました (error=$errorCode)"))
            }
        }
        advertiseCallback = callback
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(BleUuids.SERVICE)).build()
        advertiser.startAdvertising(settings, data, callback)
        // close() may have run concurrently and missed advertiseCallback.
        if (closed.get()) release()
    }

    // ---- 子機 ----

    override suspend fun exchangeAsControlee(mine: ControleeInfo): ControllerInfo {
        val adapter = requireAdapter()
        val scanner = adapter.bluetoothLeScanner ?: throw IllegalStateException("Bluetooth が OFF です")
        val result = CompletableDeferred<ControllerInfo>().also { pending = it }
        val found = AtomicBoolean(false)

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    result.completeExceptionally(IllegalStateException("親機との GATT 接続が切れました (status=$status)"))
                    return
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    log("親機に接続")
                    emitLinkEstablished()
                    log("MTU 要求: $REQUEST_MTU")
                    if (!g.requestMtu(REQUEST_MTU)) fail(result, "MTU 要求に失敗しました")
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                log("MTU 変更: $mtu (status=$status)")
                log("サービス探索開始")
                if (!g.discoverServices()) fail(result, "サービス探索を開始できませんでした")
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val char = g.getService(BleUuids.SERVICE)?.getCharacteristic(BleUuids.CONTROLEE_INFO)
                if (status != BluetoothGatt.GATT_SUCCESS || char == null) {
                    fail(result, "OOB サービスが見つかりません (status=$status)")
                    return
                }
                val bytes = OobCodec.encode(mine)
                log("子機情報を送信 (${bytes.size} bytes)")
                val rc = g.writeCharacteristic(char, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                if (rc != BluetoothStatusCodes.SUCCESS) fail(result, "子機情報の書き込みに失敗しました (rc=$rc)")
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                val char = g.getService(BleUuids.SERVICE)?.getCharacteristic(BleUuids.CONTROLLER_INFO)
                if (status != BluetoothGatt.GATT_SUCCESS || char == null) {
                    fail(result, "子機情報の書き込みに失敗しました (status=$status)")
                    return
                }
                log("書き込み完了、親機情報を読み取り")
                if (!g.readCharacteristic(char)) fail(result, "親機情報の読み取りを開始できませんでした")
            }

            override fun onCharacteristicRead(
                g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int,
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail(result, "親機情報の読み取りに失敗しました (status=$status)")
                    return
                }
                log("親機情報を受信 (${value.size} bytes)")
                try {
                    result.complete(OobCodec.decodeController(value))
                } catch (e: Exception) {
                    result.completeExceptionally(e)
                }
            }
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, scanResult: ScanResult) {
                if (!found.compareAndSet(false, true)) return
                stopScan()
                log("親機を発見: ${scanResult.device.address}、接続開始")
                // The replacement overload (BluetoothGattConnectionSettings) is API 37+; minSdk is 33.
                @Suppress("DEPRECATION")
                gatt = scanResult.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                if (closed.get()) release()
            }

            override fun onScanFailed(errorCode: Int) = fail(result, "スキャンに失敗しました (error=$errorCode)")
        }
        scanCallback = callback
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(BleUuids.SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        log("スキャン開始")
        scanner.startScan(listOf(filter), settings, callback)
        return awaitPending(result)
    }

    // ---- 共通 ----

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pending?.completeExceptionally(IllegalStateException("BLE OOB は閉じられました"))
        release()
        log("BLE OOB 終了")
    }

    /** Releases every BLE resource; safe to call repeatedly. */
    private fun release() {
        advertiseCallback?.let { cb ->
            manager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(cb)
            advertiseCallback = null
        }
        stopScan()
        closeGatt()
        gattServer?.let {
            it.clearServices()
            it.close()
            gattServer = null
        }
    }

    private suspend fun <T> awaitPending(result: CompletableDeferred<T>): T =
        try {
            if (closed.get()) {
                // close() raced with setup: release what was opened after it ran.
                release()
                throw IllegalStateException("BLE OOB は閉じられました")
            }
            result.await()
        } catch (e: CancellationException) {
            close()
            throw e
        }

    private fun requireAdapter(): BluetoothAdapter {
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) throw IllegalStateException("Bluetooth が OFF です")
        return adapter
    }

    private fun stopScan() {
        scanCallback?.let { cb ->
            manager.adapter?.bluetoothLeScanner?.stopScan(cb)
            scanCallback = null
        }
    }

    private fun closeGatt() {
        gatt?.let {
            it.disconnect()
            it.close()
            gatt = null
        }
    }

    private fun emitLinkEstablished() {
        if (linkEstablished.compareAndSet(false, true)) onEvent(OobEvent.LinkEstablished)
    }

    private fun fail(result: CompletableDeferred<*>, message: String) {
        result.completeExceptionally(IllegalStateException(message))
    }

    private fun log(message: String) = onEvent(OobEvent.Log(message))

    private companion object {
        const val DISCONNECT_WAIT_MS = 2_000L
        const val REQUEST_MTU = 64
    }
}
