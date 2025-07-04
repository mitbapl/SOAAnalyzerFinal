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
import android.os.Build
import android.content.pm.PackageManager

class MainActivity : AppCompatActivity() {

    private val PICK_PDF_REQUEST = 1
    private var selectedPdfUri: Uri? = null
    private lateinit var outputText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var downloadButton: Button
    private lateinit var footer: TextView
    private var latestCSVText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val uploadButton = findViewById<Button>(R.id.btnUpload)
        val analyzeButton = findViewById<Button>(R.id.btnAnalyze)
        outputText = findViewById(R.id.txtOutput)
        progressBar = findViewById(R.id.progressBar)
        downloadButton = findViewById(R.id.btnDownload)
        footer = findViewById(R.id.footer)

        downloadButton.isEnabled = false
        progressBar.visibility = ProgressBar.GONE

        // Dynamically set app version
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: PackageManager.NameNotFoundException) {
            "1.0"
        }
        footer.text = "Prashant L. Mitba - v$version"

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
            if (latestCSVText.isNotEmpty()) saveCSVToDownloads(latestCSVText)
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
            .writeTimeout(5, TimeUnit.MINUTES)
            .readTimeout(5, TimeUnit.MINUTES)
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
                runOnUiThread { progressBar.visibility = ProgressBar.GONE }
                val json = response.body?.string() ?: "No response"
                try {
                    val jsonObject = JSONObject(json)
                    val rawText = jsonObject.getString("text")
                    val csvOutput = convertToCSV(rawText)
                    latestCSVText = csvOutput
                    runOnUiThread {
                        outputText.text = csvOutput
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

    private fun convertToCSV(raw: String): String {
        val sb = StringBuilder()
        sb.append("Date,Amount,Type,Details\n")

        val lines = raw.lines()
        for (line in lines) {
            val date = Regex("\\d{2}/\\d{2}/\\d{4}").find(line)?.value ?: continue
            val amount = Regex("\\d+\\.\\d{2}").find(line)?.value ?: "-"
            val type = when {
                line.contains("withdraw", true) -> "Withdraw"
                line.contains("deposit", true) -> "Deposit"
                line.contains("transfer", true) -> "Transfer"
                else -> "Other"
            }
            val desc = line.take(30).replace(",", " ")
            sb.append("$date,$amount,$type,$desc\n")
        }
        return sb.toString()
    }

    private fun saveCSVToDownloads(text: String) {
        try {
            val filename = "soa_export_${System.currentTimeMillis()}.csv"
            val file = File("/storage/emulated/0/Download", filename)
            file.writeText(text)
            Toast.makeText(this, "Saved to: ${file.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error saving file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
