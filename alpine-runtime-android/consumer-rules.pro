# Runtime implementation rules are added with the Phase 3 process/installer implementation.
# JNI constructor/method names are part of the native PTY bridge ABI.
-keep class dev.alpine.runtime.android.internal.NativePtyBridge { *; }
-keep class dev.alpine.runtime.android.internal.NativePtyDescriptor { *; }
