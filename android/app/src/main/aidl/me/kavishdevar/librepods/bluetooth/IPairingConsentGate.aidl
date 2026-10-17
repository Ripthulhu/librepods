package me.kavishdevar.librepods.bluetooth;

import me.kavishdevar.librepods.bluetooth.IPairingConsentOwner;

interface IPairingConsentGate {
    void watch(int modelId, IPairingConsentOwner owner);
    void finish(long requestId, boolean accepted);
    void stop(IPairingConsentOwner owner);
}
