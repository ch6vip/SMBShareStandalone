# SMBShareStandalone ProGuard rules

# Keep application & components referenced from the manifest
-keep class com.smbshare.SmbShareApp { *; }
-keep class com.smbshare.SmbService { *; }
-keep class com.smbshare.ui.MainActivity { *; }

# ViewBinding generated classes
-keep class com.smbshare.databinding.** { *; }

# Kotlin metadata / coroutines
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-dontwarn kotlinx.coroutines.**

# AndroidX / Material (handled by their own consumer rules, keep quiet)
-dontwarn javax.annotation.**
