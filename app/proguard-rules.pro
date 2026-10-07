# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# ── Spiritual Tracker ────────────────────────────────────────────────────
# Firestore fills these models in by reflection (doc.toObject / set(pojo)); keep their
# names, fields, getters/setters and no-arg constructors.
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod
-keep class com.keziah.spiritualtracker.JournalEntry { *; }
-keep class com.keziah.spiritualtracker.JournalReply { *; }
-keep class com.keziah.spiritualtracker.PrayerRequest { *; }
-keep class com.keziah.spiritualtracker.MemoryVerse { *; }

# WorkManager instantiates workers by class name.
-keep class * extends androidx.work.ListenableWorker { <init>(...); }

# Cloudinary loads its signature provider and upload strategies reflectively.
-keep class com.cloudinary.** { *; }
-dontwarn com.cloudinary.**
