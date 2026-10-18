package com.ikemura23.uwb.oob.nearby

import android.content.Context
import android.os.Build
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.ikemura23.uwb.oob.ControleeInfo
import com.ikemura23.uwb.oob.ControllerInfo
import com.ikemura23.uwb.oob.OobCodec
import com.ikemura23.uwb.oob.OobEvent
import com.ikemura23.uwb.oob.OobTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Nearby Connections OOB: 親機 = Advertiser、子機 = Discoverer (P2P_POINT_TO_POINT).
 * Connections are auto-accepted on both sides. Permissions are checked before the experiment starts.
 */
class NearbyOobTransport(
    context: Context,
    private val onEvent: (OobEvent) -> Unit,
) : OobTransport {
    private val client = Nearby.getConnectionsClient(context)
    private val linkEstablished = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    // The first endpoint we talk to; any other endpoint is ignored / rejected.
    private val claimedEndpoint = AtomicReference<String?>(null)

    @Volatile private var pending: CompletableDeferred<*>? = null

    // ---- 親機 ----

    override suspend fun exchangeAsController(build: (ControleeInfo) -> ControllerInfo): ControleeInfo {
        val result = CompletableDeferred<ControleeInfo>().also { pending = it }
        var receivedControlee: ControleeInfo? = null
        var sentPayloadId: Long? = null

        val payloadCallback = object : PayloadCallback() {
            override fun onPayloadReceived(endpointId: String, payload: Payload) {
                val bytes = payload.asBytes() ?: return
                try {
                    val info = OobCodec.decodeControlee(bytes)
                    log("子機情報を受信 (${bytes.size} bytes)")
                    val response = Payload.fromBytes(OobCodec.encode(build(info)))
                    receivedControlee = info
                    sentPayloadId = response.id
                    client.sendPayload(endpointId, response)
                        .addOnFailureListener { fail(result, "親機情報の送信に失敗しました: ${it.message}") }
                } catch (e: Exception) {
                    result.completeExceptionally(e)
                }
            }

            override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
                if (update.payloadId != sentPayloadId) return
                when (update.status) {
                    PayloadTransferUpdate.Status.SUCCESS -> {
                        val info = receivedControlee ?: return
                        if (result.complete(info)) log("親機情報の送信完了")
                    }
                    PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED ->
                        fail(result, "親機情報の送信に失敗しました (status=${update.status})")
                    else -> Unit
                }
            }
        }

        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        log("アドバタイズ開始")
        client.startAdvertising(endpointName(), SERVICE_ID, lifecycleCallback(result, payloadCallback), options)
            .addOnSuccessListener { log("アドバタイズ開始完了") }
            .addOnFailureListener { fail(result, "アドバタイズ開始に失敗しました: ${it.message}") }
        return awaitPending(result)
    }

    // ---- 子機 ----

    override suspend fun exchangeAsControlee(mine: ControleeInfo): ControllerInfo {
        val result = CompletableDeferred<ControllerInfo>().also { pending = it }

        val payloadCallback = object : PayloadCallback() {
            override fun onPayloadReceived(endpointId: String, payload: Payload) {
                val bytes = payload.asBytes() ?: return
                log("親機情報を受信 (${bytes.size} bytes)")
                try {
                    result.complete(OobCodec.decodeController(bytes))
                } catch (e: Exception) {
                    result.completeExceptionally(e)
                }
            }

            override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
        }
        val lifecycle = lifecycleCallback(result, payloadCallback) { endpointId ->
            val bytes = OobCodec.encode(mine)
            log("子機情報を送信 (${bytes.size} bytes)")
            client.sendPayload(endpointId, Payload.fromBytes(bytes))
                .addOnFailureListener { fail(result, "子機情報の送信に失敗しました: ${it.message}") }
        }

        val discoveryCallback = object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                if (closed.get() || !claimedEndpoint.compareAndSet(null, endpointId)) return
                log("親機を発見: ${info.endpointName}、接続要求")
                client.stopDiscovery()
                client.requestConnection(endpointName(), endpointId, lifecycle)
                    .addOnFailureListener { fail(result, "接続要求に失敗しました: ${it.message}") }
            }

            override fun onEndpointLost(endpointId: String) = log("親機をロスト: $endpointId")
        }
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        log("探索開始")
        client.startDiscovery(SERVICE_ID, discoveryCallback, options)
            .addOnFailureListener { fail(result, "探索開始に失敗しました: ${it.message}") }
        return awaitPending(result)
    }

    // ---- 共通 ----

    /** Auto-accepts the first connection; [onConnected] runs once the connection is established. */
    private fun lifecycleCallback(
        result: CompletableDeferred<*>,
        payloadCallback: PayloadCallback,
        onConnected: (endpointId: String) -> Unit = {},
    ) = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val ours = claimedEndpoint.compareAndSet(null, endpointId) || claimedEndpoint.get() == endpointId
            if (closed.get() || !ours) {
                client.rejectConnection(endpointId)
                return
            }
            log("接続開始: ${info.endpointName}、自動承認")
            client.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { fail(result, "接続承認に失敗しました: ${it.message}") }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            val code = resolution.status.statusCode
            if (code != ConnectionsStatusCodes.STATUS_OK) {
                fail(result, "接続に失敗しました (status=$code)")
                return
            }
            log("接続成功")
            // Advertising no longer needed (P2P_POINT_TO_POINT); harmless no-op on the 子機.
            client.stopAdvertising()
            emitLinkEstablished()
            onConnected(endpointId)
        }

        override fun onDisconnected(endpointId: String) {
            fail(result, "交換完了前に切断されました")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pending?.completeExceptionally(IllegalStateException("Nearby OOB は閉じられました"))
        release()
        log("Nearby OOB 終了")
    }

    private fun release() {
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
    }

    private suspend fun <T> awaitPending(result: CompletableDeferred<T>): T =
        try {
            if (closed.get()) {
                // close() raced with setup: release what was started after it ran.
                release()
                throw IllegalStateException("Nearby OOB は閉じられました")
            }
            result.await()
        } catch (e: CancellationException) {
            close()
            throw e
        }

    private fun emitLinkEstablished() {
        if (linkEstablished.compareAndSet(false, true)) onEvent(OobEvent.LinkEstablished)
    }

    private fun fail(result: CompletableDeferred<*>, message: String) {
        result.completeExceptionally(IllegalStateException(message))
    }

    private fun endpointName(): String = Build.MODEL

    private fun log(message: String) = onEvent(OobEvent.Log(message))

    private companion object {
        const val SERVICE_ID = "com.ikemura23.uwb.oob"
        val STRATEGY: Strategy = Strategy.P2P_POINT_TO_POINT
    }
}
