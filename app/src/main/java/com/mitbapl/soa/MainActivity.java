package com.mitbapl.soa;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.InputStream;
import java.util.Scanner;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.io.IOException;

public class MainActivity extends AppCompatActivity {

    private static final int PICK_PDF_FILE = 2;
    private TextView resultText;
    private Button uploadButton, analyzeButton;
    private Uri selectedPdfUri;

    private final String serverUrl = "https://tika-server.onrender.com/parse";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        uploadButton = findViewById(R.id.uploadBtn);
        analyzeButton = findViewById(R.id.analyzeBtn);
        resultText = findViewById(R.id.resultText);

        uploadButton.setOnClickListener(view -> openPdfPicker());

        analyzeButton.setOnClickListener(view -> {
            if (selectedPdfUri != null) {
                uploadAndAnalyze(selectedPdfUri);
            } else {
                resultText.setText("Please select a PDF first.");
            }
        });
    }

    private void openPdfPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("application/pdf");
        startActivityForResult(intent, PICK_PDF_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_PDF_FILE && resultCode == Activity.RESULT_OK && data != null) {
            selectedPdfUri = data.getData();
            resultText.setText("PDF selected: " + selectedPdfUri.getLastPathSegment());
        }
    }

    private void uploadAndAnalyze(Uri pdfUri) {
        try {
            InputStream inputStream = getContentResolver().openInputStream(pdfUri);
            byte[] pdfBytes = new byte[inputStream.available()];
            inputStream.read(pdfBytes);
            inputStream.close();

            OkHttpClient client = new OkHttpClient();

            RequestBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", "soa.pdf",
                            RequestBody.create(MediaType.parse("application/pdf"), pdfBytes))
                    .build();

            Request request = new Request.Builder()
                    .url(serverUrl)
                    .post(body)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    runOnUiThread(() -> resultText.setText("Failed to connect to server."));
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String responseData = response.body().string();
                    String extractedInfo = extractWithRegex(responseData);

                    runOnUiThread(() -> resultText.setText(extractedInfo));
                }
            });

        } catch (Exception e) {
            resultText.setText("Error: " + e.getMessage());
        }
    }

    private String extractWithRegex(String text) {
        // Example regex: extract account number like ACC123456789
        String pattern = "ACC\\d{9}";
        java.util.regex.Pattern r = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = r.matcher(text);

        if (m.find()) {
            return "Match found: " + m.group(0);
        } else {
            return "No match found.";
        }
    }
}
