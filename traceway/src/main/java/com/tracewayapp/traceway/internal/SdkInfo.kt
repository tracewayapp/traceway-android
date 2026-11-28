package com.tracewayapp.traceway.internal

import com.tracewayapp.traceway.BuildConfig

internal object SdkInfo {
    /** The instrumentation scope of every record, and `traceway.sdk.name`. */
    const val NAME = "traceway-android"
    val VERSION: String = BuildConfig.SDK_VERSION
    val OTEL_VERSION: String = BuildConfig.OTEL_VERSION

    /** Sent with every OTLP request, as the OTLP exporter specification asks. */
    val OTLP_USER_AGENT: String = "OTel-OTLP-Exporter-Java/$OTEL_VERSION $NAME/$VERSION"
}
