package com.tracewayapp.symbols

import org.gradle.api.provider.Property

abstract class TracewayExtension {
    abstract val uploadToken: Property<String>

    abstract val url: Property<String>

    abstract val autoUpload: Property<Boolean>

    abstract val proguardUuid: Property<String>
}
