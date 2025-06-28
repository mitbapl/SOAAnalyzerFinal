package com.mitbapl.soa;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.Nullable;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.InputStream;

public class MainActivity extends Activity {

    private static final int PICK_PDF_FILE = 1;
    private Uri selectedPdfUri;
    private TextView output;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        output = findViewById(R.id.output);
        Button uploadBtn = findViewById(R.id.uploadBtn);
        Button analyzeBtn = findViewById(R.id.analyzeBtn);

        uploadBtn.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("application/pdf");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(intent, "Select SOA PDF"), PICK_PDF_FILE);
        });

        analyzeBtn.setOnClickListener(v -> {
            if (selectedPdfUri == null) {
                output.setText("Please upload a PDF first.");
                return;
            }

            try {
                InputStream inputStream = getContentResolver().openInputStream(selectedPdfUri);
                PDDocument document = PDDocument.load(inputStream);
                PDFTextStripper stripper = new PDFTextStripper();
                String text = stripper.getText(document);
                document.close();

                // ✅ Debug step: show full PDF text
                output.setText(text);
            } catch (Exception e) {
                output.setText("Error reading PDF: " + e.getMessage());
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PICK_PDF_FILE && resultCode == RESULT_OK && data != null) {
            selectedPdfUri = data.getData();
            output.setText("PDF uploaded successfully. Now press Analyze.");
        }
    }
}
