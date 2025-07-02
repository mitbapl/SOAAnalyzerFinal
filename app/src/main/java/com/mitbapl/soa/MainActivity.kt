package com.mitbapl.soa

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.*
import java.util.*
import java.util.concurrent.TimeUnit

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
        outputText = findViewById(R.id.txtOutput)
        progressBar = findViewById(R.id.progressBar)
        downloadButton = findViewById(R.id.btnDownload)

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
            if (extractedText.isNotEmpty()) {
                saveTextToFile(extractedText)
            } else {
                Toast.makeText(this, "No text to download", Toast.LENGTH_SHORT).show()
            }
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

        runOnUiThread {
            progressBar.visibility = ProgressBar.VISIBLE
            downloadButton.visibility = Button.GONE
            outputText.text = ""
        }

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    progressBar.visibility = ProgressBar.GONE
                    outputText.text = "Error: ${e.message}"
                    Log.e("SOAAnalyzer", "Upload failed", e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val result = response.body?.string() ?: "No response"
                extractedText = result

                runOnUiThread {
                    progressBar.visibility = ProgressBar.GONE
                    outputText.text = result
                    downloadButton.visibility = Button.VISIBLE
                    Log.d("SOAAnalyzer", "Text extracted successfully")
                }
            }
        })
    }

    private fun saveTextToFile(text: String) {
        try {
            val fileName = "ExtractedSOA.txt"
            val path = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            val file = File(path, fileName)
            file.writeText(text)
            Toast.makeText(this, "Saved to ${file.absolutePath}", Toast.LENGTH_LONG).show()
            Log.d("SOAAnalyzer", "File saved: ${file.absolutePath}")
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to save file: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e("SOAAnalyzer", "File save failed", e)
        }
    }
}
