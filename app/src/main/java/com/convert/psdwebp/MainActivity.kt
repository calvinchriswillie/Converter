package com.convert.psdwebp

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.convert.psdwebp.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val selectedUris = mutableListOf<Uri>()

    private val openDocuments = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedUris.clear()
            selectedUris.addAll(uris)
            binding.tvSelected.text = "${uris.size} file(s) selected"
            // Take persistable permission where possible
            uris.forEach { uri ->
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) {}
            }
        }
    }

    private val openTree = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            selectedUris.clear()
            selectedUris.add(it)
            binding.tvSelected.text = "Folder selected"
            try {
                contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Output format spinner
        val formats = listOf("webp", "png", "jpg", "jpeg", "tiff", "bmp")
        binding.spinnerFormat.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, formats
        )

        binding.seekQuality.progress = 80
        binding.tvQualityValue.text = "80"

        binding.seekQuality.setOnSeekBarChangeListener(object :
            android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvQualityValue.text = progress.toString()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        binding.btnSelectFiles.setOnClickListener {
            openDocuments.launch(arrayOf("*/*"))
        }

        binding.btnSelectFolder.setOnClickListener {
            openTree.launch(null)
        }

        binding.btnStart.setOnClickListener {
            if (selectedUris.isEmpty()) {
                Toast.makeText(this, "Select files or a folder first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startConversion()
        }

        // Handle share / view intents from other apps (MiXplorer etc.)
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let {
                    selectedUris.clear()
                    selectedUris.add(it)
                    binding.tvSelected.text = "1 file from share"
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let {
                    selectedUris.clear()
                    selectedUris.addAll(it)
                    binding.tvSelected.text = "${it.size} files from share"
                }
            }
            Intent.ACTION_VIEW -> {
                intent.data?.let {
                    selectedUris.clear()
                    selectedUris.add(it)
                    binding.tvSelected.text = "1 file from view"
                }
            }
        }
    }

    private fun startConversion() {
        val fmt = binding.spinnerFormat.selectedItem as String
        val quality = binding.seekQuality.progress
        val lossless = binding.switchLossless.isChecked
        val visibleOnly = binding.switchVisibleOnly.isChecked
        val exportLayers = binding.switchExportLayers.isChecked

        val serviceIntent = Intent(this, ConversionService::class.java).apply {
            action = ConversionService.ACTION_START
            putParcelableArrayListExtra(ConversionService.EXTRA_URIS, ArrayList(selectedUris))
            putExtra(ConversionService.EXTRA_FORMAT, fmt)
            putExtra(ConversionService.EXTRA_QUALITY, quality)
            putExtra(ConversionService.EXTRA_LOSSLESS, lossless)
            putExtra(ConversionService.EXTRA_VISIBLE_ONLY, visibleOnly)
            putExtra(ConversionService.EXTRA_EXPORT_LAYERS, exportLayers)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        Toast.makeText(this, "Conversion started – check notification", Toast.LENGTH_SHORT).show()
    }
}
