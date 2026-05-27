package com.github.gotify.init

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.github.gotify.accounts.AccountStore
import com.github.gotify.service.WebSocketService

internal class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val accountStore = AccountStore(context)
        accountStore.migrateLegacyIfNeeded()

        if (accountStore.active() == null) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(Intent(context, WebSocketService::class.java))
        } else {
            context.startService(Intent(context, WebSocketService::class.java))
        }
    }
}
