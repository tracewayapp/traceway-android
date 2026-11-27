package com.tracewayapp.traceway.internal

import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import java.util.Locale

/** The device and OS attributes every record carries ([DeviceInfoCollector]). */
@RunWith(RobolectricTestRunner::class)
class DeviceInfoTest {
    private fun collect(): Map<String, String> = DeviceInfoCollector.collect(ApplicationProvider.getApplicationContext<Application>())

    @Test
    fun theCoreKeysArePresent() {
        ShadowBuild.setManufacturer("Google")
        ShadowBuild.setBrand("google")
        ShadowBuild.setModel("Pixel 8")
        ShadowBuild.setDevice("shiba")
        val info = collect()
        assertEquals("android", info["os.name"])
        assertEquals("linux", info["os.type"])
        assertEquals(Build.VERSION.RELEASE, info["os.version"])
        assertEquals(Build.VERSION.SDK_INT.toString(), info["android.os.api_level"])
        assertEquals("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})", info["device.systemVersion"])
        assertEquals(info["device.systemVersion"], info["os.description"])
        assertEquals("Google", info["device.manufacturer"])
        assertEquals("google", info["device.brand"])
        assertEquals("Pixel 8", info["device.model"])
        assertEquals("Pixel 8", info["device.model.name"])
        assertEquals("shiba", info["device.modelId"])
        assertTrue(info["runtime.version"], info["runtime.version"]!!.startsWith("Java "))
        // The dimensions metrics keep (device.class only when the memory size is known).
        assertEquals(Build.VERSION.RELEASE.substringBefore('.'), info["os.major"])
        assertEquals("phone", info["device.form_factor"])
    }

    @Test
    fun anEmulatorIsNotAPhysicalDevice() {
        fun physical(fingerprint: String, model: String = "Pixel 8", product: String = "shiba", hardware: String = "shiba"): String? {
            ShadowBuild.setFingerprint(fingerprint)
            ShadowBuild.setModel(model)
            ShadowBuild.setProduct(product)
            ShadowBuild.setHardware(hardware)
            return collect()["device.isPhysical"]
        }
        assertEquals("true", physical("google/shiba/shiba:14/UQ1A.240205.004/11269751:user/release-keys"))
        assertEquals("false", physical("generic/sdk_gphone64_x86_64/emulator64_x86_64:14/UE1A/1:userdebug/dev-keys"))
        assertEquals("false", physical("unknown/sdk/generic:9/PSR1/1:eng/test-keys"))
        assertEquals("false", physical("google/sdk_gphone64_arm64/emu64a:14/UE1A/1:user/release-keys", model = "sdk_gphone64_arm64"))
        assertEquals("false", physical("google/oriole/oriole:13/TQ3A/1:user/release-keys", hardware = "ranchu"))
        assertEquals("false", physical("google/oriole/oriole:13/TQ3A/1:user/release-keys", product = "sdk_google_phone_x86"))
        assertEquals("false", physical("Android/goldfish/1:user", hardware = "goldfish"))
    }

    @Test
    @Config(qualifiers = "fr-rCA")
    fun theLocaleIsTheConfigurations() {
        assertEquals("fr_CA", collect()["device.locale"])
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp-xhdpi")
    fun theScreenIsInPixelsAndItsDensityIsWrittenAlikeInEveryLocale() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val info = collect()
            assertEquals("2.0", info["screen.density"])
            assertEquals("800x1600", info["screen.resolution"])
        } finally {
            Locale.setDefault(saved)
        }
    }
}
