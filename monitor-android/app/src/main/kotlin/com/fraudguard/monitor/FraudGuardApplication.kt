package com.fraudguard.monitor

import android.app.Application
import com.fraudguard.monitor.data.EventReporter
import com.fraudguard.monitor.data.local.AppDatabase
import com.fraudguard.monitor.heartbeat.HeartbeatWorker
import com.fraudguard.monitor.pairing.PairingRepository
import com.fraudguard.monitor.pairing.PairingRepositoryImpl
import com.fraudguard.monitor.sync.EventSyncWorker

class FraudGuardApplication : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var pairingRepository: PairingRepository
        private set

    lateinit var eventReporter: EventReporter
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        pairingRepository = PairingRepositoryImpl(this)
        eventReporter = EventReporter(database.eventDao(), pairingRepository)

        // requirements.md 35章[v2]: 監視継続性のハートビートを定期実行する。
        HeartbeatWorker.schedule(this)
        // requirements.md 24章: 未送信イベントの再送をWorkManagerで保証する。
        EventSyncWorker.schedule(this)
    }
}
