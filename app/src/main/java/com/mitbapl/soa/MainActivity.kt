package com.mitbapl.soa

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import org.json.JSONObject
import java.io.*
import java.util.*
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaTypeOrNull

class MainActivity : AppCompatActivity() {
    private val PICK_PDF_REQUEST = 1
    private var selectedPdfUri: Uri? = null
    private lateinit var outputText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var downloadButton: Button
    private lateinit var footerText: TextView
    private var csvContent: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val uploadButton = findViewById<Button>(R.id.btnUpload)
        val analyzeButton = findViewById<Button>(R.id.btnAnalyze)
        downloadButton = findViewById(R.id.btnDownload)
        outputText = findViewById(R.id.txtOutput)
        progressBar = findViewById(R.id.progressBar)
        footerText = findViewById(R.id.txtFooter)

        footerText.text = "Prashant L. Mitba - v1.0.30"
        progressBar.visibility = ProgressBar.GONE
        downloadButton.isEnabled = false

        uploadButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "application/pdf"
            startActivityForResult(Intent.createChooser(intent, "Select SOA PDF"), PICK_PDF_REQUEST)
        }

        analyzeButton.setOnClickListener {
            selectedPdfUri?.let {
                uploadPdfToServer(it)
            } ?: Toast.makeText(this, "Please select SOA PDF first", Toast.LENGTH_SHORT).show()
        }

        downloadButton.setOnClickListener {
            saveCsvToDownload(csvContent)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_PDF_REQUEST && resultCode == Activity.RESULT_OK) {
            selectedPdfUri = data?.data
            Toast.makeText(this, "File selected: ${getFileName(selectedPdfUri!!)}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = "soa.pdf"
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (it.moveToFirst() && index >= 0) {
                name = it.getString(index)
            }
        }
        return name
    }
    private fun uploadPdfToServer(pdfUri: Uri) {
        progressBar.visibility = ProgressBar.VISIBLE
        outputText.text = ""

        val inputStream = contentResolver.openInputStream(pdfUri) ?: return
        val fileBytes = inputStream.readBytes()

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", "soa.pdf",
                RequestBody.create("application/pdf".toMediaTypeOrNull(), fileBytes))
            .build()

        val request = Request.Builder()
            .url("https://tika-server.onrender.com/analyze")
            .post(requestBody)
            .build()

        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.MINUTES)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    progressBar.visibility = ProgressBar.GONE
                    outputText.text = "Error: ${e.message}"
                    downloadButton.isEnabled = false
                }
            }

            override fun onResponse(call: Call, response: Response) {
                progressBar.post { progressBar.visibility = ProgressBar.GONE }
                val json = response.body?.string() ?: "No response"
                try {
                    val text = JSONObject(json).getString("text")
                    val csv = convertTextToCSV(text)
                    csvContent = csv

                    runOnUiThread {
                        outputText.text = csv
                        downloadButton.isEnabled = true
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        outputText.text = "Parsing error: ${e.message}"
                        downloadButton.isEnabled = false
                    }
                }
            }
        })
    }

    private fun convertTextToCSV(text: String): String {
        val lines = text.split("\n").filter { it.contains(Regex("\\d{2}/\\d{2}/\\d{4}")) }
        val csv = StringBuilder("Date,Description,Amount,Type\n")
        for (line in lines) {
            val date = Regex("\\d{2}/\\d{2}/\\d{4}").find(line)?.value ?: ""
            val amount = Regex("\\d+\\.\\d{2}").find(line)?.value ?: ""
            val type = when {
                line.contains("withdraw", true) -> "Debit"
                line.contains("deposit", true) -> "Credit"
                else -> ""
            }
            val desc = line.replace(date, "").replace(amount, "").take(40).trim()
            csv.append("$date,\"$desc\",$amount,$type\n")
        }
        return csv.toString()
    }

    private fun saveCsvToDownload(content: String) {
        try {
            val fileName = "soa_${System.currentTimeMillis()}.csv"
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path)
            if (!dir.exists()) dir.mkdirs()

            val file = File(dir, fileName)
            file.writeText(content)
            Toast.makeText(this, "Saved: ${file.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

