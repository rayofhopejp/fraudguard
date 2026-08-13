package com.fraudguard.monitor

import android.app.Application
import android.content.Context
import com.fraudguard.monitor.appinstall.AppInstallScanner
import com.fraudguard.monitor.appinstall.AppLaunchDetector
import com.fraudguard.monitor.call.AppCallRegistry
import com.fraudguard.monitor.call.FraudGuardInCallService
import com.fraudguard.monitor.data.EventReporter
import com.fraudguard.monitor.data.local.AppDatabase
import com.fraudguard.monitor.heartbeat.HeartbeatWorker
import com.fraudguard.monitor.pairing.PairingRepository
import com.fraudguard.monitor.pairing.PairingRepositoryImpl
import com.fraudguard.monitor.sync.EventSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FraudGuardApplication : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var pairingRepository: PairingRepository
        private set

    lateinit var eventReporter: EventReporter
        private set

    lateinit var appInstallScanner: AppInstallScanner
        private set

    lateinit var appLaunchDetector: AppLaunchDetector
        private set

    /** requirements.md 10.3章: LINE等のアプリ内通話。通知経由で追跡する。 */
    lateinit var appCallRegistry: AppCallRegistry
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        pairingRepository = PairingRepositoryImpl(this)
        eventReporter = EventReporter(database.eventDao(), pairingRepository)
        // インストール済みパッケージの一覧は秘匿情報ではないため通常のSharedPreferencesで足りる。
        val appScanPrefs = getSharedPreferences("fraudguard_app_scan", Context.MODE_PRIVATE)
        appLaunchDetector = AppLaunchDetector(this, appScanPrefs, eventReporter)
        appInstallScanner = AppInstallScanner(
            context = this,
            prefs = appScanPrefs,
            eventReporter = eventReporter,
            launchDetector = appLaunchDetector,
        )

        appCallRegistry = AppCallRegistry(eventReporter, CoroutineScope(Dispatchers.IO))

        // requirements.md 11章: アプリ起動時にも新規インストールを走査する。
        // ペアリング直後にベースラインを確実に作るためと、端末再起動後などに
        // 定期スキャン(最大15分後)を待たずに検知するための経路。
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { appInstallScanner.scan() }
        }

        // requirements.md 35章[v2]: 監視継続性のハートビートを定期実行する。
        HeartbeatWorker.schedule(this)
        // requirements.md 24章: 未送信イベントの再送をWorkManagerで保証する。
        EventSyncWorker.schedule(this)
    }

    /**
     * requirements.md 8.1章: 遠隔切断コマンドが照合する「現在進行中の通話」。
     * 通常の電話(Telecom)とアプリ内通話(通知経由)の両方を含める。
     */
    fun activeCallIds(): Set<String> =
        setOfNotNull(FraudGuardInCallService.activeCallId()) + appCallRegistry.activeCallIds()

    /**
     * requirements.md 8.1章, 10.3章: 通話の切断。通常の電話はTelecom経由、
     * LINE等のアプリ内通話は通知が持つ切断用PendingIntent経由と、経路が異なるため両方を試す。
     */
    fun disconnectCall(callId: String): Boolean =
        FraudGuardInCallService.instance?.disconnect(callId) == true || appCallRegistry.hangUp(callId)
}
