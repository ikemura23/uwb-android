package com.ikemura23.uwb.uwb

import android.content.Context
import androidx.core.uwb.RangingParameters
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbAddress
import androidx.core.uwb.UwbClientSessionScope
import androidx.core.uwb.UwbComplexChannel
import androidx.core.uwb.UwbControllerSessionScope
import androidx.core.uwb.UwbManager
import kotlinx.coroutines.flow.Flow

interface UwbSession {
    val localAddress: UwbAddress

    /** 親機（Controller）のみ非 null。 */
    val complexChannel: UwbComplexChannel?

    fun prepare(params: RangingParameters): Flow<RangingResult>
}

interface UwbSessions {
    suspend fun openController(): UwbSession
    suspend fun openControlee(): UwbSession
}

class AndroidUwbSessions(context: Context) : UwbSessions {
    private val manager = UwbManager.createInstance(context)

    override suspend fun openController(): UwbSession {
        checkAvailable()
        val scope = manager.controllerSessionScope()
        return ScopeSession(scope, scope.uwbComplexChannel)
    }

    override suspend fun openControlee(): UwbSession {
        checkAvailable()
        return ScopeSession(manager.controleeSessionScope(), null)
    }

    private suspend fun checkAvailable() {
        check(manager.isAvailable()) { "UWB が利用できません" }
    }

    private class ScopeSession(
        private val scope: UwbClientSessionScope,
        override val complexChannel: UwbComplexChannel?,
    ) : UwbSession {
        override val localAddress: UwbAddress get() = scope.localAddress
        override fun prepare(params: RangingParameters): Flow<RangingResult> = scope.prepareSession(params)
    }
}
