package com.ikemura23.uwb.oob

sealed interface OobEvent {
    data class Log(val message: String) : OobEvent
    data object LinkEstablished : OobEvent
}

/**
 * Out-of-band channel used to exchange UWB parameters.
 * Implementations take an `onEvent` callback in their constructor (it may be invoked from any thread)
 * and must emit [OobEvent.LinkEstablished] exactly once when the link comes up.
 */
interface OobTransport {
    /** 子機の情報を受け取り、build の結果を送信し終えてから返す */
    suspend fun exchangeAsController(build: (ControleeInfo) -> ControllerInfo): ControleeInfo

    /** 自分の情報を送り、親機の情報を受け取ってから返す */
    suspend fun exchangeAsControlee(mine: ControleeInfo): ControllerInfo

    /** 冪等。広告・接続・セッションを全て解放する */
    fun close()
}
