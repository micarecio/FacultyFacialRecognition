package com.sd.facultyfacialrecognition;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.List;

public class FaceAligner {

    private final FaceDetector detector;

    public FaceAligner(@NonNull Context context) {
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .build();

        detector = FaceDetection.getClient(options);
    }

    public Bitmap alignFace(Bitmap bitmap) {
        try {
            InputImage image = InputImage.fromBitmap(bitmap, 0);

            List<Face> faces = Tasks.await(detector.process(image));

            if (faces.size() == 0) {
                Log.d("FaceAligner", "No face detected.");
                return null;
            }

            Rect bounds = faces.get(0).getBoundingBox();

            int left = Math.max(bounds.left, 0);
            int top = Math.max(bounds.top, 0);
            int width = Math.min(bounds.width(), bitmap.getWidth() - left);
            int height = Math.min(bounds.height(), bitmap.getHeight() - top);

            Bitmap faceCrop = Bitmap.createBitmap(bitmap, left, top, width, height);

            return Bitmap.createScaledBitmap(faceCrop, 160, 160, true);

        } catch (Exception e) {
            e.printStackTrace();
            Log.e("FaceAligner", "Face alignment failed: " + e.getMessage());
            return null;
        }
    }

    public void close() {
        try {
            detector.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
