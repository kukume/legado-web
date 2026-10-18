package io.legado.web.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.legado.web.config.DbSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.sql.Connection

object DatabaseFactory {

    private val logger = LoggerFactory.getLogger(DatabaseFactory::class.java)

    lateinit var database: Database
        private set

    var isSqlite = true
        private set

    fun init(settings: DbSettings) {
        isSqlite = settings.type == "sqlite"
        val config = HikariConfig().apply {
            jdbcUrl = settings.url
            if (isSqlite) {
                driverClassName = "org.sqlite.JDBC"
                // SQLite 只允许单写，使用单连接避免 "database is locked"
                maximumPoolSize = 1
                addDataSourceProperty("journal_mode", "WAL")
                addDataSourceProperty("busy_timeout", "10000")
                addDataSourceProperty("synchronous", "NORMAL")
            } else {
                driverClassName = "org.postgresql.Driver"
                username = settings.user
                password = settings.password
                maximumPoolSize = settings.maxPoolSize
            }
            isAutoCommit = false
            transactionIsolation = if (isSqlite) "TRANSACTION_SERIALIZABLE" else "TRANSACTION_READ_COMMITTED"
            validate()
        }
        val dataSource = HikariDataSource(config)
        database = Database.connect(dataSource)
        if (isSqlite) {
            org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.manager.defaultIsolationLevel =
                Connection.TRANSACTION_SERIALIZABLE
        }
        transaction(database) {
            SchemaUtils.create(*allTables)
            // 补齐新增的列（升级兼容）
            val stmts = SchemaUtils.addMissingColumnsStatements(*allTables)
            stmts.forEach { exec(it) }
        }
        logger.info("数据库初始化完成: {} ({})", settings.type, settings.url.substringBefore('?'))
    }
}

/** 在 IO 线程执行数据库事务 */
suspend fun <T> dbQuery(block: JdbcTransaction.() -> T): T = withContext(Dispatchers.IO) {
    transaction(DatabaseFactory.database) { block() }
}

/** 同步事务（用于引擎回调等非挂起场景） */
fun <T> dbBlocking(block: JdbcTransaction.() -> T): T = transaction(DatabaseFactory.database) { block() }
