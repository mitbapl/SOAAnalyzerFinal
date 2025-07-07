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
    private lateinit var summaryText: TextView
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
        summaryText = findViewById(R.id.txtSummary)
        progressBar = findViewById(R.id.progressBar)

        progressBar.visibility = ProgressBar.GONE
        downloadButton.isEnabled = false

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
                    val transactions = extractTransactions(rawText, bank)
                    val summary = summarizeByFinancialYear(transactions)
                    val csv = convertTextToCsv(rawText)
                    latestExtractedText = csv

                    runOnUiThread {
                        outputText.text = "Transactions Parsed: ${transactions.size}"
                        summaryText.text = summary
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

    fun summarizeByFinancialYear(transactions: List<Transaction>): String {
        val fyMap = mutableMapOf<String, MutableList<Transaction>>()

        fun getFY(date: String): String {
            val parts = date.split("/")
            if (parts.size != 3) return "Unknown"
            val m = parts[1].toIntOrNull() ?: return "Unknown"
            val y = parts[2].toIntOrNull() ?: return "Unknown"
            return if (m >= 4) "20$y–20${y + 1}" else "20${y - 1}–20$y"
        }

        transactions.forEach { txn ->
            val fy = getFY(txn.date)
            fyMap.getOrPut(fy) { mutableListOf() }.add(txn)
        }

        val builder = StringBuilder()
        builder.append("FY        | Debits     | Credits    | Debit Count | Credit Count\n")
        builder.append("-------------------------------------------------------------\n")

        for ((fy, txns) in fyMap) {
            val debits = txns.mapNotNull { it.debit.replace(",", "").toDoubleOrNull() }.sum()
            val credits = txns.mapNotNull { it.credit.replace(",", "").toDoubleOrNull() }.sum()
            val debitCount = txns.count { it.debit.isNotBlank() }
            val creditCount = txns.count { it.credit.isNotBlank() }
            builder.append(String.format("%-10s | %11.2f | %11.2f | %12d | %13d\n", fy, debits, credits, debitCount, creditCount))
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

    fun detectBankName(text: String): String {
        val knownBanks = listOf("HDFC Bank")
        val normalized = text.lowercase(Locale.ROOT)
        return knownBanks.firstOrNull {
            normalized.contains(it.lowercase(Locale.ROOT).replace("'", ""))
        } ?: "Unknown"
    }

    fun normalizeText(text: String): String {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val result = mutableListOf<String>()
        var current = ""

        fun isNewTxn(line: String): Boolean = Regex("""^\d{2}/\d{2}/\d{2}\s""").matches(line)

        for (line in lines) {
            if (isNewTxn(line)) {
                if (current.isNotBlank()) result.add(current.trim())
                current = line
            } else {
                current += " $line"
            }
        }

        if (current.isNotBlank()) result.add(current.trim())

        return result.joinToString("\n")
    }

    fun extractTransactions(text: String, bank: String): List<Transaction> {
        val cleaned = normalizeText(text)
        val regex = Regex(
            """(?<date>\d{2}/\d{2}/\d{2})\s+(?<desc>.+?)\s+(?<ref>\d{10,}|[A-Z0-9]+)\s+(?<valuedt>\d{2}/\d{2}/\d{2})\s+(?<amount>[\d,]+\.\d{2})\s+(?<balance>[\d,]+\.\d{2})"""
        )

        val transactions = mutableListOf<Transaction>()
        var prevBalance: Double? = null

        for (match in regex.findAll(cleaned)) {
            try {
                val date = match.groups["date"]?.value ?: continue
                val desc = match.groups["desc"]?.value?.replace(",", " ") ?: ""
                val ref = match.groups["ref"]?.value ?: ""
                val amountStr = match.groups["amount"]?.value ?: continue
                val balanceStr = match.groups["balance"]?.value ?: continue

                val amount = amountStr.replace(",", "").toDoubleOrNull() ?: continue
                val balance = balanceStr.replace(",", "").toDoubleOrNull() ?: continue

                val (debit, credit) = when {
                    prevBalance == null -> Pair("", "")
                    balance > prevBalance -> Pair("", amountStr)
                    balance < prevBalance -> Pair(amountStr, "")
                    else -> Pair("", "")
                }

                prevBalance = balance

                transactions.add(Transaction(date, ref, desc, debit, credit, balanceStr))
            } catch (_: Exception) {}
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
}
