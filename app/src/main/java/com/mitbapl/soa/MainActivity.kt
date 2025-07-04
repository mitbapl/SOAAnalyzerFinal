package com.mitbapl.soa

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import org.json.JSONObject
import java.io.*
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaTypeOrNull

class MainActivity : AppCompatActivity() {
    private val PICK_PDF_REQUEST = 1
    private var selectedPdfUri: Uri? = null
    private lateinit var outputText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var downloadButton: Button
    private lateinit var footerText: TextView
    private var latestExtractedCSV: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val uploadButton = findViewById<Button>(R.id.btnUpload)
        val analyzeButton = findViewById<Button>(R.id.btnAnalyze)
        downloadButton = findViewById(R.id.btnDownload)
        outputText = findViewById(R.id.txtOutput)
        progressBar = findViewById(R.id.progressBar)
        footerText = findViewById(R.id.footer)

        val versionName = packageManager.getPackageInfo(packageName, 0).versionName
        footerText.text = "Prashant L. Mitba - v$versionName"

        progressBar.visibility = ProgressBar.INVISIBLE
        downloadButton.isEnabled = false

        uploadButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "application/pdf"
            startActivityForResult(Intent.createChooser(intent, "Select SOA PDF"), PICK_PDF_REQUEST)
        }

        analyzeButton.setOnClickListener {
            if (selectedPdfUri != null) {
                uploadPdfToServer(selectedPdfUri!!)
            } else {
                Toast.makeText(this, "Please select soa.pdf first", Toast.LENGTH_SHORT).show()
            }
        }

        downloadButton.setOnClickListener {
            if (latestExtractedCSV.isNotEmpty()) {
                try {
                    val filename = "soa_output_${System.currentTimeMillis()}.csv"
                    val file = File("/storage/emulated/0/Download", filename)
                    file.writeText(latestExtractedCSV)
                    Toast.makeText(this, "Saved: ${file.absolutePath}", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Error saving file: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
