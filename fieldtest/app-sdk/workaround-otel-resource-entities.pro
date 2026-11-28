# WORKAROUND for finding android-002 (blocks-the-test). OpenTelemetry 1.68's OTLP marshaler reads the resource's
# entities through reflection (EntityUtil: Resource.class.getDeclaredMethod("getEntities"), and
# ResourceBuilder.addEntity). R8 inlines and renames those members, every OTLP export (logs, spans, metrics) then
# throws "Error calling getEntities on Resource", and a minified app sends nothing but replays. Neither the SDK's
# consumer rules nor OpenTelemetry's jars keep them. Applied only with -Pfm.workaround.otelResourceEntities (default
# on); -Pfm.workaround.otelResourceEntities=false builds the app as a customer's would be.
-keepclassmembers class io.opentelemetry.sdk.resources.Resource { java.util.Collection getEntities(); }
-keepclassmembers class * extends io.opentelemetry.sdk.resources.Resource { java.util.Collection getEntities(); }
-keepclassmembers class io.opentelemetry.sdk.resources.ResourceBuilder {
    io.opentelemetry.sdk.resources.ResourceBuilder addEntity(io.opentelemetry.sdk.resources.internal.Entity);
}
