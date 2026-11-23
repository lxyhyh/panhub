package com.panhub.core.storage

import com.panhub.core.model.AccountId
import com.panhub.provider.api.ProviderCredentials
import com.panhub.provider.api.ProviderSession
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JsonAccountStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val cipher = AesGcmCipher { ByteArray(32) { 7 } }

    private fun store(): JsonAccountStore = JsonAccountStore(File(tmp.root, "accounts.json"), cipher)

    private fun tokenSession(id: String, token: String) = ProviderSession(
        accountId = AccountId(id),
        credentials = ProviderCredentials.Token(accessToken = token),
        displayName = "测试账号",
    )

    @Test
    fun `save then list returns the record`() = runTest {
        val store = store()
        store.save(AccountMapper.toRecord(tokenSession("nutstore:a@b.c", "tok-1"), now = 100L))

        val all = store.list()
        assertEquals(1, all.size)
        assertEquals("nutstore:a@b.c", all.single().id)
        assertEquals("nutstore", all.single().providerId)
        assertEquals("tok-1", all.single().secret)
    }

    @Test
    fun `secret is encrypted on disk`() = runTest {
        val store = store()
        store.save(AccountMapper.toRecord(tokenSession("pan123:u1", "super-secret"), now = 1L))

        val raw = File(tmp.root, "accounts.json").readText()
        assertFalse("明文 token 不能出现在磁盘上", raw.contains("super-secret"))
        assertTrue("文件应该是一份加密后的 JSON", raw.contains("accounts"))
    }

    @Test
    fun `encrypted file is readable after reopen`() = runTest {
        store().save(AccountMapper.toRecord(tokenSession("pan123:u1", "tok-round-trip"), now = 1L))

        // 新实例模拟进程重启：内存缓存是空的，必须走解密路径。
        val reopened = store()
        assertEquals("tok-round-trip", reopened.get("pan123:u1")?.secret)
    }

    @Test
    fun `save twice with the same id overwrites`() = runTest {
        val store = store()
        store.save(AccountMapper.toRecord(tokenSession("pan123:u1", "old"), now = 1L))
        store.save(AccountMapper.toRecord(tokenSession("pan123:u1", "new"), now = 2L))

        assertEquals(1, store.list().size)
        assertEquals("new", store.get("pan123:u1")?.secret)
    }

    @Test
    fun `delete removes only the target`() = runTest {
        val store = store()
        store.save(AccountMapper.toRecord(tokenSession("a:1", "t1"), now = 1L))
        store.save(AccountMapper.toRecord(tokenSession("b:2", "t2"), now = 2L))

        store.delete("a:1")

        assertNull(store.get("a:1"))
        assertEquals("t2", store.get("b:2")?.secret)
    }

    @Test
    fun `touch reorders by lastUsedAt`() = runTest {
        val store = store()
        store.save(AccountMapper.toRecord(tokenSession("a:1", "t1"), now = 10L))
        store.save(AccountMapper.toRecord(tokenSession("b:2", "t2"), now = 20L))
        assertEquals(listOf("b:2", "a:1"), store.list().map { it.id })

        store.touch("a:1", 30L)

        assertEquals(listOf("a:1", "b:2"), store.list().map { it.id })
    }

    @Test
    fun `corrupt file degrades to empty instead of crashing`() = runTest {
        val file = File(tmp.root, "accounts.json")
        tmp.root.mkdirs()
        file.writeText("{ this is not json")

        assertTrue(store().list().isEmpty())
    }

    @Test
    fun `password based account keeps username and password`() = runTest {
        val store = store()
        val session = ProviderSession(
            accountId = AccountId("webdav:me@dav"),
            credentials = ProviderCredentials.Password("me@dav", "app-pass"),
            displayName = "坚果云",
        )
        store.save(AccountMapper.toRecord(session, now = 5L))

        val back = AccountMapper.toSession(store.get("webdav:me@dav")!!)
        assertEquals("me@dav", (back.credentials as ProviderCredentials.Password).username)
        assertEquals("app-pass", (back.credentials as ProviderCredentials.Password).password)
        assertEquals("坚果云", back.displayName)
    }

    @Test
    fun `alias is stored separately from the provider display name`() = runTest {
        val store = store()
        store.save(AccountMapper.toRecord(tokenSession("nutstore:a@b.c", "tok"), now = 1L))

        store.setAlias("nutstore:a@b.c", "公司备份盘")

        val record = store.get("nutstore:a@b.c")!!
        assertEquals("公司备份盘", record.alias)
        // 服务商报上来的名字不能被备注盖掉：下次登录还要用它做兜底。
        assertEquals("测试账号", record.displayName)
    }

    @Test
    fun `clearing the alias keeps the account`() = runTest {
        val store = store()
        store.save(AccountMapper.toRecord(tokenSession("nutstore:a@b.c", "tok"), now = 1L))
        store.setAlias("nutstore:a@b.c", "公司备份盘")

        store.setAlias("nutstore:a@b.c", null)

        assertNull(store.get("nutstore:a@b.c")!!.alias)
        assertEquals(1, store.list().size)
    }

    @Test
    fun `renaming an unknown account changes nothing`() = runTest {
        val store = store()
        store.save(AccountMapper.toRecord(tokenSession("nutstore:a@b.c", "tok"), now = 1L))

        store.setAlias("nutstore:someone-else", "别人的账号")

        assertNull(store.get("nutstore:a@b.c")!!.alias)
        assertEquals(1, store.list().size)
    }

    @Test
    fun `alias survives reopening the file`() = runTest {
        val file = File(tmp.root, "accounts.json")
        JsonAccountStore(file, cipher).apply {
            save(AccountMapper.toRecord(tokenSession("nutstore:a@b.c", "tok"), now = 1L))
            setAlias("nutstore:a@b.c", "公司备份盘")
        }

        // 换一个实例读同一个文件，模拟重启。
        val reopened = JsonAccountStore(file, cipher).list()

        assertEquals("公司备份盘", reopened.single().alias)
    }

    @Test
    fun `renaming does not touch the secret or the last used time`() = runTest {
        val store = store()
        store.save(AccountMapper.toRecord(tokenSession("nutstore:a@b.c", "tok-1"), now = 7L))

        store.setAlias("nutstore:a@b.c", "公司备份盘")

        val record = store.get("nutstore:a@b.c")!!
        assertEquals("tok-1", record.secret)
        assertEquals(7L, record.lastUsedAt)
    }
}

