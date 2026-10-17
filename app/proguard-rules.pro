# Nothing is stripped: the app is small and uses no reflection of its own,
# so minify stays off in release.  These two lines are only here so the
# release build has a file to read.
-dontwarn okhttp3.**
-dontwarn okio.**
