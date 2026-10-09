# Keep class and method names readable so crash reports from the phone make sense.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# Stamp engine classes are small; keep them whole to avoid surprises.
-keep class com.lanrex.sitecam.core.** { *; }
