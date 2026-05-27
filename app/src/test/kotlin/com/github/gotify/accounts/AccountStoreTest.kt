package com.github.gotify.accounts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AccountStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = AccountStore(context)

    @Before
    fun clearPreferences() {
        context
            .getSharedPreferences("gotify_accounts", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.getSharedPreferences("gotify", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun savingFirstAccountSetsItActive() {
        val account = account("one")

        store.save(account, makeActive = false)

        assertEquals(listOf(account), store.all())
        assertEquals(account, store.active())
        assertEquals(account.id, store.activeAccountId())
    }

    @Test
    fun savingSecondAccountPreservesActiveAccount() {
        val first = account("one")
        val second = account("two")

        store.save(first, makeActive = true)
        store.save(second, makeActive = false)

        assertEquals(first, store.active())
        assertEquals(first.id, store.activeAccountId())
    }

    @Test
    fun setActiveAccountChangesActiveAccount() {
        val first = account("one")
        val second = account("two")
        store.save(first, makeActive = true)
        store.save(second, makeActive = false)

        val changed = store.setActiveAccount(second.id)

        assertEquals(true, changed)
        assertEquals(second, store.active())
        assertEquals(second.id, store.activeAccountId())
    }

    @Test
    fun removingActiveAccountSelectsAnotherAccount() {
        val first = account("one")
        val second = account("two")
        store.save(first, makeActive = true)
        store.save(second, makeActive = false)

        val removed = store.remove(first.id)

        assertEquals(first, removed)
        assertEquals(second, store.active())
        assertEquals(second.id, store.activeAccountId())
    }

    @Test
    fun removingFinalAccountClearsActiveAccount() {
        val account = account("one")
        store.save(account, makeActive = true)

        val removed = store.remove(account.id)

        assertEquals(account, removed)
        assertFalse(store.hasAccounts())
        assertNull(store.active())
        assertNull(store.activeAccountId())
    }

    @Test
    fun migratesLegacySingleAccountSettings() {
        context.getSharedPreferences("gotify", Context.MODE_PRIVATE)
            .edit()
            .putString("url", "https://gotify.example")
            .putString("token", "client-token")
            .putString("username", "alice")
            .putBoolean("admin", true)
            .putString("version", "2.10.0")
            .putBoolean("validateSSL", false)
            .putString("caCertPath", "/ca.pem")
            .putString("clientCertPath", "/client.p12")
            .putString("clientCertPass", "secret")
            .commit()

        store.migrateLegacyIfNeeded()

        val account = store.active()
        assertEquals(1, store.all().size)
        assertEquals("https://gotify.example", account?.url)
        assertEquals("client-token", account?.token)
        assertEquals("alice", account?.username)
        assertEquals(true, account?.admin)
        assertEquals("2.10.0", account?.serverVersion)
        assertEquals(false, account?.validateSSL)
        assertEquals("/ca.pem", account?.caCertPath)
        assertEquals("/client.p12", account?.clientCertPath)
        assertEquals("secret", account?.clientCertPassword)
        assertEquals("alice @ gotify.example", account?.label)
    }

    private fun account(suffix: String): GotifyAccount {
        return AccountStore.createAccount(
            url = "https://gotify-$suffix.example",
            token = "token-$suffix",
            username = "user-$suffix",
            admin = false,
            serverVersion = "2.10.0",
            validateSSL = true,
            caCertPath = null,
            clientCertPath = null,
            clientCertPassword = null,
            id = suffix
        )
    }
}
