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
import androidx.documentfile.provider.DocumentFile
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
        const val EXTRA_IS_TREE = "is_tree"
        const val EXTRA_FORMAT = "format"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_LOSSLESS = "lossless"
        const val EXTRA_CROP_VISIBLE = "crop_visible"
        const val EXTRA_SOURCE_NAME = "source_name"
        private const val CHANNEL_ID = "conversion_channel"
        private const val NOTIF_ID = 1001
        private val IMAGE_EXTS = setOf(
            "png", "jpg", "jpeg", "tif", "tiff", "bmp", "gif", "webp"
        )
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null
    @Volatile private var cancelled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                cancelled = false
                val uris = intent.getParcelableArrayListExtra<Uri>(EXTRA_URIS) ?: arrayListOf()
                val isTree = intent.getBooleanExtra(EXTRA_IS_TREE, false)
                val fmt = intent.getStringExtra(EXTRA_FORMAT) ?: "webp"
                val quality = intent.getIntExtra(EXTRA_QUALITY, 80)
                val lossless = intent.getBooleanExtra(EXTRA_LOSSLESS, false)
                val cropVisible = intent.getBooleanExtra(EXTRA_CROP_VISIBLE, true)
                val sourceName = intent.getStringExtra(EXTRA_SOURCE_NAME) ?: "batch"
                startForeground(NOTIF_ID, buildNotification(0, 0, 0, "Scanning…"))
                job = scope.launch {
                    runConversion(uris, isTree, fmt, quality, lossless, cropVisible, sourceName)
                }
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
        isTree: Boolean,
        fmt: String,
        quality: Int,
        lossless: Boolean,
        cropVisible: Boolean,
        sourceName: String
    ) {
        try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(this))
            }
        } catch (e: Exception) {
            updateNotification(0, 0, 0, "Python start failed: ${e.message}")
            stopSelf()
            return
        }

        val py = Python.getInstance()
        val module = py.getModule("convert")
        val workDir = File(cacheDir, "work").also {
            it.deleteRecursively()
            it.mkdirs()
        }

        val finalOutRoot = File(getExternalFilesDir(null), "${sourceName}_Converted").also {
            it.mkdirs()
        }
        val errorLog = File(finalOutRoot, "_errors.log")
        errorLog.writeText("Conversion started\nOutput: ${finalOutRoot.absolutePath}\n\n")

        val jobs = mutableListOf<Pair<Uri, String>>()
        if (isTree && uris.isNotEmpty()) {
            val root = DocumentFile.fromTreeUri(this, uris[0])
            if (root != null) collectImages(root, "", jobs)
            else errorLog.appendText("ERROR: could not open tree URI\n")
        } else {
            uris.forEachIndexed { i, u ->
                jobs.add(u to (queryDisplayName(u) ?: "file_$i"))
            }
        }

        val total = jobs.size
        var processed = 0
        var ok = 0
        var repaired = 0
        var failed = 0
        errorLog.appendText("Found $total image(s)\n\n")
        updateNotification(0, total, 0, "0 / $total")

        if (total == 0) {
            val msg = "No images found\n${finalOutRoot.absolutePath}"
            errorLog.appendText("$msg\n")
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(0, 0, 100, msg, finished = true))
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
            return
        }

        for ((uri, relName) in jobs) {
            if (cancelled) break
            try {
                val local = copyUriToFile(uri, workDir, relName)
                if (local == null) {
                    failed++
                    errorLog.appendText("FAIL copy: $relName\n")
                } else {
                    val stem = relName.substringBeforeLast('.')
                    val relDir = relName.substringBeforeLast('/', missingDelimiterValue = "")
                    val outDir = if (relDir.isNotEmpty()) File(finalOutRoot, relDir) else finalOutRoot
                    outDir.mkdirs()
                    val outFile = File(outDir, "$stem.$fmt")

                    val result = module.callAttr(
                        "process_file",
                        local.absolutePath,
                        outFile.absolutePath,
                        fmt,
                        quality,
                        lossless,
                        true,
                        true,
                        cropVisible
                    )
                    val okStr = try { result.callAttr("get", "ok").toString() } catch (_: Exception) { "0" }
                    val errStr = try { result.callAttr("get", "error").toString() } catch (_: Exception) { "" }
                    val wasRepaired = try {
                        result.callAttr("get", "repaired").toString() == "1"
                    } catch (_: Exception) { false }

                    if (okStr == "1") {
                        ok++
                        if (wasRepaired) repaired++
                    } else {
                        failed++
                        errorLog.appendText("FAIL $relName → $errStr\n")
                    }
                }
            } catch (e: Exception) {
                failed++
                errorLog.appendText("EXCEPTION $relName → ${e.message}\n")
                e.printStackTrace()
            }
            processed++
            updateNotification(
                processed, total, pct(processed, total),
                "$processed / $total · ok=$ok fail=$failed"
            )
        }

        val summary = "Done: $ok ok, $repaired repaired, $failed failed\n${finalOutRoot.absolutePath}"
        errorLog.appendText("\n$summary\n")
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(processed, total, 100, summary, finished = true))
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun collectImages(dir: DocumentFile, rel: String, out: MutableList<Pair<Uri, String>>) {
        val files = dir.listFiles() ?: return
        for (f in files) {
            if (cancelled) return
            if (f.isDirectory) {
                val name = f.name ?: continue
                collectImages(f, if (rel.isEmpty()) name else "$rel/$name", out)
            } else if (f.isFile) {
                val name = f.name ?: continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext in IMAGE_EXTS) {
                    out.add(f.uri to (if (rel.isEmpty()) name else "$rel/$name"))
                }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && idx >= 0) return c.getString(idx)
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun copyUriToFile(uri: Uri, dir: File, relName: String): File? {
        return try {
            val safe = relName.replace("..", "_").replace('/', '_')
            val dest = File(dir, "${System.currentTimeMillis()}_$safe")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            } ?: return null
            if (!dest.exists() || dest.length() == 0L) null else dest
        } catch (_: Exception) {
            null
        }
    }

    private fun pct(done: Int, total: Int) = if (total > 0) done * 100 / total else 0

    private fun buildNotification(
        processed: Int, total: Int, percent: Int, text: String, finished: Boolean = false
    ): Notification {
        createChannel()
        val cancelIntent = Intent(this, ConversionService::class.java).apply { action = ACTION_CANCEL }
        val cancelPending = PendingIntent.getService(
            this, 0, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (finished) getString(R.string.conversion_complete)
        else getString(R.string.progress_notification_title)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, percent, total == 0 && !finished)
            .setOngoing(!finished)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.cancel), cancelPending)
            .build()
    }

    private fun updateNotification(processed: Int, total: Int, percent: Int, text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(processed, total, percent, text))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "File conversion", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
