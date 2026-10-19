# SMBJ uses reflection for protocol/event handlers and crypto providers.
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class org.slf4j.** { *; }
# Native JNI entry points and VLC events must retain their names/signatures.
-keep class org.videolan.** { *; }
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod
