# SMBShareStandalone ProGuard rules

# Keep Android component entry points referenced from the manifest.
# Only preserve the lifecycle methods the Android framework calls via reflection;
# internal methods are left for R8/ProGuard to optimize and obfuscate freely.
-keep class com.smbshare.SmbShareApp {
    public <init>();
    public void onCreate();
}
-keep class com.smbshare.SmbService {
    public <init>();
    public void onCreate();
    public int onStartCommand(android.content.Intent, int, int);
    public void onDestroy();
    public android.os.IBinder onBind(android.content.Intent);
}
-keep class com.smbshare.ui.MainActivity {
    public <init>();
    public void onCreate(android.os.Bundle);
}

# Kotlin metadata / coroutines
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-dontwarn kotlinx.coroutines.**

# AndroidX / Material (handled by their own consumer rules, keep quiet)
-dontwarn javax.annotation.**
