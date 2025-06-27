package com.mitbapl.soa;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.Nullable;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final int PICK_PDF_FILE = 1;
    private Uri selectedPdfUri;
    private TextView output;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PDFBoxResourceLoader.init(getApplicationContext());
        setContentView(R.layout.activity_main);

        Button uploadBtn = findViewById(R.id.uploadBtn);
        Button analyzeBtn = findViewById(R.id.analyzeBtn);
        output = findViewById(R.id.output);

        uploadBtn.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("application/pdf");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, PICK_PDF_FILE);
        });

        analyzeBtn.setOnClickListener(v -> {
            if (selectedPdfUri != null) {
                extractTextFromPdf(selectedPdfUri);
            } else {
                output.setText("Please upload a PDF first.");
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_PDF_FILE && resultCode == RESULT_OK && data != null) {
            selectedPdfUri = data.getData();
            output.setText("PDF selected.");
        }
    }

    private void extractTextFromPdf(Uri uri) {
        try {
            InputStream inputStream = getContentResolver().openInputStream(uri);
            PDDocument document = PDDocument.load(inputStream);
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            document.close();

            Pattern pattern = Pattern.compile("(\\d{2}-\\d{2}-\\d{4})\\s+(\\S+)\\s+(.+?)\\s+(\\d+\\.\\d{2})\\s+(Dr|Cr)\\s+(\\d+\\.\\d{2})\\s+Cr");
            Matcher matcher = pattern.matcher(text);
            StringBuilder result = new StringBuilder();

            while (matcher.find()) {
                result.append("Date: ").append(matcher.group(1)).append("\n");
                result.append("TxnID: ").append(matcher.group(2)).append("\n");
                result.append("Remarks: ").append(matcher.group(3)).append("\n");
                result.append("Amount: ").append(matcher.group(4)).append(" ").append(matcher.group(5)).append("\n");
                result.append("Balance: ").append(matcher.group(6)).append("\n\n");
            }

            output.setText(result.length() > 0 ? result.toString() : "No matches found.");

        } catch (Exception e) {
            output.setText("Error: " + e.getMessage());
        }
    }
}
