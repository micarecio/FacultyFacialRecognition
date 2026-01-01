package com.sd.facultyfacialrecognition;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

public class ActionActivity extends AppCompatActivity {

    public static final String EXTRA_ACTION = "extra_action";
    public static final String ACTION_TAKE_BREAK = "take_break";
    public static final String ACTION_END_CLASS = "end_class";
    public static final String ACTION_RESUME_CLASS = "resume_class";

    private Button btnTakeBreak;
    private Button btnEndClass;
    private Button btnResumeClass;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_action);

        SharedPreferences prefs = getSharedPreferences("DoorPrefs", MODE_PRIVATE);
        String lastRecognizedFace = prefs.getString("lastRecognizedFace", null);

        btnTakeBreak = findViewById(R.id.btn_break);
        btnEndClass = findViewById(R.id.btn_end_class);
        btnResumeClass = findViewById(R.id.btn_resume_class);

        TextView textActionPrompt = findViewById(R.id.text_action_prompt);

        if (lastRecognizedFace != null) {
            textActionPrompt.setText("Hello, " + lastRecognizedFace + "!");
        } else {
            textActionPrompt.setText("Hello, Professor!");
        }

        btnTakeBreak.setOnClickListener(v -> {
            Intent result = new Intent();
            result.putExtra(EXTRA_ACTION, ACTION_TAKE_BREAK);
            setResult(Activity.RESULT_OK, result);
            finish();
        });

        btnEndClass.setOnClickListener(v -> {
            Intent result = new Intent();
            result.putExtra(EXTRA_ACTION, ACTION_END_CLASS);
            setResult(Activity.RESULT_OK, result);
            finish();
        });

        btnResumeClass.setOnClickListener(v -> {
            Intent result = new Intent();
            result.putExtra(EXTRA_ACTION, ACTION_RESUME_CLASS);
            setResult(Activity.RESULT_OK, result);
            finish();
        });
    }

    @SuppressWarnings("MissingSuperCall")
    @Override
    public void onBackPressed() {
    }
}
