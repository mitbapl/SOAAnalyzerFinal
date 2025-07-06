package com.mitbapl.soa

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import org.json.JSONObject
import java.io.*
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaTypeOrNull

class MainActivity : AppCompatActivity() {

    private lateinit var resultTextView: TextView
    private lateinit var analyzeButton: Button
    private lateinit var downloadButton: Button
    private lateinit var progressBar: ProgressBar

    private var selectedPdfUri: Uri? = null
    private var latestCsvOutput: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        resultTextView = findViewById(R.id.resultTextView)
        analyzeButton = findViewById(R.id.analyzeButton)
        downloadButton = findViewById(R.id.downloadButton)
        progressBar = findViewById(R.id.progressBar)

        downloadButton.isEnabled = false
        progressBar.visibility = ProgressBar.INVISIBLE

        analyzeButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "application/pdf"
            startActivityForResult(intent, 1)
        }

        downloadButton.setOnClickListener {
            downloadCsv()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == Activity.RESULT_OK) {
            selectedPdfUri = data?.data
            selectedPdfUri?.let { uploadToTikaServer(it) }
        }
    }

    private fun uploadToTikaServer(uri: Uri) {
        progressBar.visibility = ProgressBar.VISIBLE
        val inputStream = contentResolver.openInputStream(uri) ?: return
        val fileBytes = inputStream.readBytes()

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", getFileName(uri),
                RequestBody.create("application/pdf".toMediaTypeOrNull(), fileBytes))
            .build()

        val request = Request.Builder()
            .url("https://tika-server.onrender.com/analyze")
            .post(requestBody)
            .build()

        val client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.MINUTES)
            .readTimeout(2, TimeUnit.MINUTES)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    progressBar.visibility = ProgressBar.INVISIBLE
                    resultTextView.text = "Error: ${e.message}"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseText = response.body?.string() ?: ""
                val json = JSONObject(responseText)
                val rawText = json.optString("text", "")

                val normalizedText = normalizeText(rawText)
                val csv = extractHdfcCsv(normalizedText)

                runOnUiThread {
                    progressBar.visibility = ProgressBar.INVISIBLE
                    resultTextView.text = csv
                    latestCsvOutput = csv
                    downloadButton.isEnabled = csv.contains(",") // Enable if there is data
                }
            }
        })
    }

    private fun normalizeText(text: String): String {
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val merged = mutableListOf<String>()

        for (line in lines) {
            if (Regex("""^\d{2}/\d{2}/\d{2}""").containsMatchIn(line)) {
                merged.add(line)
            } else if (merged.isNotEmpty()) {
                merged[merged.size - 1] += " $line"
            }
        }

        return merged.joinToString("\n")
    }

    private fun extractHdfcCsv(text: String): String {
        val regex = Regex("""(?m)^(\d{2}/\d{2}/\d{2})\s+(.*?)\s+(\d{15}|-)?\s+(\d{2}/\d{2}/\d{2})\s+((?:\d{1,3}(?:,\d{3})*|\d+)?(?:\.\d{2})?)?\s*((?:\d{1,3}(?:,\d{3})*|\d+)?(?:\.\d{2})?)?\s+((?:\d{1,3}(?:,\d{3})*|\d+)?(?:\.\d{2}))$""")
        val builder = StringBuilder()
        builder.append("Date,Narration,Ref No,Debit,Credit,Balance\n")

        val matches = regex.findAll(text)
        if (matches.none()) return "No transactions found for HDFC Bank\n\n--- RAW TEXT SAMPLE ---\n" + text.take(500)

        for (match in matches) {
            val date = match.groupValues[1]
            val narration = match.groupValues[2].replace(",", " ")
            val ref = match.groupValues[3].ifBlank { "-" }
            val debit = match.groupValues[5].takeIf { it.isNotEmpty() && match.groupValues[6].isEmpty() } ?: ""
            val credit = match.groupValues[6].takeIf { it.isNotEmpty() } ?: ""
            val balance = match.groupValues[7]

            builder.append("$date,\"$narration\",$ref,$debit,$credit,$balance\n")
        }

        return builder.toString()
    }

    private fun downloadCsv() {
        val fileName = "HDFC_Statement_${System.currentTimeMillis()}.csv"
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = File(downloadsDir, fileName)
        file.writeText(latestCsvOutput)
        Toast.makeText(this, "Saved to Downloads/$fileName", Toast.LENGTH_LONG).show()
    }

    private fun getFileName(uri: Uri): String {
        var result = "statement.pdf"
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) result = it.getString(index)
            }
        }
        return result
    }
}
