# Release obfuscation on top of proguard-android-optimize.txt.
# The manifest's activities and the input method service are kept automatically by the
# rules AAPT2 generates, so nothing here needs to keep classes by name.

# Move every renamed class into one unnamed package, so the package structure doesn't survive.
-repackageclasses ''
-allowaccessmodification

# Crash traces keep line numbers (decoded with mapping.txt) but not the original file names.
-keepattributes LineNumberTable
-renamesourcefileattribute SourceFile

# Kotlin's null checks embed parameter names as strings; drop them from release builds.
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkParameterIsNotNull(java.lang.Object, java.lang.String);
    static void checkNotNullParameter(java.lang.Object, java.lang.String);
    static void checkExpressionValueIsNotNull(java.lang.Object, java.lang.String);
    static void checkNotNullExpressionValue(java.lang.Object, java.lang.String);
    static void checkReturnedValueIsNotNull(java.lang.Object, java.lang.String);
    static void checkFieldIsNotNull(java.lang.Object, java.lang.String);
}
