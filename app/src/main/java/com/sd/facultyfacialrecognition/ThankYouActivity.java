package com.sd.facultyfacialrecognition;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class ThankYouActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_thank_you);

        String profName = getIntent().getStringExtra("profName");
        if (profName == null || profName.isEmpty()) profName = "Professor";

        String status = getIntent().getStringExtra("status");
        if (status == null) status = "";

        TextView msg = findViewById(R.id.text_goodbye);
        msg.setText("Goodbye, " + profName + "!\n" + status);

        new Handler().postDelayed(() -> {
            Intent intent = new Intent(ThankYouActivity.this, HomeActivity.class);
            startActivity(intent);
            finish();
        }, 3000);
    }
}
