package me.kavishdevar.librepods.shizuku;

oneway interface IAudioBridgeCallback {
    void onReport(String json);
    void onSessionState(String state, String detail);
}
