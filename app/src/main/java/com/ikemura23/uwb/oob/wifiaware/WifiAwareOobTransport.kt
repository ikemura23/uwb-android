package com.ikemura23.uwb.oob.wifiaware

import android.content.Context
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySession
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import android.os.Handler
import android.os.Looper
import com.ikemura23.uwb.oob.ControleeInfo
import com.ikemura23.uwb.oob.ControllerInfo
import com.ikemura23.uwb.oob.OobCodec
import com.ikemura23.uwb.oob.OobEvent
import com.ikemura23.uwb.oob.OobTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Wi-Fi Aware OOB: 親機 = Publisher、子機 = Subscriber. Only discovery-session messages are used;
 * no data path (NDP) is created. Permissions are checked before the experiment starts.
 */
class WifiAwareOobTransport(
    context: Context,
    private val onEvent: (OobEvent) -> Unit,
) : OobTransport {
    private val manager: WifiAwareManager = context.getSystemService(WifiAwareManager::class.java)
        ?.takeIf { it.isAvailable }
        ?: throw IllegalStateException("Wi-Fi Aware が利用できません")
    private val handler = Handler(Looper.getMainLooper())
    private val linkEstablished = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val nextMessageId = AtomicInteger(1)

    // The first peer we talk to; messages/discoveries from any other peer are ignored.
    private val claimedPeer = AtomicReference<PeerHandle?>(null)

    @Volatile private var pending: CompletableDeferred<*>? = null
    @Volatile private var awareSession: WifiAwareSession? = null
    @Volatile private var discoverySession: DiscoverySession? = null

    // 親機 state, written from Wi-Fi Aware callbacks.
    @Volatile private var receivedControlee: ControleeInfo? = null
    @Volatile private var sentMessageId: Int? = null

    // ---- 親機 ----

    override suspend fun exchangeAsController(build: (ControleeInfo) -> ControllerInfo): ControleeInfo {
        val result = CompletableDeferred<ControleeInfo>().also { pending = it }

        val discoveryCallback = object : DiscoverySessionCallback() {
            override fun onPublishStarted(session: PublishDiscoverySession) {
                if (!adopt(session)) return
                log("パブリッシュ開始")
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                if (closed.get() || !claim(peerHandle) || sentMessageId != null) return
                log("子機情報を受信 (${message.size} bytes)")
                try {
                    val info = OobCodec.decodeControlee(message)
                    val bytes = OobCodec.encode(build(info))
                    val id = nextMessageId.getAndIncrement()
                    receivedControlee = info
                    sentMessageId = id
                    emitLinkEstablished()
                    log("親機情報を送信 (${bytes.size} bytes)")
                    discoverySession?.sendMessage(peerHandle, id, bytes)
                        ?: fail(result, "探索セッションがありません")
                } catch (e: Exception) {
                    result.completeExceptionally(e)
                }
            }

            override fun onMessageSendSucceeded(messageId: Int) {
                if (messageId != sentMessageId) return
                val info = receivedControlee ?: return
                if (result.complete(info)) log("親機情報の送信完了")
            }

            override fun onMessageSendFailed(messageId: Int) {
                if (messageId == sentMessageId) fail(result, "親機情報の送信に失敗しました")
            }

            override fun onSessionConfigFailed() = fail(result, "パブリッシュ設定に失敗しました")

            override fun onSessionTerminated() = fail(result, "パブリッシュセッションが終了しました")
        }

        attach(result) { session ->
            val config = PublishConfig.Builder().setServiceName(SERVICE_NAME).build()
            session.publish(config, discoveryCallback, handler)
        }
        return awaitPending(result)
    }

    // ---- 子機 ----

    override suspend fun exchangeAsControlee(mine: ControleeInfo): ControllerInfo {
        val result = CompletableDeferred<ControllerInfo>().also { pending = it }
        val sentId = AtomicInteger(0)

        val discoveryCallback = object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                if (!adopt(session)) return
                log("サブスクライブ開始")
            }

            // The pre-API-34 overload: it is the only one invoked on API 33.
            override fun onServiceDiscovered(
                peer: PeerHandle,
                serviceSpecificInfo: ByteArray?,
                matchFilter: List<ByteArray>?,
            ) {
                if (closed.get() || !claimedPeer.compareAndSet(null, peer)) return
                log("親機を発見、子機情報を送信")
                emitLinkEstablished()
                try {
                    val id = nextMessageId.getAndIncrement()
                    sentId.set(id)
                    discoverySession?.sendMessage(peer, id, OobCodec.encode(mine))
                        ?: fail(result, "探索セッションがありません")
                } catch (e: Exception) {
                    result.completeExceptionally(e)
                }
            }

            override fun onMessageSendSucceeded(messageId: Int) {
                if (messageId == sentId.get()) log("子機情報の送信完了")
            }

            override fun onMessageSendFailed(messageId: Int) {
                if (messageId == sentId.get()) fail(result, "子機情報の送信に失敗しました")
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                if (peerHandle != claimedPeer.get()) return
                log("親機情報を受信 (${message.size} bytes)")
                try {
                    result.complete(OobCodec.decodeController(message))
                } catch (e: Exception) {
                    result.completeExceptionally(e)
                }
            }

            override fun onSessionConfigFailed() = fail(result, "サブスクライブ設定に失敗しました")

            override fun onSessionTerminated() = fail(result, "サブスクライブセッションが終了しました")
        }

        attach(result) { session ->
            val config = SubscribeConfig.Builder().setServiceName(SERVICE_NAME).build()
            session.subscribe(config, discoveryCallback, handler)
        }
        return awaitPending(result)
    }

    // ---- 共通 ----

    private fun attach(result: CompletableDeferred<*>, startDiscovery: (WifiAwareSession) -> Unit) {
        log("Wi-Fi Aware アタッチ開始")
        manager.attach(object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession) {
                awareSession = session
                if (closed.get()) {
                    // close() raced with attach: release the session that arrived after it ran.
                    session.close()
                    return
                }
                log("アタッチ成功")
                try {
                    startDiscovery(session)
                } catch (e: Exception) {
                    result.completeExceptionally(e)
                }
            }

            override fun onAttachFailed() = fail(result, "Wi-Fi Aware のアタッチに失敗しました")

            override fun onAwareSessionTerminated() = fail(result, "Wi-Fi Aware セッションが終了しました")
        }, handler)
    }

    /** Stores [session]; returns false (and closes it) if [close] already ran. */
    private fun adopt(session: DiscoverySession): Boolean {
        discoverySession = session
        if (closed.get()) {
            session.close()
            return false
        }
        return true
    }

    private fun claim(peer: PeerHandle): Boolean = claimedPeer.compareAndSet(null, peer) || claimedPeer.get() == peer

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pending?.completeExceptionally(IllegalStateException("Wi-Fi Aware OOB は閉じられました"))
        release()
        log("Wi-Fi Aware OOB 終了")
    }

    private fun release() {
        discoverySession?.close()
        discoverySession = null
        awareSession?.close()
        awareSession = null
    }

    private suspend fun <T> awaitPending(result: CompletableDeferred<T>): T =
        try {
            if (closed.get()) {
                // close() raced with setup: release what was started after it ran.
                release()
                throw IllegalStateException("Wi-Fi Aware OOB は閉じられました")
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

    private fun log(message: String) = onEvent(OobEvent.Log(message))

    private companion object {
        const val SERVICE_NAME = "uwb-oob"
    }
}
