# Keep public API
#-keep public class com.aima.bargein.BargeInEngine { *; }
#-keep public class com.aima.bargein.BargeInConfig { *; }
#-keep public interface com.aima.bargein.BargeInListener { *; }

# Keep JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Timber
-dontwarn timber.log.**