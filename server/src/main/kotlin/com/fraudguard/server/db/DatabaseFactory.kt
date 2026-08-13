package com.fraudguard.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import javax.sql.DataSource

object DatabaseFactory {

    fun init(jdbcUrl: String, user: String, password: String): DataSource {
        val dataSource = createDataSource(jdbcUrl, user, password)
        // validateMigrationNamingを立てるのは必須。既定では、命名規則に合わないと判断した
        // マイグレーションをFlywayはINFOログ1行だけ出して読み飛ばし、そのまま起動してしまう。
        // 空のスキーマのままAPIが200を返す状態は、この仕組みでは最も危険な壊れ方
        // (家族から見ると「動いている」が、イベントは1件も保存されない)。
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .validateMigrationNaming(true)
            .load()
        // 「見つかった数」を確認する。「適用した数」ではない(適用済みなら0件が正常)。
        check(flyway.info().all().isNotEmpty()) {
            "No migrations found on the classpath. The packaged artifact is missing db/migration."
        }
        flyway.migrate()
        Database.connect(dataSource)
        return dataSource
    }

    private fun createDataSource(jdbcUrl: String, user: String, password: String): DataSource {
        val config = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = user
            this.password = password
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 10
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
        }
        return HikariDataSource(config)
    }
}
