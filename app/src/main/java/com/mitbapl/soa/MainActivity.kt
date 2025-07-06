package com.mitbapl.soa

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
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
                safeToast("Please select soa.pdf first")
            }
        }

        downloadButton.setOnClickListener {
            saveTextToDownloads(latestExtractedText)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_PDF_REQUEST && resultCode == Activity.RESULT_OK) {
            selectedPdfUri = data?.data
            selectedPdfUri?.let {
                safeToast("File selected: ${getFileName(it)}")
            }
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
                    val bank = SoaParser.detectBankName(rawText)

                    Log.d("BANK", "Detected: $bank")
                    safeToast("Bank Detected: $bank")

                    val csv = SoaParser.convertTextToCsv(rawText)
                    latestExtractedText = csv

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

    private fun saveTextToDownloads(text: String) {
        val fileName = "extracted_soa_${System.currentTimeMillis()}.csv"
        try {
            val downloadsDir = File("/storage/emulated/0/Download")
            if (!downloadsDir.exists()) downloadsDir.mkdirs()

            val file = File(downloadsDir, fileName)
            file.writeText(text)

            safeToast("Saved to ${file.absolutePath}")
        } catch (e: Exception) {
            safeToast("Failed to save: ${e.message}")
        }
    }

    private fun safeToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
}

// ========= SoaParser ==========

object SoaParser {
    fun normalizeText(text: String): String {
        return text.replace(Regex("(\\d{2}/\\d{2}/)\\n(\\d{4})"), "$1$2")
            .replace(Regex("\\n+"), "\n")
            .replace(Regex("[ \t]+"), " ")
            .trim()
    }

    fun detectBankName(text: String): String {
        val knownBanks = BankRegexPatterns.patterns.keys
        val normalized = text.lowercase(Locale.ROOT)
        return knownBanks.firstOrNull {
            normalized.contains(it.lowercase(Locale.ROOT).replace("'", ""))
        } ?: "Unknown"
    }

    fun extractTransactions(text: String, bank: String): List<Transaction> {
        val cleaned = normalizeText(text)
        val regex = BankRegexPatterns.patterns[bank] ?: return emptyList()

        return regex.findAll(cleaned).mapNotNull { match ->
            try {
                val amount = match.groups["credit"]?.value?.takeIf { it != "0.00" }
                    ?: match.groups["debit"]?.value?.let { "-$it" }
                    ?: match.groups["amount"]?.value ?: ""

                Transaction(
                    date = match.groups["date"]?.value ?: "",
                    txnId = match.groups["txnId"]?.value ?: "",
                    remarks = match.groups["desc"]?.value ?: match.groups["remarks"]?.value ?: "",
                    amount = amount,
                    balance = match.groups["balance"]?.value ?: ""
                )
            } catch (e: Exception) {
                null
            }
        }.toList()
    }

    fun convertTextToCsv(text: String): String {
        val bank = detectBankName(text)
        val transactions = extractTransactions(text, bank)

        if (transactions.isEmpty()) {
            return "No transactions found for $bank\n\n--- RAW TEXT SAMPLE ---\n${text.take(500)}"
        }

        val builder = StringBuilder()
        builder.append("Date,TxnId,Particulars,Amount,Balance\n")
        transactions.forEach { txn ->
            builder.append("${txn.date},${txn.txnId},${txn.remarks},${txn.amount},${txn.balance}\n")
        }
        return builder.toString()
    }

    data class Transaction(
        val date: String,
        val txnId: String,
        val remarks: String,
        val amount: String,
        val balance: String
    )
}

// ========= Regex Patterns ==========
object BankRegexPatterns {
    val patterns = mapOf(
        "HDFC Bank" to Regex(
            """(?<date>\d{2}/\d{2}/\d{2})\s+(?<desc>.+?)\s+\d{9,18}\s+\d{2}/\d{2}/\d{2}\s+(?<amount>[\d,]+\.\d{2})\s+(?<balance>[\d,]+\.\d{2})"""
        ),
        "Bank of Maharashtra" to Regex(
            """(?<date>\d{2}/\d{2}/\d{4})\s+UPI\s+(?<txnId>\d+)/(?:\w+)/(?<desc>.+?)\s+\d+\s+(?<debit>[\d,]+\.\d{2})\s+-\s+(?<balance>[\d,]+\.\d{2})"""
        ),
        "Union Bank of India" to Regex(
            """(?<date>\d{2}/\d{2}/\d{4})\s+(?<txnId>S\d+)\s+(?<desc>.+?)\s+(?<amount>[\d,]+\.\d{2})\s+\(?(?<type>Dr|Cr)\)?\s+(?<balance>[\d,]+\.\d{2})\s+\(Cr\)"""
        ),
        "Indian Bank" to Regex(
            """(?<date>\d{2}/\d{2}/\d{4})\s+\d{2}/\d{2}/\d{4}\s+(?<credit>[\d,]+\.\d{2})\s+(?<debit>[\d,]+\.\d{2})\s+(?<desc>.+)"""
        ),
        "Kotak Bank" to Regex(
            """(?<desc>UPI\/.+?)\/(?<ref>\d+)\/UPI\s+UPI-\d+\s+(?<type>CR|DR)(?<amount>[\d,]+\.\d{2})\s+(?<date>\d{2}/\d{2}/\d{4})\s+CR(?<balance>[\d,]+\.\d{2})"""
        ),
        "ICICI Bank" to Regex(
            """(?<date>\d{2}/\d{2}/\d{4})\s+\d{2}/\d{2}/\d{4}\s+-\s+UPI/(?<desc>.+?)\/[^\s]+\s+(?<debit>[\d,]+\.\d{2})\s+(?<credit>[\d,]+\.\d{2})\s+(?<balance>[\d,]+\.\d{2})"""
        )
    )
}


