# GeckoView native bindings are accessed through JNI — keep everything.
-keep class org.mozilla.geckoview.** { *; }

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**

# GeckoView bundles snakeyaml, which references desktop-only java.beans classes
-dontwarn org.yaml.snakeyaml.**
-dontwarn java.beans.**

# Coroutines
-dontwarn kotlinx.coroutines.**

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
