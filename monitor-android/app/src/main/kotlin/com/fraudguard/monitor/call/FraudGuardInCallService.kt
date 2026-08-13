package com.fraudguard.monitor.call

import android.content.Intent
import android.telecom.Call
import android.telecom.InCallService
import android.telecom.VideoProfile
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * requirements.md 8章, 4.3章[v2]: ROLE_DIALER取得後に有効化するInCallService。
 * デフォルト電話アプリになると、着信中/通話中のシステム標準UIは表示されなくなるため、
 * このサービスがCallの追加/削除を検知してInCallActivityを起動し、
 * requirements.md 8.1章の遠隔切断コマンドが参照する「現在ACTIVEな通話」の実体もここで保持する。
 */
class FraudGuardInCallService : InCallService() {

    companion object {
        @Volatile
        var instance: FraudGuardInCallService? = null
            private set

        private val _calls = MutableStateFlow<List<TrackedCall>>(emptyList())

        /** requirements.md ui/incall: InCallActivityが画面表示に使う、Android非依存の通話一覧。 */
        val calls: StateFlow<List<TrackedCall>> = _calls.asStateFlow()

        /** requirements.md 8.1章: RemoteCommandExecutorが「対象通話が現在ACTIVEであること」を検証する際に使う。 */
        fun activeCallId(): String? = _calls.value.firstOrNull { it.state == Call.STATE_ACTIVE }?.callId
    }

    private val activeCalls = mutableMapOf<String, Call>()

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            publishState()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        activeCalls.values.forEach { it.unregisterCallback(callCallback) }
        instance = null
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val callId = UUID.randomUUID().toString()
        activeCalls[callId] = call
        call.registerCallback(callCallback)
        publishState()

        // requirements.md 4.3章[v2]: システム標準の着信/通話中UIが出ないため、自前の画面を起動する。
        startActivity(
            Intent(this, InCallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callCallback)
        activeCalls.entries.removeAll { it.value == call }
        publishState()
    }

    private fun publishState() {
        _calls.value = activeCalls.map { (callId, call) ->
            TrackedCall(
                callId = callId,
                phoneNumber = call.details?.handle?.schemeSpecificPart,
                state = call.state,
            )
        }
    }

    /** requirements.md 8.1章: 呼び出し元(command.RemoteCommandExecutor)で検証済みであることが前提。 */
    fun disconnect(callId: String): Boolean {
        val call = activeCalls[callId] ?: return false
        call.disconnect()
        return true
    }

    fun answer(callId: String): Boolean {
        val call = activeCalls[callId] ?: return false
        call.answer(VideoProfile.STATE_AUDIO_ONLY)
        return true
    }

    fun reject(callId: String): Boolean {
        val call = activeCalls[callId] ?: return false
        call.reject(false, null)
        return true
    }
}

/** InCallActivity(Compose)がandroid.telecom.Callに依存せずに描画できるようにするための投影。 */
data class TrackedCall(
    val callId: String,
    val phoneNumber: String?,
    val state: Int,
)
