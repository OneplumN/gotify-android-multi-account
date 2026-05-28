package com.github.gotify.accounts

import android.content.Context
import androidx.core.content.edit
import com.github.gotify.Utils
import com.google.gson.reflect.TypeToken
import java.net.URI
import java.util.UUID

internal class AccountStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val legacyPreferences = context.getSharedPreferences(
        LEGACY_PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun all(): List<GotifyAccount> = readAccounts()

    fun active(): GotifyAccount? {
        val accounts = all()
        val activeAccountId = activeAccountId()
        return accounts.firstOrNull { it.id == activeAccountId } ?: accounts.firstOrNull()
    }

    fun activeAccountId(): String? = preferences.getString(KEY_ACTIVE_ACCOUNT_ID, null)

    fun save(account: GotifyAccount, makeActive: Boolean = false) {
        val accounts = all().toMutableList()
        val index = accounts.indexOfFirst { it.id == account.id }
        if (index == -1) {
            accounts.add(account)
        } else {
            accounts[index] = account
        }
        writeAccounts(accounts)

        if (makeActive || activeAccountId().isNullOrEmpty()) {
            preferences.edit { putString(KEY_ACTIVE_ACCOUNT_ID, account.id) }
        }
    }

    fun setActiveAccount(id: String): Boolean {
        if (all().none { it.id == id }) {
            return false
        }
        preferences.edit { putString(KEY_ACTIVE_ACCOUNT_ID, id) }
        return true
    }

    fun remove(id: String): GotifyAccount? {
        val accounts = all().toMutableList()
        val removed = accounts.firstOrNull { it.id == id } ?: return null
        accounts.removeAll { it.id == id }
        writeAccounts(accounts)

        if (activeAccountId() == id) {
            preferences.edit {
                if (accounts.isEmpty()) {
                    remove(KEY_ACTIVE_ACCOUNT_ID)
                } else {
                    putString(KEY_ACTIVE_ACCOUNT_ID, accounts.first().id)
                }
            }
        }

        return removed
    }

    fun hasAccounts(): Boolean = all().isNotEmpty()

    fun findByCredentials(url: String, token: String): GotifyAccount? {
        return all().firstOrNull { it.url == url && it.token == token }
    }

    fun migrateLegacyIfNeeded() {
        if (hasAccounts()) {
            return
        }

        val token = legacyPreferences.getString("token", null)
        if (token.isNullOrEmpty()) {
            return
        }

        val url = legacyPreferences.getString("url", "") ?: ""
        val username = legacyPreferences.getString("username", null)
        val account = GotifyAccount(
            id = UUID.randomUUID().toString(),
            label = createLabel(url, username),
            url = url,
            token = token,
            username = username,
            admin = legacyPreferences.getBoolean("admin", false),
            serverVersion = legacyPreferences.getString("version", "UNKNOWN") ?: "UNKNOWN",
            validateSSL = legacyPreferences.getBoolean("validateSSL", true),
            caCertPath = legacyPreferences.getString("caCertPath", null),
            clientCertPath = legacyPreferences.getString("clientCertPath", null),
            clientCertPassword = legacyPreferences.getString("clientCertPass", null)
        )
        save(account, makeActive = true)
    }

    private fun readAccounts(): List<GotifyAccount> {
        val json = preferences.getString(KEY_ACCOUNTS_JSON, null) ?: return emptyList()
        return Utils.JSON.fromJson(json, ACCOUNT_LIST_TYPE) ?: emptyList()
    }

    private fun writeAccounts(accounts: List<GotifyAccount>) {
        preferences.edit { putString(KEY_ACCOUNTS_JSON, Utils.JSON.toJson(accounts)) }
    }

    companion object {
        private const val PREFERENCES_NAME = "gotify_accounts"
        private const val LEGACY_PREFERENCES_NAME = "gotify"
        private const val KEY_ACCOUNTS_JSON = "accounts_json"
        private const val KEY_ACTIVE_ACCOUNT_ID = "active_account_id"
        private val ACCOUNT_LIST_TYPE = object : TypeToken<List<GotifyAccount>>() {}.type

        fun createAccount(
            url: String,
            token: String,
            username: String?,
            admin: Boolean,
            serverVersion: String,
            validateSSL: Boolean,
            caCertPath: String?,
            clientCertPath: String?,
            clientCertPassword: String?,
            id: String = UUID.randomUUID().toString()
        ): GotifyAccount {
            return GotifyAccount(
                id = id,
                label = createLabel(url, username),
                url = url,
                token = token,
                username = username,
                admin = admin,
                serverVersion = serverVersion,
                validateSSL = validateSSL,
                caCertPath = caCertPath,
                clientCertPath = clientCertPath,
                clientCertPassword = clientCertPassword
            )
        }

        fun createLabel(url: String, username: String?): String {
            val displayName = username?.takeIf { it.isNotBlank() } ?: "UNKNOWN"
            val host = runCatching { URI(url).host }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: url
            return "$displayName @ $host"
        }
    }
}
