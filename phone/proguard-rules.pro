# ProGuard rules pour l'appli phone
# Sucrerie standard Android

-keep class androidx.** { *; }
-keep interface androidx.** { *; }

# Garder les classes Kotlin
-keep class kotlin.Metadata { *; }
-keep class kotlin.** { *; }
-dontwarn kotlin.**

# Garder les noms des classes MainActivity
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
