-keep class ai.onnxruntime.** { *; }
# The instrumentation runner calls this shared AndroidX class reflectively.
-keep class androidx.tracing.** { *; }
# Keep the binary APIs shared with the separately installed instrumentation APK.
# R8 still shrinks/obfuscates application UI code. Shared APIs cannot be
# finalized/inlined because subclasses in the separate test APK are invisible.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class kotlinx.serialization.** { *; }
-keep class androidx.compose.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.activity.** { *; }
-keep class androidx.savedstate.** { *; }
-keep class androidx.collection.** { *; }
-keep class androidx.concurrent.** { *; }
-keep class com.google.common.util.concurrent.** { *; }
-keep class app.shenghuoyouju.ui.AppActions { *; }
-keep class app.shenghuoyouju.ui.AppUiState { *; }
-keep class app.shenghuoyouju.ui.UiSearchPhase { *; }
-keep class app.shenghuoyouju.ui.LifeEvidenceAppKt { public *; }
-keep class app.shenghuoyouju.ui.ThemeKt { public *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,AnnotationDefault
# Instrumentation exercises the same optimized release implementation.
-keep,allowoptimization class app.shenghuoyouju.data.** { *; }
-keep,allowoptimization class app.shenghuoyouju.search.** { *; }
-keep,allowoptimization class app.shenghuoyouju.MainViewModel { *; }
