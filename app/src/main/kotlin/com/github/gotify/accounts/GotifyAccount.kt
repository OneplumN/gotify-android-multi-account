package com.github.gotify.accounts

internal data class GotifyAccount(
    val id: String,
    val label: String,
    val url: String,
    val token: String,
    val username: String?,
    val admin: Boolean,
    val serverVersion: String,
    val validateSSL: Boolean,
    val caCertPath: String?,
    val clientCertPath: String?,
    val clientCertPassword: String?
)
