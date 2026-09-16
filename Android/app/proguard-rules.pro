# OpenDisplay receiver — keep control-message type strings if minify is enabled.
-keepclassmembers class com.terrynamic.opendisplay.protocol.** {
    public static final java.lang.String *;
}
