# R8 rules for the release build.
#
# Strategy: conservative. We can't run the release APK on a device in CI, and
# this app is a reverse-engineered client whose failures show up as corrupted
# notes rather than crashes, so we keep every class and member in the app's
# own packages exactly as written. Only library code is shrunk and obfuscated.
# Tighten this once there are instrumented tests that exercise a minified
# build.

# --- App code: keep everything (no shrinking/obfuscation of our classes) -----
-keep class com.example.applenotes.** { *; }

# Enum valueOf()/values() are reached reflectively (e.g. the debug receiver's
# NoteAppender.Strategy.valueOf(...)); covered by the rule above, but spelled
# out so it survives if the broad keep is ever narrowed.
-keepclassmembers enum com.example.applenotes.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- kotlinx.serialization ----------------------------------------------------
# The library ships consumer rules for @Serializable classes looked up through
# the generated serializer() (e.g. Json.decodeFromString<T>()). These extra
# rules mirror the upstream README in case those consumer rules change.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,Signature
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Ktor / OkHttp ------------------------------------------------------------
# Ktor references JVM-only classes that do not exist on Android; they are
# never reached at runtime there.
-dontwarn java.lang.management.**
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Keep line numbers in stack traces so crash reports stay useful.
-keepattributes SourceFile,LineNumberTable
