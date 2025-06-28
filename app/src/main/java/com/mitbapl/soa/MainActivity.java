package com.mitbapl.soa;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.*;
import android.view.View;

import java.io.*;
import java.util.regex.*;
import okhttp3.*;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int PICK_PDF = 100;
    private Uri selectedPdfUri;
    private TextView output;

    // ⬇️ Replace with your Render server URL
    private static final String SERVER_URL = "https://tika-server.onrender.com/extract";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button uploadBtn = findViewById(R.id.uploadBtn);
        Button analyzeBtn = findViewById(R.id.analyzeBtn);
        output = findViewById(R.id.output);

        uploadBtn.setOnClickListener(v -> pickPdf());
        analyzeBtn.setOnClickListener(v -> {
            if (selectedPdfUri != null) {
                analyzePdf(selectedPdfUri);
            } else {
                output.setText("Please upload a PDF first.");
            }
        });
    }

    private void pickPdf() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("application/pdf");
        startActivityForResult(intent, PICK_PDF);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_PDF && resultCode == RESULT_OK && data != null) {
            selectedPdfUri = data.getData();
            output.setText("PDF selected: " + selectedPdfUri.getLastPathSegment());
        }
    }

    private void analyzePdf(Uri pdfUri) {
        try {
            InputStream inputStream = getContentResolver().openInputStream(pdfUri);
            byte[] pdfBytes = readBytes(inputStream);

            // Build multipart request
            OkHttpClient client = new OkHttpClient();
            RequestBody requestBody = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", "soa.pdf",
                    RequestBody.create(pdfBytes, MediaType.parse("application/pdf")))
                .build();

            Request request = new Request.Builder().url(SERVER_URL).post(requestBody).build();

            // Run in background thread
            new Thread(() -> {
                try {
                    Response response = client.newCall(request).execute();
                    if (!response.isSuccessful()) {
                        runOnUiThread(() -> output.setText("Server error: " + response.code()));
                        return;
                    }

                    String body = response.body().string();
                    JSONObject json = new JSONObject(body);
                    String text = json.getString("text");

                    String parsed = parseSOA(text);
                    runOnUiThread(() -> output.setText(parsed));

                } catch (Exception e) {
                    runOnUiThread(() -> output.setText("Error: " + e.getMessage()));
                }
            }).start();

        } catch (Exception e) {
            output.setText("Read error: " + e.getMessage());
        }
    }

    private byte[] readBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[4096];
        while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return buffer.toByteArray();
    }

    private String parseSOA(String input) {
        // SOA regex pattern
        Pattern pattern = Pattern.compile("(\\d{2}-\\d{2}-\\d{4})\\s+(\\S+)\\s+(.+?)\\s+(\\d+\\.\\d{2})\\s+(Dr|Cr)\\s+(\\d+\\.\\d{2})\\s+Cr");
        Matcher matcher = pattern.matcher(input);

        StringBuilder result = new StringBuilder("Date,TxnID,Remarks,Amount,Type,Balance\n");

        boolean found = false;
        while (matcher.find()) {
            found = true;
            result.append(matcher.group(1)).append(",")
                  .append(matcher.group(2)).append(",")
                  .append(matcher.group(3)).append(",")
                  .append(matcher.group(4)).append(",")
                  .append(matcher.group(5)).append(",")
                  .append(matcher.group(6)).append("\n");
        }

        if (!found) {
            return "No match found.\n\n" + input.substring(0, Math.min(input.length(), 1000));
        }
        return result.toString();
    }
}
