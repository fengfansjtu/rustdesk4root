# Keep class members from protobuf generated code.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
  <fields>;
}

# Keep rustls-platform-verifier classes for JNI
-keep, includedescriptorclasses class org.rustls.platformverifier.** { *; }

# Keep RootInputServer and RootInputManager classes and members for app_process execution
-keep public class com.carriez.flutter_hbb.RootInputServer {
    public static void main(java.lang.String[]);
    public *;
}
-keep public class com.carriez.flutter_hbb.RootInputManager {
    public *;
}