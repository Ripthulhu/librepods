package me.kavishdevar.librepods.shizuku;

import android.app.Application;
import android.app.Instrumentation;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.os.Process;

import java.lang.reflect.Field;

/** Process-local shell attribution for the Shizuku UserService, which is not an Android app. */
final class ShellAudioContext extends ContextWrapper {
    ShellAudioContext(Context base) {
        super(base);
        if (Process.myUid() != 2000) {
            throw new SecurityException("This bridge supports the Shizuku shell backend only");
        }
    }

    @Override public String getPackageName() { return "com.android.shell"; }
    // Context's hidden getOpPackageName is invoked by framework audio services.
    @androidx.annotation.Keep
    public String getOpPackageName() { return getPackageName(); }
    @Override public Context getApplicationContext() { return this; }
    @Override public Context createDeviceContext(int deviceId) {
        if (deviceId != 0) throw new IllegalArgumentException("Only the physical device is supported");
        return this; // BluetoothManager pins device 0; keep the shell attribution instead of the base context's.
    }
    @Override public AttributionSource getAttributionSource() {
        return new AttributionSource.Builder(Process.myUid()).setPackageName(getPackageName()).build();
    }

    void installProcessAttribution() throws ReflectiveOperationException {
        // AudioPolicy creates streams internally; those constructors use ActivityThread attribution.
        Class<?> type = Class.forName("android.app.ActivityThread");
        Object thread = type.getMethod("currentActivityThread").invoke(null);
        if (thread == null) throw new IllegalStateException("Shizuku did not supply an ActivityThread");
        Field initial = type.getDeclaredField("mInitialApplication");
        initial.setAccessible(true);
        Application application;
        try {
            application = Instrumentation.newApplication(Application.class, this);
        } catch (Exception error) {
            throw new IllegalStateException("Cannot create shell audio attribution", error);
        }
        initial.set(thread, application);
        Field bound = type.getDeclaredField("mBoundApplication");
        bound.setAccessible(true);
        Object data = bound.get(thread);
        if (data == null) {
            java.lang.reflect.Constructor<?> constructor = Class.forName("android.app.ActivityThread$AppBindData").getDeclaredConstructor();
            constructor.setAccessible(true);
            data = constructor.newInstance();
            bound.set(thread, data);
        }
        Field appInfo = data.getClass().getDeclaredField("appInfo");
        appInfo.setAccessible(true);
        ApplicationInfo previous = (ApplicationInfo) appInfo.get(data);
        ApplicationInfo info = previous == null ? new ApplicationInfo() : new ApplicationInfo(previous);
        info.packageName = getPackageName();
        info.uid = Process.myUid();
        appInfo.set(data, info);
    }
}
