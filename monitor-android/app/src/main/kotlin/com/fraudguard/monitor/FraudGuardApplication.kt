package com.fraudguard.monitor

import android.app.Application
import android.content.Context
import com.fraudguard.monitor.appinstall.AppInstallScanner
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

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        pairingRepository = PairingRepositoryImpl(this)
        eventReporter = EventReporter(database.eventDao(), pairingRepository)
        appInstallScanner = AppInstallScanner(
            context = this,
            // インストール済みパッケージの一覧は秘匿情報ではないため通常のSharedPreferencesで足りる。
            prefs = getSharedPreferences("fraudguard_app_scan", Context.MODE_PRIVATE),
            eventReporter = eventReporter,
        )

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
}
