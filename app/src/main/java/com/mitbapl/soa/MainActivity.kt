package com.mitbapl.soa

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import okhttp3.*
import java.io.*
import java.util.*
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private val PICK_PDF_REQUEST = 1
    private var selectedPdfUri: Uri? = null
    private lateinit var outputText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var downloadButton: Button
    private var extractedText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val uploadButton = findViewById<Button>(R.id.btnUpload)
        val analyzeButton = findViewById<Button>(R.id.btnAnalyze)
        downloadButton = findViewById(R.id.btnDownload)
        outputText = findViewById(R.id.txtOutput)
        progressBar = findViewById(R.id.progressBar)

        progressBar.visibility = ProgressBar.INVISIBLE
        downloadButton.visibility = Button.INVISIBLE

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
            saveTextToFileAndShare(extractedText)
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
        downloadButton.visibility = Button.INVISIBLE

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
                    progressBar.visibility = ProgressBar.INVISIBLE
                    outputText.text = "Error: ${e.message}"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val json = response.body?.string()
                runOnUiThread {
                    progressBar.visibility = ProgressBar.INVISIBLE
                    if (json != null) {
                        try {
                            val text = JSONObject(json).getString("text")
                            extractedText = text
                            outputText.text = text
                            downloadButton.visibility = Button.VISIBLE
                        } catch (e: Exception) {
                            outputText.text = "Invalid server response"
                        }
                    } else {
                        outputText.text = "Empty response"
                    }
                }
            }
        })
    }

    private fun saveTextToFileAndShare(text: String) {
        val file = File(getExternalFilesDir(null), "extracted_text.txt")
        file.writeText(text)

        val uri = FileProvider.getUriForFile(
            this,
            "${packageName}.provider",
            file
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivity(Intent.createChooser(intent, "Share extracted text"))
    }
}
