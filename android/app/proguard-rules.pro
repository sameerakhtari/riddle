# LiteRT-LM uses JNI and native symbol lookups. Keep its Java/Kotlin API surface intact.
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# WorkManager instantiates workers by class name after process recreation.
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
-keep class com.sameerakhtari.riddle.local.ModelDownloadWorker { *; }

# Preserve Android entry points referenced from the manifest.
-keep public class com.sameerakhtari.riddle.RiddleApplication { *; }
-keep public class com.sameerakhtari.riddle.**Activity { *; }
