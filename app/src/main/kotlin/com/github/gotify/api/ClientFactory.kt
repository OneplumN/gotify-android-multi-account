package com.github.gotify.api

import com.github.gotify.SSLSettings
import com.github.gotify.Settings
import com.github.gotify.client.ApiClient
import com.github.gotify.client.api.InfoApi
import com.github.gotify.client.api.OidcApi
import com.github.gotify.client.api.UserApi
import com.github.gotify.client.auth.ApiKeyAuth
import com.github.gotify.client.auth.HttpBasicAuth

internal object ClientFactory {
    private fun unauthorized(
        settings: Settings,
        sslSettings: SSLSettings,
        baseUrl: String
    ): ApiClient {
        return defaultClient(arrayOf(), settings, sslSettings, baseUrl)
    }

    fun basicAuth(
        settings: Settings,
        sslSettings: SSLSettings,
        username: String,
        password: String,
        baseUrl: String = settings.url
    ): ApiClient {
        val client = defaultClient(arrayOf("basicAuth"), settings, sslSettings, baseUrl)
        val auth = client.apiAuthorizations["basicAuth"] as HttpBasicAuth
        auth.username = username
        auth.password = password
        return client
    }

    fun clientToken(settings: Settings, token: String? = settings.token): ApiClient {
        val client = defaultClient(arrayOf("clientTokenHeader"), settings)
        val tokenAuth = client.apiAuthorizations["clientTokenHeader"] as ApiKeyAuth
        tokenAuth.apiKey = token
        return client
    }

    fun infoApi(
        settings: Settings,
        sslSettings: SSLSettings = settings.sslSettings(),
        baseUrl: String = settings.url
    ): InfoApi {
        return unauthorized(settings, sslSettings, baseUrl).createService(InfoApi::class.java)
    }

    fun oidcApi(
        settings: Settings,
        sslSettings: SSLSettings = settings.sslSettings(),
        baseUrl: String = settings.url
    ): OidcApi {
        return unauthorized(settings, sslSettings, baseUrl).createService(OidcApi::class.java)
    }

    fun userApiWithToken(settings: Settings): UserApi {
        return clientToken(settings).createService(UserApi::class.java)
    }

    private fun defaultClient(
        authentications: Array<String>,
        settings: Settings,
        sslSettings: SSLSettings = settings.sslSettings(),
        baseUrl: String = settings.url
    ): ApiClient {
        val client = ApiClient(authentications)
        CertUtils.applySslSettings(client.okBuilder, sslSettings)
        client.adapterBuilder.baseUrl("$baseUrl/")
        return client
    }
}
