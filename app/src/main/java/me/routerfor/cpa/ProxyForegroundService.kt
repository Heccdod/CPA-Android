package me.routerfor.cpa

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File

/**
 * Foreground host of the embedded Go gateway. Owns the PARTIAL_WAKE_LOCK, the
 * persistent notification (with Restart / Stop actions) and the engine lifecycle.
 */
class ProxyForegroundService : Service() {

    /** The service runs on the main looper, so the watchdog can repaint without a new thread. */
    private val handler = Handler(Looper.getMainLooper())

    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Watches the engine while the user wants it running.
     *
     * The engine can die without anything noticing: its goroutine records the error and exits,
     * the port stops answering, and the app keeps showing 运行中 because nothing ever asks again.
     * That is the other half of the "connection drops until I restart it" report — the restart
     * worked because it was the only code path that rebuilt the engine.
     *
     * The check is the engine's own live probe, so a healthy gateway costs one loopback connect
     * every 20 seconds. Two consecutive failures are required before restarting, so a single
     * slow probe cannot knock over a working gateway mid-request.
     */
    private val watchdog = object : Runnable {
        private var misses = 0

        override fun run() {
            if (!AppPrefs.gatewayWanted(this@ProxyForegroundService)) {
                handler.removeCallbacks(this)
                return
            }
            val alive = WebBridgeCompat.isRunning()
            if (alive) {
                misses = 0
            } else {
                misses++
                Log.w(TAG, "watchdog: engine not answering (miss $misses)")
                if (misses >= WATCHDOG_MISSES) {
                    misses = 0
                    Log.w(TAG, "watchdog: restarting the gateway")
                    startGateway(restart = true)
                }
            }
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        HotSoLoader.loadEngine(applicationContext)
        createChannel()
        // Tell the engine what the user wants before anything reads its status. START_STICKY
        // means this service can be recreated by the system long after a stop, and the engine
        // would otherwise report "starting" for a gateway nobody asked to run.
        WebBridgeCompat.setDesiredRunning(AppPrefs.gatewayWanted(this))
        NetworkWatcher.start(this)
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REPAINT -> {
                // Only the notification body changes; the engine is untouched.
                Log.i(TAG, "repaint, quotaLines=" + QuotaNotifier.cachedBody().length)
                updateNotification(getString(R.string.notif_listening, WebBridgeCompat.listenAddress()))
                return START_STICKY
            }
            ACTION_STOP -> {
                // Record the intent before tearing anything down: the home screen reads it to
                // distinguish "stopped on purpose" from "still starting", and it has to be in
                // place before the engine disappears.
                AppPrefs.setGatewayWanted(this, false)
                WebBridgeCompat.setDesiredRunning(false)
                stopGateway()
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                AppPrefs.setGatewayWanted(this, true)
                WebBridgeCompat.setDesiredRunning(true)
                startGateway(restart = true)
            }
            else -> {
                AppPrefs.setGatewayWanted(this, true)
                WebBridgeCompat.setDesiredRunning(true)
                startGateway(restart = false)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        NetworkWatcher.stop(this)
        releaseWakeLock()
        super.onDestroy()
    }

    private fun dataDir(): String = filesDir.absolutePath

    /** Copies assets/models.json into filesDir, replacing any older copy. */
    private fun installModelsCatalog(dir: File) {
        val target = File(dir, MODELS_FILE)
        runCatching {
            assets.open(MODELS_FILE).use { input ->
                val tmp = File(dir, MODELS_FILE + ".tmp")
                tmp.outputStream().use { out ->
                    input.copyTo(out)
                    out.flush()
                }
                if (!tmp.renameTo(target)) {
                    target.delete()
                    tmp.renameTo(target)
                }
            }
        }.onFailure { Log.w(TAG, "models catalog install failed: " + it.message) }
    }

    private fun startGateway(restart: Boolean) {
        val dataDir = dataDir()
        // The corrected model catalog ships as an APK asset (Go cannot read packed assets), so
        // copy it out first: config.yaml points models.catalog at this path. It is overwritten
        // every start because it carries no user state — an app update must refresh it.
        installModelsCatalog(File(dataDir))
        // Seed filesDir/config.yaml + filesDir/auths before the engine reads them. The thinking
        // mode has to be the user's current choice: the Go side rewrites the payload rule to
        // match, so passing a default here would silently undo the setting on every start.
        val ensureError = WebBridgeCompat.ensureDefaultConfig(
            dataDir,
            AppPrefs.proxyUrl(this),
            AppPrefs.allowLan(this),
            AppPrefs.thinkingMode(this)
        )
        if (ensureError.isNotEmpty()) {
            Log.w(TAG, "ensureDefaultConfig: $ensureError")
        }
        File(dataDir, "auths").mkdirs()

        acquireWakeLock()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notif_starting)))

        Thread {
            val result = if (restart) WebBridgeCompat.restartProxy(dataDir) else WebBridgeCompat.startProxy(dataDir)
            val running = WebBridgeCompat.isRunning()
            val text = when {
                running -> getString(R.string.notif_listening, WebBridgeCompat.listenAddress())
                result.isNotEmpty() -> getString(R.string.notif_failed, result)
                else -> getString(R.string.notif_stopped)
            }
            Log.i(TAG, "engine: $text")
            updateNotification(text)
        }.start()
    }

    private fun stopGateway() {
        Thread {
            val result = WebBridgeCompat.stopProxy()
            if (result.isNotEmpty()) {
                Log.w(TAG, "stopProxy: $result")
            }
        }.start()
        releaseWakeLock()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        // Timeout is a safety net; the lock is released with the service lifecycle.
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        if (lock.isHeld) {
            lock.release()
        }
        wakeLock = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(content: String): Notification {
        val quota = QuotaNotifier.cachedBody()
        val body = if (quota.isEmpty()) content else content + "\n" + quota
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val restartIntent = PendingIntent.getService(
            this, 1, Intent(this, ProxyForegroundService::class.java).setAction(ACTION_RESTART),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 2, Intent(this, ProxyForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.action_restart), restartIntent)
            .addAction(0, getString(R.string.action_stop), stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        // The expanded body is where the quota lines live; collapsed it stays one short line.
        if (quota.isNotEmpty()) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }
        return builder.build()
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        const val ACTION_START = "me.routerfor.cpa.action.START"
        const val ACTION_STOP = "me.routerfor.cpa.action.STOP"
        const val ACTION_RESTART = "me.routerfor.cpa.action.RESTART"
        const val ACTION_REPAINT = "me.routerfor.cpa.action.REPAINT"
        private const val TAG = "CpaService"
        private const val CHANNEL_ID = "cpa-gateway"
        private const val NOTIFICATION_ID = 8317
        private const val MODELS_FILE = "models.json"
        private const val WAKE_LOCK_TAG = "me.routerfor.cpa:gateway"
        private const val WAKE_LOCK_TIMEOUT_MS = 12L * 60L * 60L * 1000L
        private const val WATCHDOG_INTERVAL_MS = 20_000L
        private const val WATCHDOG_MISSES = 2
    }
}