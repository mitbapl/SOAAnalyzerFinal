package com.mitbapl.soa

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.*
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class MainActivity : AppCompatActivity() {
    private val PICK_PDF_REQUEST = 1
    private var selectedPdfUri: Uri? = null
    private lateinit var outputText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var exportButton: Button
    private var extractedCsv = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val uploadButton = findViewById<Button>(R.id.btnUpload)
        val analyzeButton = findViewById<Button>(R.id.btnAnalyze)
        outputText = findViewById(R.id.txtOutput)
        progressBar = findViewById(R.id.progressBar)
        exportButton = findViewById(R.id.btnExport)

        progressBar.visibility = View.GONE
        exportButton.visibility = View.GONE

        uploadButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "application/pdf"
            startActivityForResult(Intent.createChooser(intent, "Select SOA PDF"), PICK_PDF_REQUEST)
        }

        analyzeButton.setOnClickListener {
            if (selectedPdfUri != null) {
                progressBar.visibility = View.VISIBLE
                uploadPdfToServer(selectedPdfUri!!)
            } else {
                Toast.makeText(this, "Please select soa.pdf first", Toast.LENGTH_SHORT).show()
            }
        }

        exportButton.setOnClickListener {
            exportToCsv()
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
        val inputStream = contentResolver.openInputStream(pdfUri) ?: return
        val fileBytes = inputStream.readBytes()

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", "soa.pdf",
                RequestBody.create("application/pdf".toMediaTypeOrNull(), fileBytes)
            )
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
                    progressBar.visibility = View.GONE
                    outputText.text = "Error: ${e.message}"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val json = response.body?.string() ?: "No response"
                val extractedText = Regex("\"text\"\\s*:\\s*\"(.*?)\"\\s*}").find(json)?.groupValues?.get(1)
                    ?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: "Text parsing failed."

                runOnUiThread {
                    progressBar.visibility = View.GONE
                    outputText.text = extractedText
                    val bank = detectBank(extractedText)
                    val csv = applyBankRegex(extractedText, bank)
                    extractedCsv = csv
                    exportButton.visibility = View.VISIBLE
                }
            }
        })
    }

    private fun detectBank(text: String): String {
        return when {
            text.contains("UNION BANK", true) -> "union"
            text.contains("INDIAN BANK", true) -> "indian"
            text.contains("HDFC BANK", true) -> "hdfc"
            text.contains("ICICI", true) -> "icici"
            text.contains("BANK OF MAHARASHTRA", true) || text.contains("MAHB") -> "bom"
            else -> "generic"
        }
    }

    private fun applyBankRegex(text: String, bank: String): String {
        val lines = mutableListOf<String>()
        val regex = when (bank) {
            "union" -> Regex("""(\d{2}/\d{2}/\d{4})\s+(S\d+|A\d+)\s+(.+?)\s+(\d+\.\d{2})\s+\((Dr|Cr)\)\s+(\d+\.\d{2})""")
            "indian" -> Regex("""(\d{2}/\d{2}/\n2025).+?NEFT/(\w+)/([\w\d]+)[\s\S]+?Txn Amt\.\s+(\d+\.\d{2})""")
            "hdfc" -> Regex("""(\d{2}/\d{2}/\d{2})\s+(IMPS.*?|CASH DEPOSIT.*?)\s+\d{10,}\s+\d{2}/\d{2}/\d{2}\s+(\d{1,3}(?:,\d{3})*(?:\.\d{2})?)\s+(\d{1,3}(?:,\d{3})*(?:\.\d{2})?)""")
            "icici" -> Regex("""(\d{2}/\d{2}/\d{4})\s+\d{2}/\d{2}/\d{4} - (UPI/.+?)\/([A-Z\s]+)/([\w\/]+)\s+(\d+\.\d{2})\s+(\d+\.\d{2})\s+(\d+\.\d{2})""")
            "bom" -> Regex("""(\d+)\s+(\d{2}/\d{2}/\d{4})\s+(UPI\s[\s\S]+?)\n(\d+)\s+(\d+\.\d{2}|-)\s+(\d+\.\d{2}|-)\s+(\d+\.\d{2})""")
            else -> Regex("""(\d{2}/\d{2}/\d{4}).+?(\d+\.\d{2})""")
        }

        lines.add("Date,Description,Amount,Type,Balance")
        regex.findAll(text).forEach {
            val groups = it.groupValues
            when (bank) {
                "union" -> lines.add("${groups[1]},${groups[3]},${groups[4]},${groups[5]},${groups[6]}")
                "indian" -> lines.add("${groups[1]},NEFT/${groups[2]}/${groups[3]},${groups[4]},,")
                "hdfc" -> lines.add("${groups[1]},${groups[2]},${groups[3]},,${groups[4]}")
                "icici" -> lines.add("${groups[1]},${groups[2]},${groups[5]},,${groups[7]}")
                "bom" -> lines.add("${groups[2]},${groups[3]},${groups[5]},,${groups[7]}")
                else -> lines.add("${groups[1]},Unknown,${groups[2]},,")
            }
        }

        return lines.joinToString("\n")
    }

    private fun exportToCsv() {
        try {
            val filename = "soa_output_${System.currentTimeMillis()}.csv"
            val file = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), filename)
            file.writeText(extractedCsv)
            Toast.makeText(this, "Saved: ${file.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
