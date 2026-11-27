# Traceway Android SDK — rules applied to apps that minify.

# OpenTelemetry Java references optional classes it never loads on Android:
# Jackson (JSON serialization of OTLP), AutoValue/annotation processors, the
# incubator API.
-dontwarn com.fasterxml.jackson.**
-dontwarn com.google.auto.value.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn io.opentelemetry.api.incubator.**
-dontwarn io.opentelemetry.sdk.autoconfigure.**

# Optional integrations the SDK uses only when the app has them.
-dontwarn okhttp3.**
-dontwarn androidx.compose.**
-dontwarn androidx.fragment.**
