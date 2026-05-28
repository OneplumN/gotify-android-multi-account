package com.github.gotify.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import com.github.gotify.BuildConfig
import com.github.gotify.CoilInstance
import com.github.gotify.MarkwonFactory
import com.github.gotify.MissedMessageUtil
import com.github.gotify.NotificationSupport
import com.github.gotify.R
import com.github.gotify.Settings
import com.github.gotify.Utils
import com.github.gotify.accounts.AccountStore
import com.github.gotify.accounts.GotifyAccount
import com.github.gotify.api.Callback
import com.github.gotify.api.ClientFactory
import com.github.gotify.client.api.ApplicationApi
import com.github.gotify.client.api.MessageApi
import com.github.gotify.client.model.Application
import com.github.gotify.client.model.Message
import com.github.gotify.log.LoggerHelper
import com.github.gotify.log.UncaughtExceptionHandler
import com.github.gotify.messages.Extras
import com.github.gotify.messages.IntentUrlDialogActivity
import com.github.gotify.messages.MessagesActivity
import io.noties.markwon.Markwon
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import org.tinylog.kotlin.Logger

internal class WebSocketService : Service() {
    companion object {
        private val castAddition = if (BuildConfig.DEBUG) ".DEBUG" else ""
        val NEW_MESSAGE_BROADCAST = "${WebSocketService::class.java.name}.NEW_MESSAGE$castAddition"
        const val EXTRA_ACCOUNT_ID = "account_id"
        private const val NOT_LOADED = -2L
    }

