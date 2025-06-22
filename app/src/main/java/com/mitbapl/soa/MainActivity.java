package com.mitbapl.soa;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.view.View;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final TextView output = findViewById(R.id.output);
        final Button extract = findViewById(R.id.extractBtn);

        extract.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                output.setText("SOA Analyzer is working!");
            }
        });
    }
}
