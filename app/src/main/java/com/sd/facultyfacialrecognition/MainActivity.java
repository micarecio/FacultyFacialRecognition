package com.sd.facultyfacialrecognition;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.util.Size;
import android.content.Intent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.mlkit.vision.face.FaceLandmark;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.gson.reflect.TypeToken;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Calendar;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 1001;
    private static final int REQUEST_BLUETOOTH_PERMISSION = 2001;
    private static final int REQ_ACTION = 9001;
    private PreviewView previewView;
    private FaceOverlayView overlayView;
    private TextView statusTextView;
    private TextView countdownTextView;
    private Button confirmYesButton;
    private Button confirmNoButton;
    private Button btnBreakDone;
    private FaceNet faceNet;
    private ImageAligner imageAligner;
    private ExecutorService cameraExecutor;
    private final Map<String, float[]> KNOWN_FACE_EMBEDDINGS = new HashMap<>();
    private Map<String, List<float[]>> facultyEmbeddings = new HashMap<>();
    private float dynamicThreshold = 0.66f;
    private static final int STABILITY_FRAMES_NEEDED = 5;
    private static final long UNLOCK_COOLDOWN_MILLIS = 1000;
    private static final long CONFIRMATION_TIMEOUT_MILLIS = 1000;
    private static final int VISUAL_COUNTDOWN_SECONDS = 1;
    private String mode = "";
    private boolean isRescanModeActive = false;

    private String currentBestMatch = "Scanning...";
    private String stableMatchName = "Scanning...";
    private String authorizedUnlocker = null;
    private String authorizedLocker = null;
    private boolean isDoorLocked = true;
    private boolean isAwaitingUnlockConfirmation = false;
    private boolean isAwaitingLockConfirmation = false;
    private int stableMatchCount = 0;
    private String lastMatchName = "";
    private boolean unlockProcessed = false;
    private String lastLatestStatus = "";
    private boolean isAwaitingLockerRecognition = false;
    private boolean isReturningFromBreak = false;
    private long lastLockTimestamp = 0;
    private Handler confirmationHandler;
    private Runnable confirmationRunnable;
    private Handler countdownDisplayHandler;
    private Runnable countdownDisplayRunnable;
    private int confirmationTimeRemaining = VISUAL_COUNTDOWN_SECONDS;
    private FirebaseFirestore db;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothService bluetoothService;
    private BluetoothDevice bluetoothDevice;
    private String currentLab = "CpeLab";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mode = getIntent().getStringExtra("mode");
        String receivedUnlocker = getIntent().getStringExtra("authorizedUnlocker");

        if ("rescan".equals(mode) && receivedUnlocker != null) {
            authorizedUnlocker = receivedUnlocker;
            isRescanModeActive = true;

            Intent intent = new Intent(MainActivity.this, ActionActivity.class);
            intent.putExtra("currentFaculty", authorizedUnlocker);
            startActivity(intent);
            finish();
            return;
        }

        previewView = findViewById(R.id.previewView);
        overlayView = findViewById(R.id.faceOverlayView);
        statusTextView = findViewById(R.id.text_status_label);
        countdownTextView = findViewById(R.id.text_countdown_status);

        confirmYesButton = findViewById(R.id.confirm_yes_button);
        confirmNoButton = findViewById(R.id.confirm_no_button);
        btnBreakDone = findViewById(R.id.btn_break_done);

        confirmYesButton.setVisibility(View.GONE);
        confirmNoButton.setVisibility(View.GONE);
        btnBreakDone.setVisibility(View.GONE);

        confirmationHandler = new Handler();
        countdownDisplayHandler = new Handler();
        cameraExecutor = Executors.newSingleThreadExecutor();
        imageAligner = new ImageAligner();

        initializeSystem();
        startCamera();
        testLoadEmbeddings();

        db = FirebaseFirestore.getInstance();

        if (!hasBluetoothPermission()) {
            requestBluetoothPermission();
            return;
        }

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        String deviceName = "CpE Laboratory Lock";
        String deviceAddress = "D4:E9:F4:E2:F8:02";

        bluetoothDevice = findPairedDevice(deviceName, deviceAddress);
        if (bluetoothDevice == null) {
            Log.e(TAG, "Bluetooth device not found!");
            return;
        }

        bluetoothService = BluetoothServiceSingleton.getInstance(this, bluetoothDevice);
    }

    private boolean hasBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_SCAN
                    },
                    REQUEST_BLUETOOTH_PERMISSION
            );
        }
    }


    private BluetoothDevice findPairedDevice(String name, String address) {
        if (bluetoothAdapter == null) return null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "BLUETOOTH_CONNECT permission not granted!");
                return null;
            }
        }

        for (BluetoothDevice device : bluetoothAdapter.getBondedDevices()) {
            if ((name != null && device.getName().equals(name)) ||
                    (address != null && device.getAddress().equals(address))) {
                return device;
            }
        }
        return null;
    }

    public static class TimeSlot {
        public List<String> startTimes;
        public List<String> endTimes;

        public TimeSlot(List<String> startTimes, List<String> endTimes) {
            this.startTimes = startTimes;
            this.endTimes = endTimes;
        }

        public String start;
        public String end;
        public TimeSlot(String start, String end) {
            this.start = start;
            this.end = end;
        }
    }

    private String getTodayKey() {
        Calendar calendar = Calendar.getInstance();
        int dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK);
        String[] days = {"sun", "mon", "tue", "wed", "thu", "fri", "sat"};
        return days[dayOfWeek - 1];
    }

    private String getCurrentTime() {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
        return sdf.format(Calendar.getInstance().getTime());
    }

    private boolean isWithinTimeRange(String currentTime, String startTime, String endTime) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
            Date current = sdf.parse(currentTime);
            Date start = sdf.parse(startTime);
            Date end = sdf.parse(endTime);
            return current != null && start != null && end != null &&
                    current.compareTo(start) >= 0 && current.compareTo(end) <= 0;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private boolean isAllowedNow(String start, String end) {
        String currentTime = getCurrentTime();
        if (start == null || end == null || start.isEmpty() || end.isEmpty()) {
            return false;
        }
        return isWithinTimeRange(currentTime, start, end);
    }

    private void initializeSystem() {
        try {
            faceNet = new FaceNet(this, "facenet.tflite");

            boolean embeddingsLoaded = loadEmbeddingsFromStorage();
            if (!embeddingsLoaded) {
                embeddingsLoaded = loadEmbeddingsFromAssets();
            }

            Log.d(TAG, "FaceNet model and embeddings loaded successfully. Embeddings loaded: " + embeddingsLoaded);
        } catch (Exception e) {
            Log.e(TAG, "Error initializing FaceNet or embeddings", e);
        }

        startCamera();
    }



    private void startConfirmationTimer(boolean isLock) {
        stopConfirmationTimer();

        confirmationRunnable = () -> {
            if (isLock) {
                handleLockConfirmation();
                updateUiOnThread("Lock Confirmed", "Lock executed automatically after countdown.");
            } else {
                handleUnlockConfirmation(currentLab);
                updateUiOnThread("Unlock Confirmed", "Unlock executed automatically after countdown.");
            }
        };

        confirmationHandler.postDelayed(confirmationRunnable, CONFIRMATION_TIMEOUT_MILLIS);
    }


    private void stopConfirmationTimer() {
        if (confirmationRunnable != null) {
            confirmationHandler.removeCallbacks(confirmationRunnable);
            confirmationRunnable = null;
        }
    }

    private void startVisualCountdown(String action, String matchName) {
        stopVisualCountdown();

        confirmationTimeRemaining = VISUAL_COUNTDOWN_SECONDS;

        countdownDisplayRunnable = new Runnable() {
            @Override
            public void run() {
                String currentAction = isAwaitingLockConfirmation ? "Lock" : "Unlock";

                if (confirmationTimeRemaining > 0) {
                    String name = isAwaitingLockConfirmation ? authorizedLocker : stableMatchName;

                    updateUiOnThread("Confirm " + currentAction + " Identity",
                            "Is this you: " + name + "?\nAction auto-cancels in " + (CONFIRMATION_TIMEOUT_MILLIS / 1000) + "s (Visual countdown: " + confirmationTimeRemaining + "s).");

                    confirmationTimeRemaining--;
                    countdownDisplayHandler.postDelayed(this, 1000);
                } else {
                    String name = isAwaitingLockConfirmation ? authorizedLocker : stableMatchName;
                    String finalStatus = isAwaitingLockConfirmation ? "Confirm Lock Identity" : "Confirm Unlock Identity";
                    String finalCountdown = "Is this you: " + name + "? (Awaiting confirmation)";
                    updateUiOnThread(finalStatus, finalCountdown);
                    stopVisualCountdown();
                }
            }
        };

        countdownDisplayHandler.post(countdownDisplayRunnable);
    }

    private void stopVisualCountdown() {
        if (countdownDisplayRunnable != null) {
            countdownDisplayHandler.removeCallbacks(countdownDisplayRunnable);
            countdownDisplayRunnable = null;
        }
    }

    private String getCurrentTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd | EEEE | HH:mm:ss", Locale.getDefault());
        return sdf.format(new Date());
    }

    private String lastRecognizedFace = "Scanning...";

    private void updateDoorStatus(String facultyName, String facultyStatus, String doorStatus) {
        if (facultyName == null || facultyName.equals("Scanning...") || facultyName.equals("Unknown")) {
            Log.w("DoorDebug", "Skipping updateDoorStatus: invalid facultyName");
            return;
        }

        String timestamp = new SimpleDateFormat("yyyy-MM-dd | EEEE | HH:mm:ss", Locale.getDefault()).format(new Date());

        Map<String, Object> data = new HashMap<>();
        data.put("facultyName", facultyName);
        data.put("facultyStatus", facultyStatus);
        data.put("doorStatus", doorStatus);
        data.put("timestamp", timestamp);
        data.put("lab", currentLab);

        db.collection("DoorLogs")
                .add(data)
                .addOnSuccessListener(docRef -> Log.d("DoorLockDebug",
                        "Door event logged: " + facultyName + " | " + facultyStatus + " | " + doorStatus + " | " + timestamp))
                .addOnFailureListener(e -> Log.e("DoorLockDebug", "Error logging door event", e));

        String newStatusKey = facultyName + "|" + facultyStatus + "|" + doorStatus;
        if (!newStatusKey.equals(lastLatestStatus)) {
            lastLatestStatus = newStatusKey;
            db.collection(currentLab).document("Latest")
                    .set(data)
                    .addOnSuccessListener(aVoid -> Log.d("DoorLockDebug",
                            "Updated " + currentLab + " Latest: " + facultyName + " | " + facultyStatus + " | " + doorStatus))
                    .addOnFailureListener(e -> Log.e("DoorLockDebug",
                            "Error updating " + currentLab + " Latest", e));
        } else {
            Log.d("DoorLockDebug", "Duplicate Latest update skipped: " + newStatusKey);
        }

        try {
            FirebaseDatabase database = FirebaseDatabase.getInstance(
                    "https://facultyfacialrecognition-default-rtdb.asia-southeast1.firebasedatabase.app/"
            );
            DatabaseReference dbRef = database.getReference(currentLab).child("Latest");
            dbRef.setValue(data)
                    .addOnSuccessListener(aVoid -> Log.d("DoorDebug", "Realtime DB successfully updated"))
                    .addOnFailureListener(e -> Log.e("DoorDebug", "Realtime DB update FAILED", e));
        } catch (Exception e) {
            Log.e("DoorDebug", "Database initialization error", e);
        }
    }

    private void debugState(String location) {
        Log.d("DoorDebug", "---- DEBUG STATE: " + location + " ----");
        Log.d("DoorDebug", "authorizedUnlocker: " + authorizedUnlocker);
        Log.d("DoorDebug", "authorizedLocker: " + authorizedLocker);
        Log.d("DoorDebug", "currentBestMatch: " + currentBestMatch);
        Log.d("DoorDebug", "stableMatchName: " + stableMatchName);
        Log.d("DoorDebug", "lastRecognizedFace: " + lastRecognizedFace);
        Log.d("DoorDebug", "stableMatchCount: " + stableMatchCount);
        Log.d("DoorDebug", "lastLatestStatus: " + lastLatestStatus);
        Log.d("DoorDebug", "isDoorLocked: " + isDoorLocked);
        Log.d("DoorDebug", "isAwaitingUnlockConfirmation: " + isAwaitingUnlockConfirmation);
        Log.d("DoorDebug", "isAwaitingLockConfirmation: " + isAwaitingLockConfirmation);
        Log.d("DoorDebug", "isReturningFromBreak: " + isReturningFromBreak);
        Log.d("DoorDebug", "-------------------------------");
    }
    private void handleUnlockConfirmation(String currentLab) {

        if ("Scanning...".equals(stableMatchName)) return;

        FirebaseFirestore db = FirebaseFirestore.getInstance();
        String facultyName = stableMatchName;

        db.collection("FacultySchedules")
                .whereEqualTo("name", facultyName)
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    if (!queryDocumentSnapshots.isEmpty()) {
                        DocumentSnapshot doc = queryDocumentSnapshots.getDocuments().get(0);

                        Boolean enabled = doc.getBoolean("enabled");
                        if (enabled == null || !enabled) enabled = false;

                        if (!enabled) {
                            Toast.makeText(this, "Access Denied! Account Disabled.", Toast.LENGTH_SHORT).show();
                            Log.d("DoorDebug", "Access denied for " + facultyName + ", account disabled.");
                            return;
                        }

                        Map<String, Object> schedule = (Map<String, Object>) doc.get("schedule");
                        String todayKey = getTodayKey();

                        if (schedule == null || !schedule.containsKey(currentLab)) {
                            Toast.makeText(this, "Access Denied! No access to " + currentLab, Toast.LENGTH_SHORT).show();
                            Log.d("DoorDebug", "Access denied: " + facultyName + " has no schedule for " + currentLab);
                            return;
                        }

                        Map<String, Object> labSchedule = (Map<String, Object>) schedule.get(currentLab);

                        if (!labSchedule.containsKey(todayKey)) {
                            Toast.makeText(this, "Access Denied! No schedule today.", Toast.LENGTH_SHORT).show();
                            Log.d("DoorDebug", "Access denied for " + facultyName + ", no schedule today.");
                            return;
                        }

                        Map<String, Object> todaySchedule = (Map<String, Object>) labSchedule.get(todayKey);

                        List<String> startTimes = (List<String>) todaySchedule.get("start");
                        List<String> endTimes = (List<String>) todaySchedule.get("end");

                        if (startTimes == null || endTimes == null || startTimes.isEmpty() || endTimes.isEmpty()) {
                            Toast.makeText(this, "Access Denied! Schedule is empty.", Toast.LENGTH_SHORT).show();
                            Log.d("DoorDebug", "Access denied for " + facultyName + ", schedule empty.");
                            return;
                        }

                        boolean allowedNow = false;
                        for (int i = 0; i < startTimes.size(); i++) {
                            String start = startTimes.get(i);
                            String end = endTimes.get(i);

                            if (isAllowedNow(start, end)) {
                                allowedNow = true;
                                break;
                            }
                        }

                        if (!allowedNow) {
                            Toast.makeText(this, "Access Denied! Not your scheduled time.", Toast.LENGTH_SHORT).show();
                            Log.d("DoorDebug", "Access denied for " + facultyName + " at " + getCurrentTime());
                            return;
                        }

                        SharedPreferences prefs = getSharedPreferences("DoorPrefs", MODE_PRIVATE);
                        String savedFirstUnlocker = prefs.getString("lastRecognizedFace", null);

                        if (savedFirstUnlocker == null) {
                            lastRecognizedFace = stableMatchName;
                            prefs.edit().putString("lastRecognizedFace", lastRecognizedFace).apply();
                            Log.d("DoorDebug", "First scan — lastRecognizedFace set to: " + lastRecognizedFace);

                            isDoorLocked = false;
                            isAwaitingUnlockConfirmation = false;
                            unlockProcessed = true;

                            Log.d("DoorDebug", "Door unlocked by first scan: " + lastRecognizedFace);
                            sendBluetoothStatus("UNLOCKED");
                            updateDoorStatus(lastRecognizedFace, "In Class", "UNLOCKED");

                            if (cameraExecutor != null) cameraExecutor.shutdown();

                            Intent intent = new Intent(this, DashboardActivity.class);
                            intent.putExtra("profName", lastRecognizedFace);
                            startActivity(intent);
                            debugState("End of handleUnlockConfirmation - door unlocked");
                            return;
                        }

                        lastRecognizedFace = savedFirstUnlocker;
                        authorizedUnlocker = stableMatchName;
                        Log.d("DoorDebug", "Rescan — authorizedUnlocker updated to: " + authorizedUnlocker);
                        debugState("Rescan - accessing ActionActivity");

                        if (!lastRecognizedFace.equals(authorizedUnlocker)) {
                            Toast.makeText(this, "Only " + lastRecognizedFace + " can take actions.", Toast.LENGTH_SHORT).show();
                            Intent intent = new Intent(this, DashboardActivity.class);
                            intent.putExtra("profName", lastRecognizedFace);
                            intent.putExtra("status", "Access denied. Please rescan your face.");
                            startActivity(intent);
                            return;
                        }

                        Intent intent = new Intent(this, ActionActivity.class);
                        intent.putExtra("currentFaculty", lastRecognizedFace);
                        startActivityForResult(intent, REQ_ACTION);

                    } else {
                        Toast.makeText(this, "Access Denied! No record found.", Toast.LENGTH_SHORT).show();
                        Log.d("DoorDebug", "Access denied for " + facultyName + ", no schedule found.");
                    }
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "Error accessing schedule", Toast.LENGTH_SHORT).show();
                    Log.e("DoorDebug", "Firestore error: " + e.getMessage());
                });
    }

    private void handleLockConfirmation() {
        if (lastRecognizedFace.equals("Scanning...")) {
            Log.d("DoorLockDebug", "Lock denied: no unlocker set");
            return;
        }

        isDoorLocked = true;
        isAwaitingLockConfirmation = false;

        authorizedLocker = currentBestMatch != null && !currentBestMatch.equals("Scanning...") ?
                currentBestMatch : authorizedUnlocker;

        authorizedUnlocker = null;
        unlockProcessed = false;

        sendBluetoothStatus("LOCKED");
        Log.d("DoorLockDebug", "Door locked by: " + authorizedLocker);

        updateDoorStatus(lastRecognizedFace, "End Class", "LOCKED");
        updateUiOnThread("System Locked", "Door secured. Cooldown active.");
    }

    private void sendBluetoothStatus(String status) {
        BluetoothService service = BluetoothServiceSingleton.getInstance();
        if (service != null && service.isConnected()) {
            service.sendDoorStatus(status);
            Log.d("DoorLockDebug", "Bluetooth: " + status + " sent");
        }
    }

    private boolean canAccessActionActivity() {
        return lastRecognizedFace.equals(currentBestMatch) && !currentBestMatch.equals("Scanning...");
    }

    public boolean isDoorUnlocker(String currentUser) {
        return authorizedUnlocker != null && authorizedUnlocker.equals(currentUser);
    }

    private void resetStateAfterAction() {
        stableMatchCount = 0;
        stableMatchName = "Scanning...";
        currentBestMatch = "Scanning...";

        isAwaitingUnlockConfirmation = false;
        isAwaitingLockConfirmation = false;
        isAwaitingLockerRecognition = false;
    }

    public void onTakeBreakClicked(View view) {
        if (!lastRecognizedFace.equals(authorizedUnlocker)) {
            Toast.makeText(this, "Only " + lastRecognizedFace + " may take a break.", Toast.LENGTH_SHORT).show();
            return;
        }
        updateDoorStatus(lastRecognizedFace, "On Break", "UNLOCKED");

        Intent intent = new Intent(this, DashboardActivity.class);
        intent.putExtra("profName", lastRecognizedFace);
        intent.putExtra("status", "Status: On Break\n\nScan Your Face Again to Choose Actions.\n( Resume Class / Take A Break / End Class )");
        startActivity(intent);
        finish();
    }

    public void onBreakDoneClicked(View view) {
        if (authorizedUnlocker == null) return;
        isReturningFromBreak = true;
        updateDoorStatus(lastRecognizedFace, "In Class", "UNLOCKED");

        Intent intent = new Intent(this, DashboardActivity.class);
        intent.putExtra("profName", authorizedUnlocker);
        startActivity(intent);
        finish();
    }

    public void onEndClassClicked(View view) {
        if (!lastRecognizedFace.equals(authorizedUnlocker)) {
            Toast.makeText(this, "Only " + lastRecognizedFace + " may end the class.", Toast.LENGTH_SHORT).show();
            return;
        }

        handleLockConfirmation();

        Intent intent = new Intent(this, ThankYouActivity.class);
        intent.putExtra("profName", lastRecognizedFace);
        intent.putExtra("status", "Class ended. Door locked.");
        startActivity(intent);

        SharedPreferences prefs = getSharedPreferences("DoorPrefs", MODE_PRIVATE);
        prefs.edit().remove("lastRecognizedFace").apply();
        lastRecognizedFace = "Scanning...";
        authorizedUnlocker = null;

        finish();
    }


    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startCamera() {
        BluetoothService bt = BluetoothServiceSingleton.getInstance();
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                bindPreviewAndAnalyzer(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera provider error", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    public void checkBluetoothBeforeCamera() {
        BluetoothService bt = BluetoothServiceSingleton.getInstance();
        if (bt == null || !bt.isConnected()) {
            statusTextView.setText("Bluetooth not connected. Please connect to the Door Lock.");
            Log.e("Camera", "Camera blocked: Bluetooth not connected.");
            return;
        }
        startCamera();
    }


    @OptIn(markerClass = ExperimentalGetImage.class)
    private void bindPreviewAndAnalyzer(ProcessCameraProvider cameraProvider) {
        cameraProvider.unbindAll();

        Preview preview = new Preview.Builder().build();
        CameraSelector selector = CameraSelector.DEFAULT_FRONT_CAMERA;
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(0.30f)
                .enableTracking()
                .build();

        FaceDetector detector = FaceDetection.getClient(options);

        imageAnalysis.setAnalyzer(cameraExecutor, image -> {
            try {
                final android.media.Image mediaImage = image.getImage();
                if (mediaImage != null) {
                    InputImage inputImage = InputImage.fromMediaImage(mediaImage, image.getImageInfo().getRotationDegrees());
                    detector.process(inputImage)
                            .addOnSuccessListener(faces -> handleFaces(faces, inputImage))
                            .addOnFailureListener(e -> Log.e(TAG, "Face detection failed", e))
                            .addOnCompleteListener(task -> image.close());
                } else {
                    image.close();
                }
            } catch (Exception e) {
                Log.e(TAG, "Analyzer error", e);
                image.close();
            }
        });

        cameraProvider.bindToLifecycle(this, selector, preview, imageAnalysis);
    }

    private void handleFaces(List<Face> faces, InputImage inputImage) {

        Bitmap fullBmp = InputImageUtils.getBitmapFromInputImage(this, inputImage);
        if (fullBmp == null) {
            updateUiOnThread("System Ready", "Point camera at face.");
            return;
        }

        Face bestFace = null;

        if (faces.isEmpty()) {
            currentBestMatch = "Scanning...";
            updateUiOnThread("System Ready", "Point camera at face.");
            runOnUiThread(() -> overlayView.setFaces(new ArrayList<>()));
            return;
        } else if (faces.size() > 1) {
            float minDistance = Float.MAX_VALUE;
            int screenCenterX = previewView.getWidth() / 2;
            int screenCenterY = previewView.getHeight() / 2;

            for (Face face : faces) {
                Rect boundingBox = face.getBoundingBox();
                float distance = (float) Math.sqrt(
                        Math.pow(boundingBox.centerX() - screenCenterX, 2) +
                                Math.pow(boundingBox.centerY() - screenCenterY, 2)
                );

                if (distance < minDistance) {
                    minDistance = distance;
                    bestFace = face;
                }
            }
        } else {
            bestFace = faces.get(0);
        }

        if (bestFace == null) {
            return;
        }

        if (bluetoothService == null || !bluetoothService.isConnected()) {
            if (cameraExecutor != null) {
                cameraExecutor.shutdownNow();
                cameraExecutor = null;
            }
            runOnUiThread(() -> statusTextView.setText("Door Lock disconnected.\nFace Recognition stopped."));
            Log.w(TAG, "Door Lock disconnected.\nStopping camera and face recognition.");
            return;
        }

        Float leftEyeOpenProb = bestFace.getLeftEyeOpenProbability();
        Float rightEyeOpenProb = bestFace.getRightEyeOpenProbability();
        if ((leftEyeOpenProb != null && leftEyeOpenProb < 0.4) || (rightEyeOpenProb != null && rightEyeOpenProb < 0.4)) {
            updateUiOnThread("Face Unclear", "Please keep your eyes open.");
            runOnUiThread(() -> overlayView.setFaces(new ArrayList<>()));
            return;
        }

        float headY = bestFace.getHeadEulerAngleY();
        float acceptableAngle = 70.0f;
        if (Math.abs(headY) > acceptableAngle) {
            updateUiOnThread("Face Not Centered", "Please look towards the camera.");
            runOnUiThread(() -> overlayView.setFaces(new ArrayList<>()));
            return;
        }

        String currentBestFrameMatch = "Scanning...";
        float bestDist = Float.MAX_VALUE;
        List<FaceOverlayView.FaceGraphic> graphics = new ArrayList<>();

        android.graphics.PointF leftEye = bestFace.getLandmark(FaceLandmark.LEFT_EYE) != null ? bestFace.getLandmark(FaceLandmark.LEFT_EYE).getPosition() : null;
        android.graphics.PointF rightEye = bestFace.getLandmark(FaceLandmark.RIGHT_EYE) != null ? bestFace.getLandmark(FaceLandmark.RIGHT_EYE).getPosition() : null;
        Bitmap faceBmp = imageAligner.alignAndCropFace(fullBmp, bestFace.getBoundingBox(), leftEye, rightEye);

        if (faceBmp != null) {
            float[] emb = faceNet.getEmbedding(faceBmp);
            if (emb != null) {
                normalizeEmbedding(emb);

                for (Map.Entry<String, float[]> entry : KNOWN_FACE_EMBEDDINGS.entrySet()) {
                    float d = FaceNet.distance(emb, entry.getValue());
                    if (d < bestDist) {
                        bestDist = d;
                        currentBestFrameMatch = entry.getKey();
                    }
                }

                if (bestDist > dynamicThreshold) {
                    currentBestFrameMatch = "Unknown";
                }
            }
        }

        String label = currentBestFrameMatch;
        if (!currentBestFrameMatch.equals("Scanning...") && !currentBestFrameMatch.equals("Unknown")) {
            label = currentBestFrameMatch;
        }
        graphics.add(new FaceOverlayView.FaceGraphic(bestFace.getBoundingBox(), label, bestDist));

        this.currentBestMatch = currentBestFrameMatch;

        String finalMessage = "";
        String countdownMessage = "";

        if (isAwaitingLockConfirmation || isAwaitingUnlockConfirmation) {

            String authorizedName = isAwaitingLockConfirmation ? authorizedLocker : stableMatchName;

            if (countdownDisplayRunnable == null) {
                finalMessage = isAwaitingLockConfirmation ? "Confirm Lock Identity" : "Confirm Unlock Identity";
                countdownMessage = "Is this you: " + authorizedName + "? (Awaiting confirmation)";
            } else {
                runOnUiThread(() -> overlayView.setFaces(graphics));
                return;
            }

        } else if (isAwaitingLockerRecognition) {

            updateStabilityState(currentBestFrameMatch);

            if (stableMatchCount >= STABILITY_FRAMES_NEEDED) {
                boolean isLockerIdentityConfirmed = !stableMatchName.equals("Unknown") &&
                        !stableMatchName.equals("Scanning...") &&
                        stableMatchName.equals(currentBestMatch);

                if (isLockerIdentityConfirmed) {
                    isAwaitingLockerRecognition = false;
                    isAwaitingLockConfirmation = true;
                    authorizedLocker = stableMatchName;
                    stableMatchCount = 0;

                    startConfirmationTimer(true);
                    startVisualCountdown("Lock", authorizedLocker);

                } else {
                    isAwaitingLockerRecognition = false;
                    finalMessage = "Recognition Failed";
                    countdownMessage = "Lock initiation failed. Please try again.";
                }
            } else if (stableMatchCount > 0 && !currentBestFrameMatch.equals("Unknown") && !currentBestFrameMatch.equals("Scanning...")) {
                int remainingFrames = STABILITY_FRAMES_NEEDED - stableMatchCount;
                finalMessage = "Recognizing: " + currentBestMatch;
                countdownMessage = String.format("Hold Steady to LOCK! (%d frames remaining)", remainingFrames);
            } else {
                finalMessage = "Awaiting Locker Recognition";
                countdownMessage = "Please hold a faculty face steady for 5 seconds to initiate lock.";
            }

        } else if (isDoorLocked) {

            long timeSinceLock = System.currentTimeMillis() - lastLockTimestamp;
            if (timeSinceLock < UNLOCK_COOLDOWN_MILLIS) {
                long remainingSeconds = (UNLOCK_COOLDOWN_MILLIS - timeSinceLock) / 1000 + 1;
                finalMessage = "System Locked";
                countdownMessage = String.format("Unlock Cooldown Active: %d seconds remaining.", remainingSeconds);

                updateUiOnThread(finalMessage, countdownMessage);
                runOnUiThread(() -> overlayView.setFaces(graphics));
                return;
            }

            updateStabilityState(currentBestFrameMatch);

            if (stableMatchCount >= STABILITY_FRAMES_NEEDED) {

                boolean isUnlockIdentityConfirmed = !stableMatchName.equals("Unknown") &&
                        !stableMatchName.equals("Scanning...") &&
                        stableMatchName.equals(currentBestMatch);

                if (isUnlockIdentityConfirmed) {
                    isAwaitingUnlockConfirmation = true;
                    stableMatchCount = 0;

                    startConfirmationTimer(false);
                    startVisualCountdown("Unlock", authorizedUnlocker);

                } else {
                    finalMessage = "Access Denied";
                    countdownMessage = "Recognition Failed. Please try again.";
                    stableMatchCount = 0;
                }
            } else if (stableMatchCount > 0 && !currentBestFrameMatch.equals("Unknown") && !currentBestFrameMatch.equals("Scanning...")) {
                int remainingFrames = STABILITY_FRAMES_NEEDED - stableMatchCount;
                finalMessage = "Recognizing: " + currentBestMatch;
                countdownMessage = String.format("Hold Steady for unlock! (%d frames remaining)", remainingFrames);
            } else {
                finalMessage = "Awaiting Recognition";
                countdownMessage = "Scanning for faculty...";
            }
        } else {
            finalMessage = "Access Granted: " +  lastRecognizedFace;
            countdownMessage = "Door UNLOCKED. Choose options below.";
        }

        updateUiOnThread(finalMessage, countdownMessage);

        overlayView.setImageSourceInfo(inputImage.getWidth(), inputImage.getHeight(), true);
        runOnUiThread(() -> overlayView.setFaces(graphics));
    }

    private synchronized void updateStabilityState(String newMatch) {
        if (!newMatch.equals(lastMatchName)) {
            stableMatchCount = 0;
            lastMatchName = newMatch;
        }
        if (newMatch.equals(currentBestMatch)) {
            stableMatchCount++;
        } else {
            currentBestMatch = newMatch;
            stableMatchCount = 1;
        }

        if (stableMatchCount >= STABILITY_FRAMES_NEEDED) {
            stableMatchName = currentBestMatch;
        } else if (stableMatchCount == 0 || newMatch.equals("Scanning...")) {
            stableMatchName = "Scanning...";
        }
    }

    private void updateUiOnThread(final String status, final String countdown) {
        runOnUiThread(() -> {
            statusTextView.setText(status);
            countdownTextView.setText(countdown);
        });
    }


    private void normalizeEmbedding(float[] emb) {
        float norm = 0;
        for (float v : emb) norm += v * v;
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < emb.length; i++) emb[i] /= norm;
        }
    }

    private boolean loadEmbeddingsFromAssets() {
        try {
            InputStream is = getAssets().open("embeddings.json");
            String json = readStreamToString(is);
            is.close();

            Map<String, List<List<Double>>> temp = new Gson().fromJson(
                    json,
                    new TypeToken<Map<String, List<List<Double>>>>() {}.getType()
            );

            KNOWN_FACE_EMBEDDINGS.clear();

            for (Map.Entry<String, List<List<Double>>> entry : temp.entrySet()) {
                String name = entry.getKey();
                List<List<Double>> embeddingsList = entry.getValue();

                List<Double> firstEmb = embeddingsList.get(0);
                float[] embArr = new float[firstEmb.size()];
                for (int j = 0; j < firstEmb.size(); j++) {
                    embArr[j] = firstEmb.get(j).floatValue();
                }
                KNOWN_FACE_EMBEDDINGS.put(name, embArr);
            }

            Log.i(TAG, "✅ Loaded embeddings from assets: " + KNOWN_FACE_EMBEDDINGS.size());
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Error loading embeddings from assets", e);
            return false;
        }
    }


    private boolean loadEmbeddingsFromStorage() {
        try {
            File embeddingsFile = new File(getExternalFilesDir("Pictures/FacultyPhotos"), "embeddings.json");
            if (!embeddingsFile.exists()) {
                Log.d(TAG, "Embeddings file does not exist");
                return false;
            }

            String jsonStr = new String(Files.readAllBytes(embeddingsFile.toPath()), StandardCharsets.UTF_8);
            JSONObject jsonObj = new JSONObject(jsonStr);

            facultyEmbeddings.clear();
            KNOWN_FACE_EMBEDDINGS.clear();

            Iterator<String> keys = jsonObj.keys();
            while (keys.hasNext()) {
                String facultyName = keys.next();
                JSONArray embeddingsArray = jsonObj.getJSONArray(facultyName);

                if (embeddingsArray.length() == 0) continue;

                List<float[]> allEmbeddings = new ArrayList<>();
                for (int i = 0; i < embeddingsArray.length(); i++) {
                    JSONArray arr = embeddingsArray.getJSONArray(i);
                    float[] emb = new float[arr.length()];
                    for (int j = 0; j < arr.length(); j++) {
                        emb[j] = (float) arr.getDouble(j);
                    }
                    allEmbeddings.add(emb);
                }
                facultyEmbeddings.put(facultyName, allEmbeddings);

                int embSize = allEmbeddings.get(0).length;
                float[] avgEmb = new float[embSize];
                for (float[] emb : allEmbeddings) {
                    for (int j = 0; j < embSize; j++) {
                        avgEmb[j] += emb[j];
                    }
                }
                for (int j = 0; j < embSize; j++) {
                    avgEmb[j] /= allEmbeddings.size();
                }

                KNOWN_FACE_EMBEDDINGS.put(facultyName, avgEmb);
            }

            Log.d(TAG, "✅ Embeddings loaded successfully from storage.");
            Log.d(TAG, "Faculties loaded: " + facultyEmbeddings.keySet().size());
            Log.d(TAG, "KNOWN_FACE_EMBEDDINGS loaded: " + KNOWN_FACE_EMBEDDINGS.size());

            return true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to load embeddings from storage", e);
            return false;
        }
    }

    private String readStreamToString(InputStream is) throws Exception {
        StringBuilder sb = new StringBuilder();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = is.read(buffer)) != -1) {
            sb.append(new String(buffer, 0, length, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }




    private float computeDynamicThreshold(Map<String, float[]> embeddingsMap) {
        List<Float> intraDists = new ArrayList<>();
        List<Float> interDists = new ArrayList<>();

        List<String> names = new ArrayList<>(embeddingsMap.keySet());

        for (String name : names) {
            float[] emb = embeddingsMap.get(name);
            if (emb != null) {
                float intra = simulateNoiseDistance(emb);
                intraDists.add(intra);
            }
        }

        for (int i = 0; i < names.size(); i++) {
            for (int j = i + 1; j < names.size(); j++) {
                float[] embA = embeddingsMap.get(names.get(i));
                float[] embB = embeddingsMap.get(names.get(j));
                if (embA != null && embB != null) {
                    float d = FaceNet.distance(embA, embB);
                    interDists.add(d);
                }
            }
        }

        float meanIntra = average(intraDists);
        float meanInter = average(interDists);
        float threshold = (meanIntra + meanInter) / 2;

        Log.d("DynamicThreshold", "Mean Intra = " + meanIntra +
                " | Mean Inter = " + meanInter +
                " | Computed Threshold = " + threshold);

        if (threshold < 0.3f || threshold > 1.3f) threshold = 0.9f;

        return threshold;
    }

    private float simulateNoiseDistance(float[] emb) {
        float[] noisy = emb.clone();
        for (int i = 0; i < noisy.length; i++) {
            noisy[i] += (Math.random() - 0.5f) * 0.02f;
        }
        return FaceNet.distance(emb, noisy);
    }

    private float average(List<Float> list) {
        if (list == null || list.isEmpty()) return 0f;
        float sum = 0f;
        for (float v : list) sum += v;
        return sum / list.size();
    }

    private void evaluateRecognitionAccuracy() {
        if (KNOWN_FACE_EMBEDDINGS.size() < 2) {
            Log.d("ModelAccuracy", "Not enough embeddings to evaluate.");
            return;
        }

        int totalComparisons = 0;
        int correctMatches = 0;

        List<String> names = new ArrayList<>(KNOWN_FACE_EMBEDDINGS.keySet());
        for (int i = 0; i < names.size(); i++) {
            String nameA = names.get(i);
            float[] embA = KNOWN_FACE_EMBEDDINGS.get(nameA);
            if (embA == null) continue;

            for (int j = 0; j < names.size(); j++) {
                String nameB = names.get(j);
                float[] embB = KNOWN_FACE_EMBEDDINGS.get(nameB);
                if (embB == null) continue;

                float distance = FaceNet.distance(embA, embB);
                boolean samePerson = nameA.equals(nameB);
                boolean recognized = distance < dynamicThreshold;

                if ((recognized && samePerson) || (!recognized && !samePerson)) {
                    correctMatches++;
                }

                totalComparisons++;
            }
        }

        float accuracy = (float) correctMatches / totalComparisons * 100f;
        Log.d("ModelAccuracy", String.format("Recognition Accuracy: %.2f%% (Threshold = %.3f)", accuracy, dynamicThreshold));
    }

    private void testLoadEmbeddings() {
        try {
            File embeddingsFile = new File(
                    getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                    "FacultyPhotos/embeddings.json"
            );

            Log.d(TAG, "Embeddings file exists: " + embeddingsFile.exists());
            Log.d(TAG, "Embeddings file path: " + embeddingsFile.getAbsolutePath());

            if (!embeddingsFile.exists()) return;

            FileInputStream fis = new FileInputStream(embeddingsFile);
            String json = readStreamToString(fis);
            fis.close();

            Log.d(TAG, "JSON content snippet: " + json.substring(0, Math.min(json.length(), 200)) + "...");

            Map<String, List<List<Double>>> temp = new Gson().fromJson(
                    json,
                    new TypeToken<Map<String, List<List<Double>>>>(){}.getType()
            );

            if (temp == null || temp.isEmpty()) {
                Log.e(TAG, "Parsed JSON is null or empty!");
                return;
            }

            for (Map.Entry<String, List<List<Double>>> entry : temp.entrySet()) {
                String name = entry.getKey();
                List<List<Double>> embeddingsList = entry.getValue();
                Log.d(TAG, "Person: " + name + " | # of embeddings: " + embeddingsList.size());

                if (!embeddingsList.isEmpty()) {
                    List<Double> firstEmb = embeddingsList.get(0);
                    Log.d(TAG, "First embedding sample: " + firstEmb.subList(0, Math.min(firstEmb.size(), 10)));
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error testing embeddings load", e);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_ACTION && resultCode == RESULT_OK && data != null) {
            String action = data.getStringExtra(ActionActivity.EXTRA_ACTION);

            if (ActionActivity.ACTION_TAKE_BREAK.equals(action)) {
                onTakeBreakClicked(null);

            } else if (ActionActivity.ACTION_END_CLASS.equals(action)) {

                if (!isDoorUnlocker(authorizedUnlocker)) {
                    Intent intent = new Intent(MainActivity.this, DashboardActivity.class);
                    intent.putExtra("profName", authorizedUnlocker);
                    intent.putExtra("status", "Only the faculty who unlocked the door can end the class.");
                    startActivity(intent);
                    finish();
                    return;
                }

                onEndClassClicked(null);

            } else if (ActionActivity.ACTION_RESUME_CLASS.equals(action)) {
                updateDoorStatus(lastRecognizedFace, "In Class", "UNLOCKED");
                Intent intent = new Intent(MainActivity.this, DashboardActivity.class);
                intent.putExtra("profName", authorizedUnlocker);
                intent.putExtra("status", "Status: In Class");
                startActivity(intent);
                finish();
            }
        }
    }


    private void saveStatus(String status) {
        getSharedPreferences("faculty_state", MODE_PRIVATE)
                .edit()
                .putString("status", status)
                .apply();
    }


    @SuppressWarnings("MissingSuperCall")
    @Override
    public void onBackPressed() {
        if (authorizedUnlocker == null) {
            Toast.makeText(this, "You must scan a face first.", Toast.LENGTH_SHORT).show();
        } else {
            Intent intent = new Intent(MainActivity.this, DashboardActivity.class);
            intent.putExtra("profName", authorizedUnlocker);
            intent.putExtra("status", "In Class");
            startActivity(intent);
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopConfirmationTimer();
        stopVisualCountdown();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (faceNet != null) faceNet.close();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initializeSystem();
            } else {
                finish();
            }
        }

        if (requestCode == REQUEST_BLUETOOTH_PERMISSION) {
            if (grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                Log.d("Bluetooth", "Bluetooth permission granted");
                recreate();

            } else {
                Toast.makeText(
                        this,
                        "Bluetooth permission is required to connect to the Door Lock",
                        Toast.LENGTH_LONG
                ).show();
                finish();
            }
        }
    }

}