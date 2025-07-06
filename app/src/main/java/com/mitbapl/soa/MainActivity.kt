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
                    val summary = getStatementSummary(csv)

                    runOnUiThread {
                        outputText.text = summary
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
                    else -> Pair("", "")
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

    fun getStatementSummary(csv: String): String {
        val lines = csv.lines().drop(1).filter { it.isNotBlank() } // skip header
        if (lines.isEmpty()) return "No transaction data."

        var debitCount = 0
        var creditCount = 0
        var debitTotal = 0.0
        var creditTotal = 0.0

        val balances = mutableListOf<Double>()

        lines.forEach { line ->
            val parts = line.split(",")
            if (parts.size < 6) return@forEach

            val debit = parts[3].toDoubleOrNull()
            val credit = parts[4].toDoubleOrNull()
            val balance = parts[5].toDoubleOrNull()

            if (debit != null && debit > 0) {
                debitCount++
                debitTotal += debit
            }
            if (credit != null && credit > 0) {
                creditCount++
                creditTotal += credit
            }
            if (balance != null) balances.add(balance)
        }

        val openingBalance = if (balances.size > 1) {
            val first = balances.first()
            val firstLine = lines[0].split(",")
            val delta = if (firstLine[3].isNotBlank()) {
                first + firstLine[3].toDoubleOrNull()!!
            } else {
                first - firstLine[4].toDoubleOrNull()!!
            }
            delta
        } else 0.0

        val closingBalance = balances.lastOrNull() ?: 0.0

        return """
            STATEMENT SUMMARY :-
            Opening Balance  : ₹%,.2f
            Debit Count      : $debitCount
            Credit Count     : $creditCount
            Total Debits     : ₹%,.2f
            Total Credits    : ₹%,.2f
            Closing Balance  : ₹%,.2f
        """.trimIndent().format(openingBalance, debitTotal, creditTotal, closingBalance)
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
