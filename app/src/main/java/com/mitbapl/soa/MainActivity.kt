package com.mitbapl.soa

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import okhttp3.*
import org.json.JSONObject
import java.io.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.collections.HashMap

class MainActivity : AppCompatActivity() {

    private val PICK_PDF_REQUEST_CODE = 1
    private val CREATE_FILE_REQUEST_CODE = 2
    private lateinit var summaryTextView: TextView
    private lateinit var saveCsvButton: Button
    private var extractedText: String? = null
    private var financialSummary: Map<String, Map<String, Double>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val selectPdfButton: Button = findViewById(R.id.selectPdfButton)
        summaryTextView = findViewById(R.id.summaryTextView)
        saveCsvButton = findViewById(R.id.saveCsvButton)

        selectPdfButton.setOnClickListener {
            openFilePicker()
        }

        saveCsvButton.setOnClickListener {
            financialSummary?.let {
                createCsvFile(convertSummaryToCsv(it))
            } ?: Toast.makeText(this, "No data to save", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "application/pdf"
        startActivityForResult(Intent.createChooser(intent, "Select PDF"), PICK_PDF_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == PICK_PDF_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                extractTextFromPdf(uri)?.let { text ->
                    extractedText = text
                    sendTextToBackend(text)
                }
            }
        }

        if (requestCode == CREATE_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                saveCsvToFile(uri)
            }
        }
    }

    private fun extractTextFromPdf(uri: Uri): String? {
        return try {
            val inputStream = contentResolver.openInputStream(uri)
            val document = PDDocument.load(inputStream)
            val pdfStripper = PDFTextStripper()
            val text = pdfStripper.getText(document)
            document.close()
            text
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error extracting text", Toast.LENGTH_SHORT).show()
            null
        }
    }

    private fun sendTextToBackend(text: String) {
        val client = OkHttpClient()
        val requestBody = RequestBody.create("text/plain".toMediaTypeOrNull(), text)
        val request = Request.Builder()
            .url("https://soa.mitbapl.com/analyze") // Replace with your endpoint
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Network error", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    response.body?.string()?.let { jsonString ->
                        runOnUiThread {
                            handleBackendResponse(jsonString)
                        }
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "Server error", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    private fun handleBackendResponse(jsonString: String) {
        try {
            val jsonObject = JSONObject(jsonString)
            val transactionsArray = jsonObject.getJSONArray("transactions")
            val transactions = mutableListOf<Transaction>()

            for (i in 0 until transactionsArray.length()) {
                val obj = transactionsArray.getJSONObject(i)
                val date = obj.getString("date")
                val description = obj.getString("description")
                val amount = obj.getDouble("amount")
                val type = obj.getString("type")
                transactions.add(Transaction(date, description, amount, type))
            }

            financialSummary = summarizeByFinancialYear(transactions)
            summaryTextView.text = formatSummaryForDisplay(financialSummary)
            saveCsvButton.isEnabled = true

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error parsing response", Toast.LENGTH_SHORT).show()
        }
    }

    private fun summarizeByFinancialYear(transactions: List<Transaction>): Map<String, Map<String, Double>> {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val summary = HashMap<String, HashMap<String, Double>>()

        for (txn in transactions) {
            val date = LocalDate.parse(txn.date, formatter)
            val fy = if (date.monthValue >= 4) "${date.year}-${date.year + 1}" else "${date.year - 1}-${date.year}"

            val yearSummary = summary.getOrPut(fy) { HashMap() }
            yearSummary[txn.type] = yearSummary.getOrDefault(txn.type, 0.0) + txn.amount
        }

        return summary
    }

    private fun formatSummaryForDisplay(summary: Map<String, Map<String, Double>>?): String {
        if (summary == null) return ""
        val builder = StringBuilder()
        summary.forEach { (fy, data) ->
            builder.append("FY $fy:\n")
            data.forEach { (type, total) ->
                builder.append("  $type: ₹%.2f\n".format(total))
            }
            builder.append("\n")
        }
        return builder.toString()
    }

    private fun convertSummaryToCsv(summary: Map<String, Map<String, Double>>): String {
        val builder = StringBuilder()
        builder.append("Financial Year,Type,Amount\n")
        summary.forEach { (fy, data) ->
            data.forEach { (type, amount) ->
                builder.append("$fy,$type,$amount\n")
            }
        }
        return builder.toString()
    }

    private fun createCsvFile(csvData: String) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/csv"
            putExtra(Intent.EXTRA_TITLE, "SOA_Summary.csv")
        }
        startActivityForResult(intent, CREATE_FILE_REQUEST_CODE)
    }

    private fun saveCsvToFile(uri: Uri) {
        try {
            val outputStream = contentResolver.openOutputStream(uri)
            outputStream?.write(convertSummaryToCsv(financialSummary!!).toByteArray())
            outputStream?.close()
            Toast.makeText(this, "CSV saved", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to save CSV", Toast.LENGTH_SHORT).show()
        }
    }

    data class Transaction(
        val date: String,
        val description: String,
        val amount: Double,
        val type: String
    )
}
