package com.mitbapl.soa

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import org.json.JSONObject
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaTypeOrNull

class MainActivity : AppCompatActivity() {
    private val PICK_PDF_REQUEST = 1
    private var selectedPdfUri: Uri? = null
    private lateinit var outputText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var downloadButton: Button
    private lateinit var footer: TextView
    private var csvOutput: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val uploadButton = findViewById<Button>(R.id.btnUpload)
        val analyzeButton = findViewById<Button>(R.id.btnAnalyze)
        downloadButton = findViewById(R.id.btnDownload)
        outputText = findViewById(R.id.txtOutput)
        progressBar = findViewById(R.id.progressBar)
        footer = findViewById(R.id.txtFooter)

        progressBar.visibility = ProgressBar.GONE
        downloadButton.isEnabled = false

        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "v1.0"
        }
        footer.text = "Prashant L. Mitba - v$version"

        uploadButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "application/pdf"
            startActivityForResult(Intent.createChooser(intent, "Select SOA PDF"), PICK_PDF_REQUEST)
        }

        analyzeButton.setOnClickListener {
            selectedPdfUri?.let {
                uploadPdfToServer(it)
            } ?: Toast.makeText(this, "Please select soa.pdf first", Toast.LENGTH_SHORT).show()
        }

        downloadButton.setOnClickListener {
            saveCsvToDownload(csvOutput)
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
                val rawJson = response.body?.string() ?: "{}"
                runOnUiThread { progressBar.visibility = ProgressBar.GONE }
                try {
                    val text = JSONObject(rawJson).getString("text")
                    csvOutput = convertTextToCsv(text)
                    runOnUiThread {
                        outputText.text = csvOutput
                        downloadButton.isEnabled = true
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        outputText.text = "Failed to parse response."
                        downloadButton.isEnabled = false
                    }
                }
            }
        })
    }

    private fun convertTextToCsv(text: String): String {
        val builder = StringBuilder()
        builder.append("Date,Particulars,Cheque/Reference No,Debit,Credit,Balance\n")
        val lines = text.split("\n")

        for (line in lines) {
            if (line.contains(Regex("\\d{2}/\\d{2}/\\d{4}|\\d{2}/\\d{2}/\\d{2}")) &&
                (line.contains("DEPOSIT", true) || line.contains("WITHDRAWAL", true) || line.contains("NEFT", true))
            ) {
                val date = Regex("\\d{2}/\\d{2}/\\d{4}|\\d{2}/\\d{2}/\\d{2}").find(line)?.value ?: "-"
                val ref = Regex("\\b\\d{4,}\\b").find(line)?.value ?: "-"
                val debit = if (line.contains("WITHDRAWAL", true)) Regex("\\d+\\.\\d{2}").find(line)?.value else ""
                val credit = if (line.contains("DEPOSIT", true)) Regex("\\d+\\.\\d{2}").find(line)?.value else ""
                val balance = Regex("\\d+\\.\\d{2}").findAll(line).lastOrNull()?.value ?: "-"
                val desc = line.take(30).replace(",", " ")

                builder.append("$date,$desc,$ref,${debit ?: ""},${credit ?: ""},$balance\n")
            }
        }

        return builder.toString()
    }

    private fun saveCsvToDownload(content: String) {
        try {
            val fileName = "soa_${System.currentTimeMillis()}.csv"
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloads, fileName)
            file.writeText(content)
            Toast.makeText(this, "Saved to: ${file.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error saving CSV: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
