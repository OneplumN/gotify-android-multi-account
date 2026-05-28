package com.github.gotify

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.github.gotify.accounts.AccountStore
import com.github.gotify.accounts.GotifyAccount
import com.github.gotify.client.model.User

internal class Settings(context: Context) {
    private val sharedPreferences: SharedPreferences
    private val accountStore: AccountStore
    val filesDir: String
    var url: String
        get() = activeAccount()?.url ?: sharedPreferences.getString("url", "")!!
        set(value) = sharedPreferences.edit { putString("url", value) }
    var token: String?
        get() = activeAccount()?.token ?: sharedPreferences.getString("token", null)
        set(value) = sharedPreferences.edit { putString("token", value) }
    var user: User? = null
        get() {
            val account = activeAccount()
            val username = account?.username ?: sharedPreferences.getString("username", null)
            val admin = account?.admin ?: sharedPreferences.getBoolean("admin", false)
            return if (username != null) {
                User().name(username).admin(admin)
            } else {
                User().name("UNKNOWN").admin(false)
            }
        }
        private set
    var serverVersion: String
        get() =
            activeAccount()?.serverVersion
                ?: sharedPreferences.getString("version", "UNKNOWN")!!
        set(value) {
            updateActiveAccount { account -> account.copy(serverVersion = value) }
                ?: sharedPreferences.edit { putString("version", value) }
        }
    var legacyCert: String?
        get() = sharedPreferences.getString("cert", null)
        set(value) = sharedPreferences.edit(commit = true) { putString("cert", value) }.toUnit()
    var caCertPath: String?
        get() = activeAccount()?.caCertPath ?: sharedPreferences.getString("caCertPath", null)
        set(value) {
            updateActiveAccount { account -> account.copy(caCertPath = value) }
                ?: sharedPreferences
                    .edit(commit = true) { putString("caCertPath", value) }
                    .toUnit()
        }
    var validateSSL: Boolean
        get() =
            activeAccount()?.validateSSL
                ?: sharedPreferences.getBoolean("validateSSL", true)
        set(value) {
            updateActiveAccount { account -> account.copy(validateSSL = value) }
                ?: sharedPreferences.edit { putBoolean("validateSSL", value) }
        }
    var clientCertPath: String?
        get() =
            activeAccount()?.clientCertPath
                ?: sharedPreferences.getString("clientCertPath", null)
        set(value) {
            updateActiveAccount { account -> account.copy(clientCertPath = value) }
                ?: sharedPreferences.edit { putString("clientCertPath", value) }
        }
    var clientCertPassword: String?
        get() =
            activeAccount()?.clientCertPassword
                ?: sharedPreferences.getString("clientCertPass", null)
        set(value) {
            updateActiveAccount { account -> account.copy(clientCertPassword = value) }
                ?: sharedPreferences.edit { putString("clientCertPass", value) }
        }
    var oidcCodeVerifier: String?
        get() = sharedPreferences.getString("oidc_code_verifier", null)
        set(value) = sharedPreferences.edit { putString("oidc_code_verifier", value) }
    var oidcState: String?
        get() = sharedPreferences.getString("oidc_state", null)
        set(value) = sharedPreferences.edit { putString("oidc_state", value) }
    var loginUrl: String
        get() = sharedPreferences.getString("url", "") ?: ""
        set(value) = sharedPreferences.edit { putString("url", value) }
    var loginValidateSSL: Boolean
        get() = sharedPreferences.getBoolean("validateSSL", true)
        set(value) = sharedPreferences.edit { putBoolean("validateSSL", value) }
    var loginCaCertPath: String?
        get() = sharedPreferences.getString("caCertPath", null)
        set(value) = sharedPreferences
            .edit(commit = true) { putString("caCertPath", value) }
            .toUnit()
    var loginClientCertPath: String?
        get() = sharedPreferences.getString("clientCertPath", null)
        set(value) = sharedPreferences.edit { putString("clientCertPath", value) }
    var loginClientCertPassword: String?
        get() = sharedPreferences.getString("clientCertPass", null)
        set(value) = sharedPreferences.edit { putString("clientCertPass", value) }

    init {
        sharedPreferences = context.getSharedPreferences("gotify", Context.MODE_PRIVATE)
        accountStore = AccountStore(context)
        filesDir = context.filesDir.absolutePath
    }

    fun tokenExists(): Boolean = !token.isNullOrEmpty()

    fun clear() {
        val activeAccountId = accountStore.active()?.id
        if (activeAccountId != null) {
            accountStore.remove(activeAccountId)
        } else {
            token = null
            legacyCert = null
        }
        url = ""
        loginValidateSSL = true
        loginCaCertPath = null
        loginClientCertPath = null
        loginClientCertPassword = null
        oidcCodeVerifier = null
        oidcState = null
    }

    fun setUser(name: String?, admin: Boolean) {
        updateActiveAccount { account ->
            account.copy(
                username = name,
                admin = admin,
                label = AccountStore.createLabel(account.url, name)
            )
        } ?: sharedPreferences.edit { putString("username", name).putBoolean("admin", admin) }
    }

    fun saveAccountForLogin(
        token: String,
        username: String?,
        admin: Boolean,
        serverVersion: String
    ) {
        val loginUrl = sharedPreferences.getString("url", "") ?: ""
        val existing = accountStore.findByCredentials(loginUrl, token)
        val account = AccountStore.createAccount(
            url = loginUrl,
            token = token,
            username = username,
            admin = admin,
            serverVersion = serverVersion,
            validateSSL = loginValidateSSL,
            caCertPath = loginCaCertPath,
            clientCertPath = loginClientCertPath,
            clientCertPassword = loginClientCertPassword,
            id = existing?.id ?: java.util.UUID.randomUUID().toString()
        )
        accountStore.save(account, makeActive = true)
    }

    fun sslSettings(): SSLSettings {
        return SSLSettings(
            validateSSL,
            caCertPath,
            clientCertPath,
            clientCertPassword
        )
    }

    fun loginSslSettings(): SSLSettings {
        return SSLSettings(
            loginValidateSSL,
            loginCaCertPath,
            loginClientCertPath,
            loginClientCertPassword
        )
    }

    @Suppress("UnusedReceiverParameter")
    private fun Any?.toUnit() = Unit

    private fun activeAccount(): GotifyAccount? = accountStore.active()

    private fun updateActiveAccount(update: (GotifyAccount) -> GotifyAccount): Unit? {
        val account = activeAccount() ?: return null
        accountStore.save(update(account), makeActive = true)
        return Unit
    }
}