class AesGcmCipherTest {

    @Test
    fun `round trip`() {
        val cipher = AesGcmCipher { ByteArray(16) { 1 } }
        val encoded = cipher.encrypt("你好 token-abc")
        assertNotEquals("你好 token-abc", encoded)
        assertEquals("你好 token-abc", cipher.decrypt(encoded))
    }

    @Test
    fun `same plaintext encrypts differently each time`() {
        val cipher = AesGcmCipher { ByteArray(16) { 1 } }
        assertNotEquals(cipher.encrypt("x"), cipher.encrypt("x"))
    }

    @Test
    fun `tampered ciphertext returns null`() {
        val cipher = AesGcmCipher { ByteArray(16) { 1 } }
        // 改密文正文（跳过 Base64 尾部的填充位——那里改一位可能解出同样的字节）。
        val encoded = cipher.encrypt("a longer secret so the payload is not tiny").toCharArray()
        val index = encoded.size / 2
        encoded[index] = if (encoded[index] == 'A') 'B' else 'A'
        assertNull(cipher.decrypt(String(encoded)))
    }

    @Test
    fun `wrong key returns null`() {
        val encoded = AesGcmCipher { ByteArray(16) { 1 } }.encrypt("secret")
        assertNull(AesGcmCipher { ByteArray(16) { 2 } }.decrypt(encoded))
    }

    @Test
    fun `empty string stays empty`() {
        val cipher = AesGcmCipher { ByteArray(16) { 1 } }
        assertEquals("", cipher.encrypt(""))
        assertEquals("", cipher.decrypt(""))
    }
}
