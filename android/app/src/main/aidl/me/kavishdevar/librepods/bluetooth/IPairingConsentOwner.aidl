package me.kavishdevar.librepods.bluetooth;

import android.bluetooth.BluetoothDevice;

oneway interface IPairingConsentOwner {
    void onReady();
    void onConsent(in BluetoothDevice device, long receivedAt, long requestId);
}
