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
-dontwarn androidx.navigation.**

# OpenTelemetry's OTLP marshaler reads a resource's entities by reflection:
# io.opentelemetry.sdk.resources.internal.EntityUtil looks these members up by
# name (Resource.class.getDeclaredMethod("getEntities"), ...). R8 inlines or
# renames them otherwise, every export of logs, spans and metrics then fails
# ("Error calling getEntities on Resource"), and a minified app sends none.
-keepclassmembers class io.opentelemetry.sdk.resources.Resource {
    java.util.Collection getEntities();
    io.opentelemetry.api.common.Attributes getUnassociatedAttributes();
}
-keepclassmembers class * extends io.opentelemetry.sdk.resources.Resource {
    java.util.Collection getEntities();
}
-keepclassmembers class io.opentelemetry.sdk.resources.ResourceBuilder {
    io.opentelemetry.sdk.resources.ResourceBuilder addEntity(io.opentelemetry.sdk.resources.internal.Entity);
}
