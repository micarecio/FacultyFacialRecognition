package com.sd.facultyfacialrecognition;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class PinLockActivity extends AppCompatActivity {
    private EditText editTextPin;
    private Button buttonSubmit, buttonSetPin;
    private static final String DEFAULT_PIN = "1234";
    private String currentPin = DEFAULT_PIN;
    private final String DATABASE_URL = "https://facultyfacialrecognition-default-rtdb.asia-southeast1.firebasedatabase.app/";
    private DatabaseReference pinRef;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pin_lock);

        editTextPin = findViewById(R.id.editTextPin);
        buttonSubmit = findViewById(R.id.buttonSubmit);
        buttonSetPin = findViewById(R.id.buttonSetPin);

        FirebaseDatabase database = FirebaseDatabase.getInstance(DATABASE_URL);
        pinRef = database.getReference("system_settings").child("admin_pin");

        loadPinFromDatabase();

        buttonSubmit.setOnClickListener(v -> handlePinSubmit());
        buttonSetPin.setOnClickListener(v -> openSetPinDialog());

        SharedPreferences prefs = getSharedPreferences("app_state", MODE_PRIVATE);
        String lastActivity = prefs.getString("last_activity", null);

    }

    private void loadPinFromDatabase() {
        pinRef.get().addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                if (task.getResult().exists()) {
                    currentPin = task.getResult().getValue(String.class);
                    Log.d("PinLockDebug", "Loaded PIN: " + currentPin);
                } else {
                    currentPin = DEFAULT_PIN;
                    savePin(DEFAULT_PIN);
                }
            } else {
                Log.e("PinLockDebug", "Failed to load PIN", task.getException());
            }
        });
    }

    private void handlePinSubmit() {
        String enteredPin = editTextPin.getText().toString().trim();

        if (enteredPin.isEmpty()) {
            Toast.makeText(this, "Please enter a PIN", Toast.LENGTH_SHORT).show();
            return;
        }

        if (enteredPin.equals(currentPin)) {
            Toast.makeText(this, "Access granted!", Toast.LENGTH_SHORT).show();
            logAccess();

            startActivity(new Intent(PinLockActivity.this, AdminActivity.class));
            finish();
        } else {
            Toast.makeText(this, "Incorrect PIN. Try again.", Toast.LENGTH_SHORT).show();
        }
    }

    private void openSetPinDialog() {
        EditText inputCurrent = new EditText(this);
        inputCurrent.setHint("Enter current PIN");
        inputCurrent.setInputType(android.text.InputType.TYPE_CLASS_NUMBER |
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);

        new android.app.AlertDialog.Builder(this)
                .setTitle("Verify Current PIN")
                .setView(inputCurrent)
                .setPositiveButton("Next", (dialog, which) -> {
                    String enteredCurrent = inputCurrent.getText().toString().trim();

                    if (enteredCurrent.isEmpty()) {
                        Toast.makeText(this, "Please enter current PIN", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    if (!enteredCurrent.equals(currentPin)) {
                        Toast.makeText(this, "Incorrect current PIN", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    openNewPinDialog();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openNewPinDialog() {
        EditText inputNew = new EditText(this);
        inputNew.setHint("Enter new PIN");
        inputNew.setInputType(android.text.InputType.TYPE_CLASS_NUMBER |
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);

        new android.app.AlertDialog.Builder(this)
                .setTitle("Set New PIN")
                .setView(inputNew)
                .setPositiveButton("Save", (dialog, which) -> {
                    String newPin = inputNew.getText().toString().trim();
                    if (newPin.isEmpty()) {
                        Toast.makeText(this, "PIN cannot be empty", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    savePin(newPin);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void savePin(String newPin) {
        Map<String, Object> data = new HashMap<>();
        data.put("pin", newPin);

        pinRef.setValue(newPin)
                .addOnSuccessListener(aVoid -> {
                    currentPin = newPin;
                    Toast.makeText(this, "PIN updated!", Toast.LENGTH_SHORT).show();
                    Log.d("PinLockDebug", "PIN saved: " + newPin);
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "Failed to save PIN: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    Log.e("PinLockDebug", "Error saving PIN", e);
                });
    }

    private void logAccess() {
        try {
            FirebaseDatabase database = FirebaseDatabase.getInstance(DATABASE_URL);
            String timestamp = new SimpleDateFormat("yyyy-MM-dd | EEEE | HH:mm:ss", Locale.getDefault())
                    .format(new Date());

            Map<String, Object> data = new HashMap<>();
            data.put("pin", currentPin);
            data.put("timestamp", timestamp);

            DatabaseReference logRef = database.getReference("access_to_database_logs").child("Latest");
            logRef.setValue(data)
                    .addOnSuccessListener(aVoid -> Log.d("PinLockDebug", "Latest access updated"))
                    .addOnFailureListener(e -> Log.e("PinLockDebug", "Failed to update latest access", e));

        } catch (Exception e) {
            Log.e("PinLockDebug", "Database initialization error", e);
        }
    }

    @SuppressWarnings("MissingSuperCall")
    @Override
    public void onBackPressed() {
        Intent intent = new Intent(PinLockActivity.this, HomeActivity.class);
        startActivity(intent);
        finish();
    }

}
