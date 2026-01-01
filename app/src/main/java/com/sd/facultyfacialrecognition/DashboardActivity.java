package com.sd.facultyfacialrecognition;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class DashboardActivity extends AppCompatActivity {

    private TextView welcomeText;
    private TextView statusText;
    private Button scanAgainButton;
    private String profName;
    private String authorizedUnlocker;
    private SharedPreferences prefs;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        welcomeText = findViewById(R.id.text_welcome);
        statusText = findViewById(R.id.text_status);
        scanAgainButton = findViewById(R.id.btn_scan_again);

        Intent intent = getIntent();
        String profNameFromIntent = intent.getStringExtra("profName");
        String statusFromIntent = intent.getStringExtra("status");

        SharedPreferences prefs = getSharedPreferences("faculty_state", MODE_PRIVATE);

        String profName = profNameFromIntent != null
                ? profNameFromIntent
                : prefs.getString("authorizedUnlocker", "Professor");

        String status = statusFromIntent != null
                ? statusFromIntent
                : "Status: In Class\n\nScan Your Face Again to Choose Actions.\n( Resume Class / Take A Break / End Class )";


        welcomeText.setText(profName != null ? "Welcome, " + profName + "!" : "Welcome, Professor!");
        statusText.setText(status);

        scanAgainButton.setOnClickListener(v -> {
            if (prefs.getString("authorizedUnlocker", null) == null) return;

            Intent newIntent = new Intent(DashboardActivity.this, MainActivity.class);
            newIntent.putExtra("mode", "rescan");
            newIntent.putExtra("profName", prefs.getString("authorizedUnlocker", null));
            startActivity(newIntent);
            finish();
        });
    }



    @SuppressWarnings("MissingSuperCall")
    @Override
    public void onBackPressed() {
    }

}
