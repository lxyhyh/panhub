package com.panhub.core.storage

import kotlinx.coroutines.flow.Flow

/**
 * 已登录账号的持久化。
 *
 * 抽成接口而不是直接用 DataStore：上层（ViewModel）只关心"读写账号"，
 * 不关心底下是 DataStore、SQLite 还是别的。测试里可以塞内存实现。
 */
interface AccountStore {

    /** 所有已保存的账号，按最近使用时间倒序。 */
    fun observeAll(): Flow<List<AccountRecord>>

    suspend fun list(): List<AccountRecord>

    suspend fun get(accountId: String): AccountRecord?

    /** 新增或覆盖。 */
    suspend fun save(record: AccountRecord)

    suspend fun delete(accountId: String)

    /** 记录一次使用，用于把最近用过的账号排到前面。 */
    suspend fun touch(accountId: String, at: Long)

    /**
     * 改账号的备注名。传 null 表示清空。
     *
     * 单独一个方法而不是「读出来改完再 save」：整条 save 会把
     * 凭据、创建时间一并覆写，调用方只想改名字却必须先把秘密读进内存，
     * 这里能不做就不做。
     */
    suspend fun setAlias(accountId: String, alias: String?)
}
