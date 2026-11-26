# ClusterLab Release R8 / ProGuard Configuration
# Author: AJ60

# Preserve line numbers for readable crash diagnostics
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep Room database, entities, and DAOs
-keep class com.example.data.ClusterLabDatabase { *; }
-keep class com.example.data.SavedDatasetEntity { *; }
-keep interface com.example.data.SavedDatasetDao { *; }

# Keep Android service and FileProvider components
-keep class com.example.service.RpcService { *; }
-keep class androidx.core.content.FileProvider { *; }

# Keep Kotlin Coroutines & DataStore preferences internals
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**