    private lateinit var settings: Settings
    private val runtimes = ConcurrentHashMap<String, AccountRuntime>()
    private val networkCallback: ConnectivityManager.NetworkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                Logger.info(
                    "WebSocket: Network available, reconnect if needed. " +
                        AndroidRuntimeDiagnostics.networkSnapshot(this@WebSocketService, network) +
                        " ${AndroidRuntimeDiagnostics.snapshot(this@WebSocketService)}"
                )
                runtimes.values.forEach { it.connection?.start() }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                Logger.warn(
                    "WebSocket: Network lost. " +
                        AndroidRuntimeDiagnostics.networkSnapshot(this@WebSocketService, network) +
                        " ${AndroidRuntimeDiagnostics.snapshot(this@WebSocketService)}"
                )
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                super.onLinkPropertiesChanged(network, linkProperties)
                Logger.info(
                    "WebSocket: Network properties changed, reconnect if needed. " +
                        "interface=${linkProperties.interfaceName} " +
                        AndroidRuntimeDiagnostics.networkSnapshot(this@WebSocketService, network) +
                        " ${AndroidRuntimeDiagnostics.snapshot(this@WebSocketService)}"
                )
                runtimes.values.forEach { it.connection?.start() }
            }
        }

    private var networkCallbackRegistered = false

    private lateinit var markwon: Markwon

    private class AccountRuntime(
        val account: GotifyAccount,
        val missingMessageUtil: MissedMessageUtil
    ) {
        val appIdToApp = ConcurrentHashMap<Long, Application>()
        val lastReceivedMessage = AtomicLong(NOT_LOADED)
        var connection: WebSocketConnection? = null
    }

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        Logger.info("Create ${javaClass.simpleName}")
        markwon = MarkwonFactory.createForNotification(this, CoilInstance.get(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        runtimes.values.forEach { it.connection?.close() }
        runtimes.clear()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && networkCallbackRegistered) {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            runCatching { cm.unregisterNetworkCallback(networkCallback) }
            networkCallbackRegistered = false
        }

        Logger.warn("Destroy ${javaClass.simpleName}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LoggerHelper.init(this)
        UncaughtExceptionHandler.registerCurrentThread()

        Logger.info("Starting ${javaClass.simpleName}")
        super.onStartCommand(intent, flags, startId)
        Thread { startPushService() }.start()

        return START_STICKY
    }

    @Synchronized
    private fun startPushService() {
        UncaughtExceptionHandler.registerCurrentThread()
        val accounts = AccountStore(this).all()
        if (accounts.isEmpty()) {
            Logger.warn("WebSocket: No accounts, stopping service.")
            stopSelf()
            return
        }
        Logger.info("WebSocket: Service diagnostics ${AndroidRuntimeDiagnostics.snapshot(this)}")
        showForegroundNotification(getString(R.string.websocket_init), accountSummary(accounts))

        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val reconnectDelay =
            sharedPreferences.getString(
                getString(R.string.setting_key_reconnect_delay),
                null
            )?.toIntOrNull()?.toDuration(DurationUnit.SECONDS) ?: 15.seconds

        val exponentialBackoff = sharedPreferences.getBoolean(
            getString(R.string.setting_key_exponential_backoff),
            true
        )

        reconcileAccounts(accounts, alarmManager, reconnectDelay, exponentialBackoff)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (!networkCallbackRegistered) {
                cm.registerDefaultNetworkCallback(networkCallback)
                networkCallbackRegistered = true
            }
        }
    }

    private fun reconcileAccounts(
        accounts: List<GotifyAccount>,
        alarmManager: AlarmManager,
        reconnectDelay: Duration,
        exponentialBackoff: Boolean
    ) {
        val accountIds = accounts.map { it.id }.toSet()
        runtimes.keys
            .filterNot { it in accountIds }
            .forEach { accountId ->
                runtimes.remove(accountId)?.connection?.close()
                Logger.info("WebSocket: removed account runtime $accountId")
            }

        accounts.forEach { account ->
            val existing = runtimes[account.id]
            if (existing != null && existing.account == account) {
                existing.connection?.start()
                return@forEach
            }

            existing?.connection?.close()
            val runtime = AccountRuntime(
                account,
                MissedMessageUtil(
                    ClientFactory.clientToken(account).createService(MessageApi::class.java)
                )
            )
            runtimes[account.id] = runtime

            if (runtime.lastReceivedMessage.get() == NOT_LOADED) {
                runtime.missingMessageUtil.lastReceivedMessage {
                    if (isAccountKnown(account.id)) {
                        runtime.lastReceivedMessage.set(it)
                    }
                }
            }

            runtime.connection = WebSocketConnection(
                account.label,
                account.url,
                account.sslSettings(),
                account.token,
                alarmManager,
                reconnectDelay,
                exponentialBackoff
            )
                .onOpen { onOpen(account.id) }
                .onClose { onClose(account.id) }
                .onFailure { status, reconnectIn -> onFailure(account.id, status, reconnectIn) }
                .onMessage { message -> onMessage(account.id, message) }
                .onReconnected { notifyMissedNotifications(account.id) }
                .start()
            fetchApps(account.id)
        }
    }

    private fun fetchApps(accountId: String) {
        val runtime = runtimes[accountId] ?: return
        ClientFactory.clientToken(runtime.account)
            .createService(ApplicationApi::class.java)
            .apps
            .enqueue(
                Callback.call(
                    onSuccess = Callback.SuccessBody { apps ->
                        if (!isAccountKnown(accountId)) {
                            return@SuccessBody
                        }
                        runtime.appIdToApp.clear()
                        runtime.appIdToApp.putAll(apps.associateBy { it.id })
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            NotificationSupport.createChannels(
                                this,
                                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager),
                                apps
                            )
                        }
                    },
                    onError = {
                        if (isAccountKnown(accountId)) {
                            runtime.appIdToApp.clear()
                        }
                    }
                )
            )
    }

    private fun onClose(accountId: String) {
        val runtime = runtimes[accountId] ?: return
        showForegroundNotification(
            getString(R.string.websocket_closed),
            "${runtime.account.label}: ${getString(R.string.websocket_reconnect)}"
        )
        ClientFactory.userApiWithToken(runtime.account)
            .currentUser()
            .enqueue(
                Callback.call(
                    onSuccess = {
                        if (isAccountKnown(accountId)) {
                            doReconnect(accountId)
                        }
                    },
                    onError = { exception ->
                        if (isAccountKnown(accountId)) {
                            if (exception.code == 401) {
                                showForegroundNotification(
                                    getString(R.string.user_action),
                                    "${runtime.account.label}: ${
                                        getString(R.string.websocket_closed_logout)
                                    }"
                                )
                            } else {
                                Logger.info(
                                    "WebSocket closed but the user still authenticated, " +
                                        "trying to reconnect"
                                )
                                doReconnect(accountId)
                            }
                        }
                    }
                )
            )
    }

    private fun doReconnect(accountId: String) {
        runtimes[accountId]?.connection?.scheduleReconnectNow(15.seconds)
    }

    private fun onFailure(accountId: String, status: String, reconnectIn: Duration) {
        val runtime = runtimes[accountId] ?: return
        Logger.warn(
            "WebSocket[${runtime.account.label}]: failure diagnostics " +
                AndroidRuntimeDiagnostics.snapshot(this)
        )
        val title = getString(R.string.websocket_error, status)
        showForegroundNotification(
            title,
            "${runtime.account.label}: ${getString(R.string.websocket_reconnect, reconnectIn)}"
        )
    }

    private fun onOpen(accountId: String) {
        if (!isAccountKnown(accountId)) return
        Logger.info(
            "WebSocket[${runtimes[accountId]?.account?.label}]: open diagnostics " +
                AndroidRuntimeDiagnostics.snapshot(this)
        )
        showForegroundNotification(
            getString(R.string.websocket_listening),
            accountSummary(runtimes.values.map { it.account })
        )
    }

    private fun notifyMissedNotifications(accountId: String) {
        val runtime = runtimes[accountId] ?: return
        val messageId = runtime.lastReceivedMessage.get()
        if (messageId == NOT_LOADED) {
            return
        }

        val messages = runtime.missingMessageUtil.missingMessages(messageId).filterNotNull()

        if (messages.size > 5) {
            onGroupedMessages(accountId, messages)
        } else {
            messages.forEach {
                onMessage(accountId, it)
            }
        }
    }

    private fun onGroupedMessages(accountId: String, messages: List<Message>) {
        val runtime = runtimes[accountId] ?: return
        var highestPriority = 0L
        messages.forEach { message ->
            if (runtime.lastReceivedMessage.get() < message.id) {
                runtime.lastReceivedMessage.set(message.id)
                highestPriority = highestPriority.coerceAtLeast(message.priority ?: 0L)
            }
            broadcast(accountId, message)
        }
        val size = messages.size
        showNotification(
            runtime.account,
            NotificationSupport.ID.GROUPED,
            getString(R.string.missed_messages),
            getString(R.string.grouped_message, size),
            highestPriority,
            null
        )
    }

    private fun onMessage(accountId: String, message: Message) {
        val runtime = runtimes[accountId] ?: return
        if (runtime.lastReceivedMessage.get() < message.id) {
            runtime.lastReceivedMessage.set(message.id)
        }
        broadcast(accountId, message)
        showNotification(
            runtime.account,
            message.id,
            message.title ?: "",
            message.message,
            message.priority ?: 0L,
            message.extras,
            message.appid
        )
    }

    private fun broadcast(accountId: String, message: Message) {
        if (!isAccountActive(accountId)) return
        val intent = Intent()
        intent.action = NEW_MESSAGE_BROADCAST
        intent.putExtra(EXTRA_ACCOUNT_ID, accountId)
        intent.putExtra("message", Utils.JSON.toJson(message))
        sendBroadcast(intent)
    }

    override fun onBind(intent: Intent): IBinder? = null

    private fun isAccountKnown(accountId: String): Boolean = runtimes.containsKey(accountId)

    private fun isAccountActive(accountId: String): Boolean = AccountStore(this).active()?.id == accountId

    private fun showForegroundNotification(title: String, message: String? = null) {
        val notificationIntent = Intent(this, MessagesActivity::class.java)

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        val notificationBuilder =
            NotificationCompat.Builder(this, NotificationSupport.Channel.FOREGROUND)
        notificationBuilder.setSmallIcon(R.drawable.ic_gotify)
        notificationBuilder.setOngoing(true)
        notificationBuilder.priority = NotificationCompat.PRIORITY_MIN
        notificationBuilder.setShowWhen(false)
        notificationBuilder.setWhen(0)
        notificationBuilder.setContentTitle(title)

        if (message != null) {
            notificationBuilder.setContentText(message)
            notificationBuilder.setStyle(NotificationCompat.BigTextStyle().bigText(message))
        }

        notificationBuilder.setContentIntent(pendingIntent)
        notificationBuilder.color = ContextCompat.getColor(applicationContext, R.color.colorPrimary)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NotificationSupport.ID.FOREGROUND,
                notificationBuilder.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NotificationSupport.ID.FOREGROUND, notificationBuilder.build())
        }
    }

    private fun showNotification(
        account: GotifyAccount,
        id: Int,
        title: String,
        message: String,
        priority: Long,
        extras: Map<String, Any>?
    ) {
        showNotification(account, id.toLong(), title, message, priority, extras, -1L)
    }

    private fun showNotification(
        account: GotifyAccount,
        id: Long,
        title: String,
        message: String,
        priority: Long,
        extras: Map<String, Any>?,
        appId: Long
    ) {
        var intent: Intent

        val intentUrl = Extras.getNestedValue(
            String::class.java,
            extras,
            "android::action",
            "onReceive",
            "intentUrl"
        )

        if (intentUrl != null) {
            val prompt = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(
                getString(R.string.setting_key_prompt_onreceive_intent),
                resources.getBoolean(R.bool.prompt_onreceive_intent)
            )
            val onReceiveIntent = if (prompt) {
                Intent(this, IntentUrlDialogActivity::class.java).apply {
                    putExtra(IntentUrlDialogActivity.EXTRA_KEY_URL, intentUrl)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            } else {
                Intent(Intent.ACTION_VIEW).apply {
                    data = intentUrl.toUri()
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            }
            startActivity(onReceiveIntent)
        }

        val url = Extras.getNestedValue(
            String::class.java,
            extras,
            "client::notification",
            "click",
            "url"
        )

        if (url != null) {
            intent = Intent(Intent.ACTION_VIEW)
            intent.data = url.toUri()
        } else {
            intent = Intent(this, MessagesActivity::class.java)
        }
        intent.putExtra(EXTRA_ACCOUNT_ID, account.id)

        val contentIntent = PendingIntent.getActivity(
            this,
            notificationId(account.id, id),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId: String
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            NotificationSupport.areAppChannelsRequested(this)
        ) {
            channelId = NotificationSupport.getChannelID(priority, appId.toString())
            NotificationSupport.createChannelIfNonexistent(
                this,
                appId.toString(),
                channelId
            )
        } else {
            channelId = NotificationSupport.convertPriorityToChannel(priority)
        }

        val b = NotificationCompat.Builder(this, channelId)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            showNotificationGroup(account, channelId)
        }

        b.setAutoCancel(true)
            .setDefaults(Notification.DEFAULT_ALL)
            .setWhen(System.currentTimeMillis())
            .setSmallIcon(R.drawable.ic_gotify)
            .setLargeIcon(CoilInstance.getIcon(this, runtimes[account.id]?.appIdToApp?.get(appId)))
            .setTicker("${getString(R.string.app_name)} - $title")
            .setGroup(groupKey(account.id))
            .setContentTitle(title)
            .setSubText(account.label)
            .setDefaults(Notification.DEFAULT_LIGHTS or Notification.DEFAULT_SOUND)
            .setLights(Color.CYAN, 1000, 5000)
            .setColor(ContextCompat.getColor(applicationContext, R.color.colorPrimary))
            .setContentIntent(contentIntent)

        var formattedMessage = message as CharSequence
        var newMessage: String? = null
        if (Extras.useMarkdown(extras)) {
            formattedMessage = markwon.toMarkdown(message)
            newMessage = formattedMessage.toString()
        }
        b.setContentText(newMessage ?: message)
        b.setStyle(NotificationCompat.BigTextStyle().bigText(formattedMessage))

        val notificationImageUrl = Extras.getNestedValue(
            String::class.java,
            extras,
            "client::notification",
            "bigImageUrl"
        )

        if (notificationImageUrl != null) {
            try {
                b.setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(CoilInstance.getImageFromUrl(this, notificationImageUrl))
                )
            } catch (e: Exception) {
                Logger.error(e, "Error loading bigImageUrl")
            }
        }
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notificationId = notificationId(account.id, id)
        val notificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        val channelImportance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.getNotificationChannel(channelId)?.importance?.toString() ?: "missing"
        } else {
            "pre-o"
        }
        if (!notificationsEnabled) {
            Logger.warn("Notification[$account.label]: app notifications are disabled by Android")
        }
        notificationManager.notify(notificationId, b.build())
        Logger.info(
            "Notification[${account.label}]: posted id=$notificationId " +
                "messageId=$id priority=$priority channel=$channelId " +
                "importance=$channelImportance enabled=$notificationsEnabled"
        )
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun showNotificationGroup(account: GotifyAccount, channelId: String) {
        val intent = Intent(this, MessagesActivity::class.java)
        intent.putExtra(EXTRA_ACCOUNT_ID, account.id)
        val contentIntent = PendingIntent.getActivity(
            this,
            notificationId(account.id, -5L),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(
            this,
            channelId
        )

        builder.setAutoCancel(true)
            .setDefaults(Notification.DEFAULT_ALL)
            .setWhen(System.currentTimeMillis())
            .setSmallIcon(R.drawable.ic_gotify)
            .setTicker(getString(R.string.app_name))
            .setGroup(groupKey(account.id))
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setContentTitle(getString(R.string.grouped_notification_text))
            .setGroupSummary(true)
            .setContentText(account.label)
            .setColor(ContextCompat.getColor(applicationContext, R.color.colorPrimary))
            .setContentIntent(contentIntent)

        val notificationManager = this.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId(account.id, -5L), builder.build())
    }

    private fun accountSummary(accounts: Collection<GotifyAccount>): String {
        return accounts.joinToString { it.label }
    }

    private fun notificationId(accountId: String, messageId: Long): Int {
        return 31 * accountId.hashCode() + Utils.longToInt(messageId)
    }

    private fun groupKey(accountId: String): String {
        return "${NotificationSupport.Group.MESSAGES}.$accountId"
    }

    private fun GotifyAccount.sslSettings() = com.github.gotify.SSLSettings(
        validateSSL,
        caCertPath,
        clientCertPath,
        clientCertPassword
    )
}
