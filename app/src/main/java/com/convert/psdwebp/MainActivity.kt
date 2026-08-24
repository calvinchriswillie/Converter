package com.convert.psdwebp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.convert.psdwebp.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var treeUri: Uri? = null
    private val fileUris = mutableListOf<Uri>()
    private var sourceName: String = "batch"

    private val openTree = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri ?: return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {}
        }
        treeUri = uri
        fileUris.clear()
        sourceName = queryTreeName(uri) ?: "folder"
        binding.tvSelected.text = "Folder: $sourceName\n(Output → Download/${sourceName}_Converted)"
        binding.tvHint.text = "Recursive scan of all subfolders. Corrupt/partial images will be repaired when possible."
    }

    private val openDocuments = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        treeUri = null
        fileUris.clear()
        fileUris.addAll(uris)
        uris.forEach { u ->
            try {
                contentResolver.takePersistableUriPermission(
                    u, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {}
        }
        sourceName = "files"
        binding.tvSelected.text = "${uris.size} file(s) selected\n(Output → Download/files_Converted)"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val formats = listOf("webp", "png", "jpg", "jpeg", "tiff", "bmp")
        binding.spinnerFormat.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, formats
        )

        binding.seekQuality.progress = 80
        binding.tvQualityValue.text = "80"
        binding.seekQuality.setOnSeekBarChangeListener(object :
            android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, f: Boolean) {
                binding.tvQualityValue.text = p.toString()
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {}
        })

        binding.btnSelectFolder.setOnClickListener {
            openTree.launch(null)
        }
        binding.btnSelectFiles.setOnClickListener {
            openDocuments.launch(arrayOf("image/*", "*/*"))
        }
        binding.btnStart.setOnClickListener { startConversion() }

        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let {
                    treeUri = null
                    fileUris.clear()
                    fileUris.add(it)
                    sourceName = "share"
                    binding.tvSelected.text = "1 file from share"
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let {
                    treeUri = null
                    fileUris.clear()
                    fileUris.addAll(it)
                    sourceName = "share"
                    binding.tvSelected.text = "${it.size} files from share"
                }
            }
            Intent.ACTION_VIEW -> {
                intent.data?.let {
                    treeUri = null
                    fileUris.clear()
                    fileUris.add(it)
                    sourceName = "view"
                    binding.tvSelected.text = "1 file from view"
                }
            }
        }
    }

    private fun queryTreeName(uri: Uri): String? {
        // Try document name
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val name = docId.substringAfterLast(':').substringAfterLast('/')
        return name.ifBlank { null }
    }

    private fun startConversion() {
        val isTree = treeUri != null
        val uris = if (isTree) arrayListOf(treeUri!!) else ArrayList(fileUris)
        if (uris.isEmpty()) {
            Toast.makeText(this, "Select a folder (or files) first", Toast.LENGTH_SHORT).show()
            return
        }

        val fmt = binding.spinnerFormat.selectedItem as String
        val quality = binding.seekQuality.progress
        val lossless = binding.switchLossless.isChecked
        val cropVisible = binding.switchCropVisible.isChecked

        val serviceIntent = Intent(this, ConversionService::class.java).apply {
            action = ConversionService.ACTION_START
            putParcelableArrayListExtra(ConversionService.EXTRA_URIS, uris)
            putExtra(ConversionService.EXTRA_IS_TREE, isTree)
            putExtra(ConversionService.EXTRA_FORMAT, fmt)
            putExtra(ConversionService.EXTRA_QUALITY, quality)
            putExtra(ConversionService.EXTRA_LOSSLESS, lossless)
            putExtra(ConversionService.EXTRA_CROP_VISIBLE, cropVisible)
            putExtra(ConversionService.EXTRA_SOURCE_NAME, sourceName)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        Toast.makeText(
            this,
            "Started. Output: Download/${sourceName}_Converted",
            Toast.LENGTH_LONG
        ).show()
    }
}
