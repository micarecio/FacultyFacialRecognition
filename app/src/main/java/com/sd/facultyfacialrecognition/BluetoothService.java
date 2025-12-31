package com.sd.facultyfacialrecognition;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class BluetoothService {

    private static final String TAG = "BluetoothService";
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final BluetoothDevice device;
    private final Context context;
    private BluetoothSocket socket;
    private OutputStream outputStream;

    public BluetoothService(Context context, BluetoothDevice device) {
        this.context = context;
        this.device = device;
    }

    private boolean hasBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        } else {
            return true;
        }
    }

    public boolean connect() {
        if (!hasBluetoothPermission()) {
            Log.e(TAG, "BLUETOOTH_CONNECT permission not granted");
            return false;
        }

        try {
            socket = device.createRfcommSocketToServiceRecord(MY_UUID);
            BluetoothAdapter.getDefaultAdapter().cancelDiscovery();
            socket.connect();
            outputStream = socket.getOutputStream();
            Log.d(TAG, "Bluetooth connected successfully");
            return true;
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException: Permission denied", e);
            return false;
        } catch (IOException e) {
            Log.e(TAG, "Bluetooth connection failed", e);
            return false;
        }
    }

    public void sendDoorStatus(String status) {
        if (outputStream == null || !isConnected()) return;

        new Thread(() -> {
            try {
                String message = status + "\n";
                outputStream.write(message.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
                Log.d(TAG, "Sent message: " + message.trim());
            } catch (IOException e) {
                Log.e(TAG, "Error sending message", e);
            }
        }).start();
    }

    public void disconnect() {
        try {
            if (outputStream != null) {
                outputStream.close();
                outputStream = null;
            }
            if (socket != null) {
                socket.close();
                socket = null;
            }
            Log.d(TAG, "Bluetooth disconnected successfully");
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException: Permission denied", e);
        } catch (IOException e) {
            Log.e(TAG, "Error closing Bluetooth", e);
        }
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected();
    }
}