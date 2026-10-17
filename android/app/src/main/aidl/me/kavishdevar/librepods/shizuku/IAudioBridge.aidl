package me.kavishdevar.librepods.shizuku;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import me.kavishdevar.librepods.shizuku.IAudioBridgeCallback;
import me.kavishdevar.librepods.shizuku.IPlaybackRecoveryOwner;

interface IAudioBridge {
    void probe(IAudioBridgeCallback callback) = 0;
    ParcelFileDescriptor startMicrophoneInjection(int sampleRate, int channels,
        String targetPackage, boolean forCall, String selectedAddress, IBinder owner,
        IAudioBridgeCallback callback) = 1;
    void stopSession(IBinder owner) = 2;
    boolean restoreNativePlayback(String address, IPlaybackRecoveryOwner owner) = 3;
    boolean isMicrophonePrivacyBlocked() = 4;
    boolean confirmPairing(String address, IPlaybackRecoveryOwner owner) = 5;
    void destroy() = 16777114;
}
