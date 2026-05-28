package com.github.gotify.service

import android.app.usage.UsageStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager

internal object AndroidRuntimeDiagnostics {
    fun snapshot(context: Context): String {
        val appContext = context.applicationContext
        return listOf(
            powerState(appContext),
            standbyState(appContext),
            backgroundNetworkState(appContext),
            activeNetworkState(appContext)
        ).joinToString(" ")
    }

    fun networkSnapshot(context: Context, network: Network): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val capabilities = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
        val linkProperties = runCatching { cm.getLinkProperties(network) }.getOrNull()
        return "network=$network capabilities=${describeCapabilities(capabilities)} " +
            "link=${linkProperties?.interfaceName ?: "unknown"}"
    }

    private fun powerState(context: Context): String {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val idle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isDeviceIdleMode.toString()
        } else {
            "unsupported"
        }
        val ignoringBatteryOptimizations = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(context.packageName).toString()
        } else {
            "unsupported"
        }
        return "power(interactive=${powerManager.isInteractive}," +
            "save=${powerManager.isPowerSaveMode}," +
            "idle=$idle," +
            "ignoreBatteryOpt=$ignoringBatteryOptimizations)"
    }

    private fun standbyState(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return "standby=unsupported"
        }
        val usageStatsManager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        return "standby=${standbyBucketName(usageStatsManager.appStandbyBucket)}"
    }

    private fun backgroundNetworkState(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return "backgroundNetwork=unsupported"
        }
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return "backgroundNetwork=${restrictBackgroundStatusName(manager.restrictBackgroundStatus)}"
    }

    private fun activeNetworkState(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return "activeNetwork=none"
        val capabilities = cm.getNetworkCapabilities(network)
        return "activeNetwork=$network capabilities=${describeCapabilities(capabilities)}"
    }

    private fun describeCapabilities(capabilities: NetworkCapabilities?): String {
        if (capabilities == null) {
            return "none"
        }
        val transports = mutableListOf<String>()
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports += "wifi"
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports += "cellular"
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transports += "ethernet"
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) transports += "vpn"

        val flags = mutableListOf<String>()
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            flags += "internet"
        }
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            flags += "validated"
        }
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            flags += "notMetered"
        }
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)) {
            flags += "notRoaming"
        }
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
            flags += "notVpn"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
        ) {
            flags += "notSuspended"
        }
        return "transports=${transports.ifEmpty { listOf("unknown") }.joinToString("+")}," +
            "flags=${flags.ifEmpty { listOf("none") }.joinToString("+")}"
    }

    private fun restrictBackgroundStatusName(status: Int): String {
        return when (status) {
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_DISABLED -> "disabled"
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "whitelisted"
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "enabled"
            else -> "unknown($status)"
        }
    }

    private fun standbyBucketName(bucket: Int): String {
        return when (bucket) {
            UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "active"
            UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "working_set"
            UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "frequent"
            UsageStatsManager.STANDBY_BUCKET_RARE -> "rare"
            UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "restricted"
            else -> "unknown($bucket)"
        }
    }
}
