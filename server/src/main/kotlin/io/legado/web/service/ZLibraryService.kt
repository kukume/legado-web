package io.legado.web.service

import com.google.gson.Gson
import io.legado.web.auth.BadRequestException
import io.legado.web.auth.ForbiddenException
import io.legado.web.auth.UserPrincipal
import io.legado.web.db.UserPrefs
import io.legado.web.db.dbBlocking
import io.legado.web.zlib.Account
import io.legado.web.zlib.Book
import io.legado.web.zlib.ZLibraryClient
import io.legado.web.zlib.ZLibraryException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Z-Library 搜索与下载。功能由管理员开关（[SettingService.ZLIB_ENABLED]）：开启后，搜索和下载都需要先登录
 * Z-Library 账号（匿名使用会很快触及站点按 IP 的每日上限）。管理员可在后台配置全局代理（可留空直连）。
 * 下载在后台进行，完成后自动把 EPUB 导入书架。
 */
class ZLibraryService(
    private val settings: SettingService,
    private val localBookService: LocalBookService,
) {
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 每个用户的下载任务（保存在内存，重启后清空）；LinkedHashMap 保持加入顺序
    private val jobs = ConcurrentHashMap<Long, LinkedHashMap<String, DownloadJob>>()

    // 全局下载队列：同一时间只下载一个，其余排队（Channel + 单个消费协程，先进先出）
    private data class Task(val user: UserPrincipal, val book: Book, val groupId: Long, val job: DownloadJob)
    private val queue = Channel<Task>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (task in queue) runDownload(task)
        }
    }

    companion object {
        private const val SESSION_KEY = "_zlibSession"
        private const val MAX_JOBS_PER_USER = 50
    }

    private data class StoredSession(val userId: String, val userKey: String, val email: String)

    data class AccountDto(
        val email: String,
        val premium: Boolean,
        val premiumUntil: String,
        val downloadsToday: Int,
        val downloadsLimit: Int,
        /** 额度重置倒计时（站点原文，可能为空） */
        val downloadsReset: String = "",
    )

    data class StatusDto(
        /** 管理员是否开启了功能 */
        val enabled: Boolean,
        val host: String,
        /** 是否已登录 Z-Library（下载需要登录） */
        val loggedIn: Boolean,
        val account: AccountDto?,
    )

    data class DownloadJob(
        val id: String,
        val bookId: String,
        val title: String,
        val author: String,
        val extension: String,
        @Volatile var status: String = "queued", // queued | downloading | importing | done | error
        @Volatile var receivedBytes: Long = 0,
        @Volatile var totalBytes: Long = 0,
        @Volatile var error: String? = null,
        @Volatile var importedBookId: Long? = null,
        val createdAt: Long = System.currentTimeMillis(),
    )

    // -- 设置与状态 -------------------------------------------------------
    private fun enabled() = settings.getBool(SettingService.ZLIB_ENABLED)

    private fun host() = settings.get(SettingService.ZLIB_HOST).trim().ifEmpty { ZLibraryClient.DEFAULT_HOST }

    /** 全局代理，由管理员在后台配置；留空表示不使用代理（直连） */
    private fun adminProxy(): String? = settings.get(SettingService.ZLIB_PROXY).trim().ifEmpty { null }

    private fun newClientRaw() = ZLibraryClient(host(), adminProxy())

    fun requireEnabled() {
        if (!enabled()) throw ForbiddenException("管理员未开启 Z-Library 功能")
    }

    /** 测试代理（空表示直连）能否连上站点并通过反爬；失败时抛 [BadRequestException]。管理员用 */
    suspend fun testProxy(proxy: String?): Unit = withContext(Dispatchers.IO) {
        val p = proxy?.trim().orEmpty()
        val client = try {
            ZLibraryClient(host(), p.ifEmpty { null })
        } catch (e: Exception) {
            throw BadRequestException(e.message ?: "代理格式错误")
        }
        try {
            client.ping()
        } catch (e: Exception) {
            throw BadRequestException(e.message ?: "连接失败")
        }
    }

    private fun getSession(userId: Long): StoredSession? = dbBlocking {
        UserPrefs.selectAll().where { (UserPrefs.userId eq userId) and (UserPrefs.key eq SESSION_KEY) }
            .firstOrNull()?.get(UserPrefs.value)
    }?.let { runCatching { gson.fromJson(it, StoredSession::class.java) }.getOrNull() }

    private fun putPref(userId: Long, key: String, value: String) = dbBlocking {
        UserPrefs.upsert {
            it[UserPrefs.userId] = userId
            it[UserPrefs.key] = key
            it[UserPrefs.value] = value
        }
        Unit
    }

    private fun deletePref(userId: Long, key: String) = dbBlocking {
        UserPrefs.deleteWhere { (UserPrefs.userId eq userId) and (UserPrefs.key eq key) }
        Unit
    }

    fun status(user: UserPrincipal): StatusDto {
        val session = getSession(user.id)
        return StatusDto(
            enabled = enabled(),
            host = host(),
            loggedIn = session != null,
            account = session?.let { AccountDto(it.email, false, "", 0, 0) },
        )
    }

    // -- 登录 -------------------------------------------------------------
    suspend fun login(user: UserPrincipal, email: String, password: String): AccountDto = withContext(Dispatchers.IO) {
        requireEnabled()
        if (email.isBlank() || password.isBlank()) throw BadRequestException("请输入邮箱和密码")
        val client = newClientRaw()
        val acct = try {
            client.login(email, password)
        } catch (e: ZLibraryException) {
            throw BadRequestException(e.message ?: "登录失败")
        }
        putPref(user.id, SESSION_KEY, gson.toJson(StoredSession(acct.id.toString(), acct.key, acct.email)))
        acct.toDto()
    }

    suspend fun logout(user: UserPrincipal): Unit = withContext(Dispatchers.IO) {
        // 先通知 Z-Library 注销，使其服务端会话失效（尽力而为，失败不影响本地退出）
        getSession(user.id)?.let { s ->
            runCatching { newClientRaw().logout(s.userId, s.userKey) }
        }
        deletePref(user.id, SESSION_KEY)
    }

    // -- 搜索 -------------------------------------------------------------
    suspend fun search(
        user: UserPrincipal,
        query: String,
        page: Int,
        order: String,
        extensions: List<String>,
        languages: List<String>,
    ): List<Book> = withContext(Dispatchers.IO) {
        requireEnabled()
        // 搜索也需要登录
        val session = getSession(user.id) ?: throw ForbiddenException("请先登录 Z-Library")
        if (query.isBlank()) throw BadRequestException("请输入搜索关键词")
        val client = newClient(session)
        try {
            client.search(query, page = page, order = order, extensions = extensions, languages = languages)
        } catch (e: ZLibraryException) {
            throw BadRequestException(e.message ?: "搜索失败")
        }
    }

    /** 带上已登录会话的客户端 */
    private fun newClient(session: StoredSession): ZLibraryClient {
        val client = newClientRaw()
        runCatching { client.useSession(session.userId, session.userKey, session.email) }
        return client
    }

    // -- 下载任务 ---------------------------------------------------------
    fun jobs(user: UserPrincipal): List<DownloadJob> =
        jobs[user.id]?.values?.sortedByDescending { it.createdAt } ?: emptyList()

    fun startDownload(user: UserPrincipal, book: Book, groupId: Long): DownloadJob {
        requireEnabled()
        // 下载需要登录 Z-Library（匿名下载会很快触及站点按 IP 的每日上限）
        if (getSession(user.id) == null) throw ForbiddenException("下载需要先登录 Z-Library")
        if (book.extension.isNotEmpty() && !book.extension.equals("epub", true)) {
            throw BadRequestException("只支持下载 EPUB 格式")
        }
        // 下载目标必须是配置的 Z-Library 站点，避免被构造的地址访问任意主机
        val target = book.download.ifEmpty { book.href }
        val base = host().toHttpUrlOrNull()
        val t = target.toHttpUrlOrNull()
        if (base == null || t == null || t.host != base.host) {
            throw BadRequestException("下载地址无效")
        }
        val job = DownloadJob(
            id = UUID.randomUUID().toString(),
            bookId = book.id,
            title = book.title,
            author = book.author,
            extension = "epub",
        )
        val userJobs = jobs.getOrPut(user.id) { LinkedHashMap() }
        synchronized(userJobs) {
            userJobs[job.id] = job
            // 超出上限时移除最旧的已完成/失败任务
            if (userJobs.size > MAX_JOBS_PER_USER) {
                userJobs.entries
                    .filter { it.value.status == "done" || it.value.status == "error" }
                    .sortedBy { it.value.createdAt }
                    .take(userJobs.size - MAX_JOBS_PER_USER)
                    .forEach { userJobs.remove(it.key) }
            }
        }
        // 加入全局下载队列（同一时间只下载一个，其余排队）
        queue.trySend(Task(user, book, groupId, job))
        return job
    }

    private suspend fun runDownload(task: Task) {
        val (user, book, groupId, job) = task
        job.status = "downloading"
        val tmp = File(System.getProperty("java.io.tmpdir"), "zlib-${job.id}")
        try {
            val session = getSession(user.id) ?: throw ZLibraryException("请先登录 Z-Library")
            val client = newClient(session)
            val file = withContext(Dispatchers.IO) {
                client.download(book, tmp) { received, total ->
                    job.receivedBytes = received
                    job.totalBytes = total
                }
            }
            job.status = "importing"
            val name = book.title.ifBlank { file.name }.let { if (it.endsWith(".epub", true)) it else "$it.epub" }
            val dto = localBookService.import(
                user, file, name,
                LocalBookService.ImportOptions(groupId = groupId, force = true),
            )
            job.importedBookId = dto.id
            job.status = "done"
        } catch (e: Exception) {
            job.status = "error"
            job.error = e.message ?: "下载失败"
        } finally {
            withContext(Dispatchers.IO) { runCatching { tmp.delete() } }
        }
    }

    private fun Account.toDto() = AccountDto(email, premium, premiumUntil, downloadsToday, downloadsLimit, downloadsReset)

    /**
     * 已登录时联网获取账号额度（已用 / 额度、重置倒计时）。未登录返回 null；会话失效时清除本地会话并返回 null。
     * 需要联网（过反爬），由前端在显示“已登录”后单独请求
     */
    suspend fun account(user: UserPrincipal): AccountDto? = withContext(Dispatchers.IO) {
        requireEnabled()
        val session = getSession(user.id) ?: return@withContext null
        val client = newClientRaw()
        try {
            // useSession 读 /profile（校验会话，拿到已用/额度/会员），再读下载记录页补上重置倒计时
            client.useSession(session.userId, session.userKey, session.email)
            runCatching { client.refreshQuota() }
            client.account?.toDto()
        } catch (e: ZLibraryException) {
            deletePref(user.id, SESSION_KEY)
            null
        }
    }
}
