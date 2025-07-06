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

        progressBar.visibility = ProgressBar.GONE
        downloadButton.isEnabled = false

        // Set footer version dynamically
        val footer = findViewById<TextView>(R.id.txtFooter)
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        footer.text = "Prashant L. Mitba • v$version"

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

                    val bank = detectBankName(rawText)
                    val csv = convertTextToCsv(rawText)

                    latestExtractedText = csv
                    runOnUiThread {
                        outputText.text = latestExtractedText
                        downloadButton.isEnabled = true
                        safeToast("Bank Detected: $bank")
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

    // ==================== NORMALIZATION & PARSING ====================

    fun normalizeText(text: String): String {
        val lines = text.lines()
            .map { it.trim() }
            .filter {
                it.isNotBlank() &&
                !it.matches(Regex("""(?i)(Page No|Statement of account|MR\.|HDFC BANK LIMITED|Joint Holders|Account No|A/C Open Date|Branch Code|MICR|GSTN|Email|Phone|Address|Currency|.*GSTIN.*|.*Senapati Bapat Marg.*|Contents of this statement.*)"""))
            }

        val result = mutableListOf<String>()
        var current = ""

        fun isNewTransaction(line: String): Boolean {
            return Regex("""^\d{2}/\d{2}/\d{2}\s""").matches(line)
        }

        for (line in lines) {
            if (isNewTransaction(line)) {
                if (current.isNotBlank()) result.add(current.trim())
                current = line
            } else {
                current += " $line"
            }
        }

        if (current.isNotBlank()) result.add(current.trim())

        return result.joinToString("\n")
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
        if (bank != "HDFC Bank") return emptyList()

        val regex = Regex(
            """(?<date>\d{2}/\d{2}/\d{2})\s+(?<desc>.+?)\s+(?<ref>\d{10,}|[A-Z0-9]+)\s+(?<valuedt>\d{2}/\d{2}/\d{2})\s+(?<amount>[\d,]+\.\d{2})\s+(?<balance>[\d,]+\.\d{2})"""
        )

        val transactions = mutableListOf<Transaction>()
        var prevBalance: Double? = null

        for (match in regex.findAll(cleaned)) {
            try {
                val date = match.groups["date"]?.value ?: ""
                val desc = match.groups["desc"]?.value?.replace(",", " ") ?: ""
                val ref = match.groups["ref"]?.value ?: ""
                val amountStr = match.groups["amount"]?.value ?: ""
                val balanceStr = match.groups["balance"]?.value ?: ""

                val amount = amountStr.replace(",", "").toDoubleOrNull() ?: continue
                val balance = balanceStr.replace(",", "").toDoubleOrNull() ?: continue

                val (debit, credit) = when {
                    prevBalance == null -> Pair("", "")
                    balance > prevBalance -> Pair("", amountStr)
                    balance < prevBalance -> Pair(amountStr, "")
                    else -> Pair("", "") // no movement
                }

                prevBalance = balance

                transactions.add(
                    Transaction(
                        date = date,
                        txnId = ref,
                        remarks = desc,
                        debit = debit,
                        credit = credit,
                        balance = balanceStr
                    )
                )
            } catch (_: Exception) { }
        }

        return transactions
    }

    fun convertTextToCsv(text: String): String {
        val bank = detectBankName(text)
        val transactions = extractTransactions(text, bank)

        if (transactions.isEmpty()) {
            return "No transactions found for $bank\n\n--- RAW TEXT SAMPLE ---\n${text.take(500)}"
        }

        val builder = StringBuilder()
        builder.append("Date,TxnId,Particulars,Debit,Credit,Balance\n")
        transactions.forEach { txn ->
            val cleanDebit = txn.debit.replace(",", "")
            val cleanCredit = txn.credit.replace(",", "")
            val cleanBalance = txn.balance.replace(",", "")
            builder.append("${txn.date},${txn.txnId},\"${txn.remarks}\",${cleanDebit},${cleanCredit},${cleanBalance}\n")
        }
        return builder.toString()
    }

    data class Transaction(
        val date: String,
        val txnId: String,
        val remarks: String,
        val debit: String,
        val credit: String,
        val balance: String
    )

    object BankRegexPatterns {
        val patterns = mapOf(
            "HDFC Bank" to Regex(
                """(?<date>\d{2}/\d{2}/\d{2})\s+(?<desc>.+?)\s+(?<ref>\d{10,}|[A-Z0-9]+)\s+(?<valuedt>\d{2}/\d{2}/\d{2})\s+(?<amount>[\d,]+\.\d{2})\s+(?<balance>[\d,]+\.\d{2})"""
            )
        )
    }
}
