package com.panhub.core.storage

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 用一个 JSON 文件保存所有账号。
 *
 * 为什么不是数据库：账号数量是个位数，读写频率极低（只在登录/切换时），
 * 引入 Room 只会增加构建时间和迁移负担。整体读、整体写足够了。
 *
 * 内存缓存里始终是**明文**，加密只在落盘/读盘这一层做——这是不变式：
 * 缓存里的 [AccountRecord.secret] 一定是可直接用的凭据，加密后的字符串
 * 永远不会离开 [persist] / [readFile]。
 */
class JsonAccountStore(
    private val file: File,
    private val cipher: SecretCipher,
) : AccountStore {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val mutex = Mutex()
    private val cache = MutableStateFlow<List<AccountRecord>>(emptyList())
    private var loaded = false

    override fun observeAll(): Flow<List<AccountRecord>> =
        cache.map { records -> records.sortedByDescending { it.lastUsedAt } }

    override suspend fun list(): List<AccountRecord> = mutex.withLock {
        ensureLoaded()
        cache.value.sortedByDescending { it.lastUsedAt }
    }

    override suspend fun get(accountId: String): AccountRecord? = mutex.withLock {
        ensureLoaded()
        cache.value.firstOrNull { it.id == accountId }
    }

    override suspend fun save(record: AccountRecord) = mutex.withLock {
        ensureLoaded()
        val next = cache.value.filterNot { it.id == record.id } + record
        persist(next)
    }

    override suspend fun delete(accountId: String) = mutex.withLock {
        ensureLoaded()
        persist(cache.value.filterNot { it.id == accountId })
    }

    override suspend fun touch(accountId: String, at: Long) = mutex.withLock {
        ensureLoaded()
        val next = cache.value.map { if (it.id == accountId) it.copy(lastUsedAt = at) else it }
        if (next != cache.value) persist(next)
    }

    /** 只有第一次访问才读盘，之后都走内存缓存。 */
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        cache.value = readFile()
    }

    private fun readFile(): List<AccountRecord> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val stored = json.decodeFromString(StoredAccounts.serializer(), file.readText())
            stored.accounts.map { record ->
                record.copy(secret = record.secret?.let { cipher.decrypt(it) })
            }
        }.getOrElse { emptyList() }
    }

    private fun persist(records: List<AccountRecord>) {
        cache.value = records
        val encrypted = StoredAccounts(
            accounts = records.map { it.copy(secret = it.secret?.let(cipher::encrypt)) },
        )
        file.parentFile?.mkdirs()
        // 先写临时文件再改名，避免写到一半掉电把账号库写坏。
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(StoredAccounts.serializer(), encrypted))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }
}

@kotlinx.serialization.Serializable
internal data class StoredAccounts(
    val accounts: List<AccountRecord> = emptyList(),
    /** 格式版本号，将来迁移用。 */
    val version: Int = 1,
)
