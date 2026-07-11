# Never ship Android log calls in public release builds. Some runtime failures can include
# provider or session context, so stripping the calls is the final defense after sanitizing the
# individual call sites.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}
