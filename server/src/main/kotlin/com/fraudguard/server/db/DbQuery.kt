package com.fraudguard.server.db

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

/** JDBC(Exposed)はブロッキングAPIのため、IOディスパッチャ上でトランザクションを実行する共通ヘルパー。 */
suspend fun <T> dbQuery(block: suspend () -> T): T =
    newSuspendedTransaction(Dispatchers.IO) { block() }
