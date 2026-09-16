# OpenDisplay receiver — keep control-message type strings if minify is enabled.
-keepclassmembers class build.terrynamic.opendisplay.protocol.** {
    public static final java.lang.String *;
}
