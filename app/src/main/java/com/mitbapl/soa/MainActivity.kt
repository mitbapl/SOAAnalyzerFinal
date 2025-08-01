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
                    val recurring = detectRecurringDebits(transactions)

                    val finalSummary = buildString {
                        append(summary)
                        append("\n\n--- Recurring Debits (3+ times) ---\n")
                        if (recurring.isEmpty()) append("None detected.\n")
                        else recurring.forEach { append("• $it\n") }
                    }

                    val csv = convertTextToCsv(rawText)
                    latestExtractedText = csv

                    runOnUiThread {
                        outputText.text = "Transactions Parsed: ${transactions.size}"
                        summaryText.text = finalSummary
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

    data class Transaction(
        val date: String,
        val remarks: String,
        val txnId: String,
        val debit: String,
        val credit: String,
        val balance: String
    )

    fun extractTransactions(text: String, bank: String): List<Transaction> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val transactions = mutableListOf<Transaction>()

        val txnRegex = Regex("""^(\d{2}/\d{2}/\d{2})\s+(.*?)(\d{14,}|[A-Z0-9]{10,})\s+(\d{2}/\d{2}/\d{2})\s+([\d,]+\.\d{2})\s+([\d,]+\.\d{2})$""")
        val datePrefixRegex = Regex("""^\d{2}/\d{2}/\d{2}""")

        var buffer = mutableListOf<String>()
        var prevBalance: Double? = null

        fun flushBuffer() {
            if (buffer.isEmpty()) return
            val line = buffer.joinToString(" ").replace("  ", " ").trim()
            buffer.clear()

            val match = txnRegex.find(line)
            if (match != null) {
                val (date, desc, ref, _, amountStr, balanceStr) = match.destructured
                val amount = amountStr.replace(",", "").toDoubleOrNull() ?: return
                val balance = balanceStr.replace(",", "").toDoubleOrNull() ?: return

                val (debit, credit) = when {
                    prevBalance == null -> "" to ""
                    balance > prevBalance -> "" to amountStr
                    balance < prevBalance -> amountStr to ""
                    else -> "" to ""
                }
                prevBalance = balance
                transactions.add(Transaction(date, desc.trim(), ref, debit, credit, balanceStr))
            }
        }

        for (line in lines) {
            if (datePrefixRegex.containsMatchIn(line)) {
                flushBuffer()
            }
            buffer.add(line)
        }
        flushBuffer()
        return transactions
    }

    fun detectBankName(text: String): String {
        return if (text.contains("HDFC", true)) "HDFC Bank" else "Unknown"
    }

    fun summarizeByFinancialYear(transactions: List<Transaction>): String {
        val fyMap = mutableMapOf<String, MutableList<Transaction>>()

        fun getFY(date: String): String {
            val parts = date.split("/")
            val m = parts.getOrNull(1)?.toIntOrNull() ?: return "Unknown"
            val y = parts.getOrNull(2)?.toIntOrNull() ?: return "Unknown"
            return if (m >= 4) "20$y–20${y + 1}" else "20${y - 1}–20$y"
        }

        transactions.forEach { txn ->
            val fy = getFY(txn.date)
            fyMap.getOrPut(fy) { mutableListOf() }.add(txn)
        }

        return buildString {
            append("FY        | Debits     | Credits    | Debit Count | Credit Count\n")
            append("-------------------------------------------------------------\n")
            for ((fy, txns) in fyMap) {
                val debits = txns.sumOf { it.debit.replace(",", "").toDoubleOrNull() ?: 0.0 }
                val credits = txns.sumOf { it.credit.replace(",", "").toDoubleOrNull() ?: 0.0 }
                val debitCount = txns.count { it.debit.isNotBlank() }
                val creditCount = txns.count { it.credit.isNotBlank() }
                append(String.format("%-10s | %11.2f | %11.2f | %12d | %13d\n", fy, debits, credits, debitCount, creditCount))
            }
        }
    }

    fun detectRecurringDebits(transactions: List<Transaction>): List<String> {
        val recurring = mutableMapOf<String, MutableList<Transaction>>()

        fun cleanRemarks(remarks: String): String =
            remarks.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9 ]"), " ").replace("\\s+".toRegex(), " ").trim()

        transactions.filter { it.debit.isNotBlank() }.forEach { txn ->
            val key = cleanRemarks(txn.remarks)
            recurring.getOrPut(key) { mutableListOf() }.add(txn)
        }

        return recurring.filter { it.value.size >= 3 }.map { (key, txns) ->
            val total = txns.sumOf { it.debit.replace(",", "").toDoubleOrNull() ?: 0.0 }
            "$key — ${txns.size} times — ₹%.2f".format(total)
        }
    }

    fun convertTextToCsv(text: String): String {
        val transactions = extractTransactions(text, detectBankName(text))
        if (transactions.isEmpty()) return "No transactions parsed.\n\n${text.take(300)}"

        return buildString {
            append("Date,TxnId,Particulars,Debit,Credit,Balance\n")
            transactions.forEach { txn ->
                append("${txn.date},${txn.txnId},\"${txn.remarks}\",${txn.debit},${txn.credit},${txn.balance}\n")
            }
        }
    }
}
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
                    val recurring = detectRecurringDebits(transactions)
                    val finalSummary = buildString {
                        append(summary)
                        append("\n\n--- Recurring Debits (3+ times) ---\n")
                        if (recurring.isEmpty()) {
                            append("None detected.\n")
                        } else {
                            recurring.forEach { append("• $it\n") }
                        }
                    }

                    val csv = convertTextToCsv(rawText)
                    latestExtractedText = csv

                    runOnUiThread {
                        outputText.text = "Transactions Parsed: ${transactions.size}"
                        summaryText.text = finalSummary
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

    fun cleanRemarks(remarks: String): String {
        return remarks
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    fun detectRecurringDebits(transactions: List<Transaction>): List<String> {
        val recurring = mutableMapOf<String, MutableList<Transaction>>()

        transactions.filter { it.debit.isNotBlank() }.forEach { txn ->
            val key = cleanRemarks(txn.remarks)
            recurring.getOrPut(key) { mutableListOf() }.add(txn)
        }

        return recurring.filter { it.value.size >= 3 }.map { (key, txns) ->
            val total = txns.sumOf { it.debit.replace(",", "").toDoubleOrNull() ?: 0.0 }
            val labels = classifyRecurringMulti(key)
            "[$labels] ${key.replaceFirstChar { it.uppercaseChar() }} — ${txns.size} times — ₹%.2f".format(total)
        }.sortedByDescending {
            Regex("""₹([0-9,.]+)""").find(it)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() ?: 0.0
        }
    }

    fun classifyRecurringMulti(description: String): String {
        val recurringCategories = mapOf(
            "EMI" to listOf("emi", "loan", "instalment", "cashe", "creditline", "aeries", "bajajfin", "nbfc", "ach", "nach", "mandate"),
            "Rent" to listOf("rent", "lease", "licence fee"),
            "Subscription" to listOf("netflix", "prime", "hotstar", "spotify", "playstore", "mandate", "subscription", "recharge", "amazonbill"),
            "Credit Card" to listOf("credit card", "cc payment", "card payment", "hdfc card", "icici card"),
            "Utility" to listOf("electricity", "water", "gas", "broadband", "mobile", "bill", "mahadiscom"),
            "Insurance" to listOf("hdfc ergo", "tata aia", "lic", "sbi life", "insurance", "premium"),
            "Investment" to listOf("sip", "mutual fund", "nifty", "demat", "zerodha", "upstox"),
            "Medical" to listOf("hospital", "ivf", "clinic", "apollo", "test", "indiraivf"),
            "Food" to listOf("zomato", "swiggy", "gokhana", "eat", "box8", "faasos", "meals", "tobox"),
            "Self Transfer" to listOf("myself", "self", "own account")
        )

        val desc = description.lowercase(Locale.ROOT)
        return recurringCategories.filter { (_, keywords) ->
            keywords.any { desc.contains(it) }
        }.keys.joinToString("/") // e.g., "EMI/Insurance"
            .ifBlank { "Other" }
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
