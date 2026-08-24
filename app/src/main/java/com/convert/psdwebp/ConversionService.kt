package com.convert.psdwebp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream

/**
 * Recursively walks a SAF tree (or list of files), converts images,
 * writes to <basename>_Converted mirroring structure under public Downloads
 * (or app external files as fallback). Matches Termux batch script intent.
 */
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
                val sourceName = intent.getStringExtra(EXTRA_SOURCE_NAME) ?: "Converted"

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
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        val py = Python.getInstance()
        val module = py.getModule("convert")

        val workDir = File(cacheDir, "work").also { it.mkdirs() }

        // Output: public Downloads/<name>_Converted  (easy to find in MiXplorer)
        val outRoot = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "${sourceName}_Converted"
        )
        val usePublic = try {
            outRoot.mkdirs()
            outRoot.canWrite()
        } catch (_: Exception) {
            false
        }
        val finalOutRoot = if (usePublic) outRoot else File(getExternalFilesDir(null), "${sourceName}_Converted").also { it.mkdirs() }

        // Collect files
        val jobs = mutableListOf<Pair<Uri, String>>() // uri -> relative path without ext change
        if (isTree && uris.isNotEmpty()) {
            val root = DocumentFile.fromTreeUri(this, uris[0])
            if (root != null) collectImages(root, "", jobs)
        } else {
            uris.forEachIndexed { i, u ->
                val name = queryDisplayName(u) ?: "file_$i"
                jobs.add(u to name)
            }
        }

        val total = jobs.size
        var processed = 0
        var ok = 0
        var repaired = 0
        var failed = 0

        updateNotification(0, total, 0, "0 / $total")

        for ((uri, relName) in jobs) {
            if (cancelled) break
            try {
                val local = copyUriToFile(uri, workDir, relName)
                if (local == null) {
                    failed++
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
                        true,  // visible_only (unused for raster)
                        true,  // export_layers
                        cropVisible
                    )
                    val okFlag = try { result.callAttr("get", "ok").toBoolean() } catch (_: Exception) { false }
                    val wasRepaired = try { result.callAttr("get", "repaired").toBoolean() } catch (_: Exception) { false }
                    if (okFlag) {
                        ok++
                        if (wasRepaired) repaired++
                    } else {
                        failed++
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                failed++
            }
            processed++
            updateNotification(processed, total, pct(processed, total), "$processed / $total · ok=$ok fail=$failed")
        }

        val summary = "Done: $ok ok, $repaired repaired, $failed failed → ${finalOutRoot.absolutePath}"
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(processed, total, 100, summary, finished = true))
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun collectImages(dir: DocumentFile, rel: String, out: MutableList<Pair<Uri, String>>) {
        for (f in dir.listFiles()) {
            if (cancelled) return
            if (f.isDirectory) {
                val name = f.name ?: continue
                collectImages(f, if (rel.isEmpty()) name else "$rel/$name", out)
            } else if (f.isFile) {
                val name = f.name ?: continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext in IMAGE_EXTS) {
                    val relPath = if (rel.isEmpty()) name else "$rel/$name"
                    out.add(f.uri to relPath)
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
            val dest = File(dir, safe)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
            dest
        } catch (_: Exception) {
            null
        }
    }

    private fun pct(done: Int, total: Int) = if (total > 0) done * 100 / total else 0

    private fun buildNotification(
        processed: Int,
        total: Int,
        percent: Int,
        text: String,
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
            val channel = NotificationChannel(
                CHANNEL_ID, "File conversion", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
