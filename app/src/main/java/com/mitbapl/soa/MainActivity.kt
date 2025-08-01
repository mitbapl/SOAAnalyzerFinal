package com.mitbapl.soa

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private val PICK_PDF_REQUEST = 1
    private var selectedPdfUri: Uri? = null
    private lateinit var summaryTextView: TextView
    private lateinit var saveCsvButton: Button
    private var latestCsvContent: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val selectPdfButton: Button = findViewById(R.id.selectPdfButton)
        summaryTextView = findViewById(R.id.summaryTextView)
        saveCsvButton = findViewById(R.id.saveCsvButton)

        saveCsvButton.isEnabled = false

        selectPdfButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "application/pdf"
            startActivityForResult(Intent.createChooser(intent, "Select a PDF"), PICK_PDF_REQUEST)
        }

        saveCsvButton.setOnClickListener {
            saveTextToDownloads(latestCsvContent)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_PDF_REQUEST && resultCode == Activity.RESULT_OK) {
            selectedPdfUri = data?.data
            selectedPdfUri?.let {
                uploadPdfToServer(it)
            }
        }
    }

    private fun uploadPdfToServer(pdfUri: Uri) {
        val inputStream = contentResolver.openInputStream(pdfUri) ?: return
        val fileBytes = inputStream.readBytes()

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", getFileName(pdfUri),
                RequestBody.create("application/pdf".toMediaTypeOrNull(), fileBytes))
            .build()

        val request = Request.Builder()
            .url("https://tika-server.onrender.com/analyze")
            .post(requestBody)
            .build()

        val client = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.MINUTES)
            .writeTimeout(3, TimeUnit.MINUTES)
            .readTimeout(3, TimeUnit.MINUTES)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    summaryTextView.text = "Upload failed: ${e.message}"
                    saveCsvButton.isEnabled = false
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val json = response.body?.string() ?: return
                try {
                    val jsonObject = JSONObject(json)
                    val rawText = jsonObject.getString("text")
                    latestCsvContent = rawText

                    runOnUiThread {
                        summaryTextView.text = rawText.take(3000)
                        saveCsvButton.isEnabled = true
                        Toast.makeText(this@MainActivity, "Extracted successfully", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        summaryTextView.text = "Error parsing response"
                        saveCsvButton.isEnabled = false
                    }
                }
            }
        })
    }

    private fun getFileName(uri: Uri): String {
        var name = "document.pdf"
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (it.moveToFirst() && nameIndex >= 0) {
                name = it.getString(nameIndex)
            }
        }
        return name
    }

    private fun saveTextToDownloads(text: String) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "statement_$timestamp.csv"
            val dir = File("/storage/emulated/0/Download")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            file.writeText(text)
            Toast.makeText(this, "Saved to ${file.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
