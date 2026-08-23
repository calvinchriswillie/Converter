package com.convert.psdwebp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

class ConversionService : Service() {

    companion object {
        const val ACTION_START = "com.convert.psdwebp.START"
        const val ACTION_CANCEL = "com.convert.psdwebp.CANCEL"
        const val EXTRA_URIS = "uris"
        const val EXTRA_FORMAT = "format"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_LOSSLESS = "lossless"
        const val EXTRA_VISIBLE_ONLY = "visible_only"
        const val EXTRA_EXPORT_LAYERS = "export_layers"
        private const val CHANNEL_ID = "conversion_channel"
        private const val NOTIF_ID = 1001
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null
    private var cancelled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val uris = intent.getParcelableArrayListExtra<Uri>(EXTRA_URIS) ?: arrayListOf()
                val fmt = intent.getStringExtra(EXTRA_FORMAT) ?: "webp"
                val quality = intent.getIntExtra(EXTRA_QUALITY, 80)
                val lossless = intent.getBooleanExtra(EXTRA_LOSSLESS, false)
                val visibleOnly = intent.getBooleanExtra(EXTRA_VISIBLE_ONLY, true)
                val exportLayers = intent.getBooleanExtra(EXTRA_EXPORT_LAYERS, true)

                startForeground(NOTIF_ID, buildNotification(0, uris.size, 0))
                job = scope.launch { runConversion(uris, fmt, quality, lossless, visibleOnly, exportLayers) }
            }
            ACTION_CANCEL -> {
                cancelled = true
                job?.cancel()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runConversion(
        uris: List<Uri>,
        fmt: String,
        quality: Int,
        lossless: Boolean,
        visibleOnly: Boolean,
        exportLayers: Boolean
    ) {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        val py = Python.getInstance()
        val module = py.getModule("convert")

        val cacheDir = File(cacheDir, "work").also { it.mkdirs() }
        val outDir = File(getExternalFilesDir(null), "converted").also { it.mkdirs() }

        var processed = 0
        val total = uris.size

        for (uri in uris) {
            if (cancelled) break
            try {
                // Copy content URI to a real file so Python can open it
                val localFile = copyUriToFile(uri, cacheDir)
                if (localFile != null) {
                    val result = module.callAttr(
                        "process_file",
                        localFile.absolutePath,
                        outDir.absolutePath,
                        fmt,
                        quality,
                        lossless,
                        visibleOnly,
                        exportLayers
                    )
                    // result is a PyObject map; we just count success for progress
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            processed++
            val percent = if (total > 0) (processed * 100 / total) else 0
            updateNotification(processed, total, percent)
        }

        // Final notification
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(processed, total, 100, finished = true))
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun copyUriToFile(uri: Uri, dir: File): File? {
        return try {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file_${System.currentTimeMillis()}"
            val dest = File(dir, name)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
            dest
        } catch (e: Exception) {
            null
        }
    }

    private fun buildNotification(
        processed: Int,
        total: Int,
        percent: Int,
        finished: Boolean = false
    ): Notification {
        createChannel()
        val cancelIntent = Intent(this, ConversionService::class.java).apply { action = ACTION_CANCEL }
        val cancelPending = PendingIntent.getService(
            this, 0, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (finished) getString(R.string.conversion_complete)
        else getString(R.string.progress_notification_title)

        val text = "$processed / $total  ·  $percent%"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, percent, false)
            .setOngoing(!finished)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.cancel), cancelPending)
            .build()
    }

    private fun updateNotification(processed: Int, total: Int, percent: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(processed, total, percent))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "File conversion",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
