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
import java.util.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private val PICK_PDF_REQUEST = 1
    private var selectedPdfUri: Uri? = null
    private lateinit var outputText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var downloadButton: Button
    private var latestExtractedText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val uploadButton = findViewById<Button>(R.id.btnUpload)
        val analyzeButton = findViewById<Button>(R.id.btnAnalyze)
        downloadButton = findViewById(R.id.btnDownload)
        outputText = findViewById(R.id.txtOutput)
        progressBar = findViewById(R.id.progressBar)

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
            saveTextToFile(latestExtractedText)
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
            .writeTimeout(5, TimeUnit.MINUTES)
            .readTimeout(5, TimeUnit.MINUTES)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    progressBar.visibility = ProgressBar.INVISIBLE
                    outputText.text = "Error: ${e.message}"
                    downloadButton.isEnabled = false
                }
            }

            override fun onResponse(call: Call, response: Response) {
                runOnUiThread { progressBar.visibility = ProgressBar.INVISIBLE }
                val json = response.body?.string() ?: "No response"
                try {
                    val jsonObject = JSONObject(json)
                    val rawText = jsonObject.getString("text")
                    latestExtractedText = formatSOAText(rawText)
                    runOnUiThread {
                        outputText.text = latestExtractedText
                        downloadButton.isEnabled = true
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        outputText.text = "Error parsing output: ${e.message}"
                        downloadButton.isEnabled = false
                    }
                }
            }
        })
    }

    private fun formatSOAText(raw: String): String {
        val lines = raw.lines()
        val filtered = lines.filter {
            it.contains(Regex("\\d{2}/\\d{2}/\\d{4}")) &&
            it.contains(Regex("NEFT|WITHDRAWAL|DEPOSIT", RegexOption.IGNORE_CASE))
        }

        val formatted = StringBuilder()
        formatted.append("Date\t\tAmount\t\tType\t\tDetails\n")
        formatted.append("--------------------------------------------------\n")

        for (line in filtered) {
            val date = Regex("\\d{2}/\\d{2}/\\d{4}").find(line)?.value ?: "-"
            val amount = Regex("\\d+\\.\\d{2}").find(line)?.value ?: "-"
            val type = when {
                line.contains("withdrawal", true) -> "Withdraw"
                line.contains("deposit", true) -> "Deposit"
                line.contains("transfer", true) -> "Transfer"
                else -> "Other"
            }
            val desc = line.take(40)
            formatted.append("$date\t$amount\t$type\t$desc\n")
        }

        return formatted.toString()
    }

    private fun saveTextToFile(text: String) {
        try {
            val filename = "soa_output_${System.currentTimeMillis()}.txt"
            val file = File(getExternalFilesDir(null), filename)
            file.writeText(text)
            Toast.makeText(this, "Saved to: ${file.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error saving file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
