package com.sd.facultyfacialrecognition;

import android.content.Context;
import android.util.Log;

public class BluetoothServiceSingleton {
    private static BluetoothService instance;

    public static BluetoothService getInstance(Context context, android.bluetooth.BluetoothDevice device) {
        if (instance == null) {
            instance = new BluetoothService(context, device);
            new Thread(() -> {
                boolean connected = instance.connect();
                if (connected && context instanceof MainActivity) {
                    ((MainActivity) context).runOnUiThread(() -> {
                        ((MainActivity) context).checkBluetoothBeforeCamera();
                    });
                }
                if (!connected) Log.e("BluetoothServiceSingleton", "Failed to connect to Bluetooth device");
            }).start();
        }
        return instance;
    }

    public static BluetoothService getInstance() {
        return instance;
    }

    public static void reset() {
        instance = null;
    }
}