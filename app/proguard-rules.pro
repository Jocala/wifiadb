# R8 rules for wifiadb.
#
# The app is a small, non-reflective utility: no serialization, no JNI, no
# dynamic class loading. Manifest-declared components (BootReceiver,
# MainActivity, WifiAdbService) are kept automatically by R8 via the generated
# keep rules from AGP, so nothing needs to be listed here by hand.
#
# Keep line numbers useful in crash reports from the field.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
