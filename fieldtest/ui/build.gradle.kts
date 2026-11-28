import com.android.build.api.variant.LibraryAndroidComponentsExtension

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * Writes `values/fm_ids.xml`: a resource id for every element the driver finds by id (02-APP-SPEC.md §4, dashes as
 * underscores), including the indexed ones bound in recycled rows (products, feed rows, reviews), so each view
 * carries a real resource name. Adds every id of `shared/ids.json` when the shared files are there.
 */
abstract class GenerateFmIds : DefaultTask() {
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sharedIds: RegularFileProperty

    /** `shared/labels.json`: the fixed UI texts the checkpoints expect, written as string resources `fm_l_<path>`. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val labels: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val names = sortedSetOf<String>()
        fun add(vararg n: String) = n.forEach { names.add(it) }
        fun range(prefix: String, from: Int, to: Int) = (from..to).forEach { names.add(prefix + it) }
        add("fm_nav_home", "fm_nav_markets", "fm_nav_feed", "fm_nav_checkout", "fm_nav_more", "fm_nav_back")
        add("fm_home_logo", "fm_home_search", "fm_home_cart", "fm_home_hero", "fm_home_deals", "fm_home_grid", "fm_home_chips")
        range("fm_home_chip_", 0, 11)
        range("fm_home_deal_", 0, 19)
        range("fm_home_product_", 0, 2000)
        add("fm_product_gallery", "fm_product_qty_minus", "fm_product_qty_plus", "fm_product_readmore",
            "fm_product_reviews", "fm_product_add")
        range("fm_product_size_", 0, 5)
        range("fm_product_colour_", 0, 7)
        range("fm_product_helpful_r17_", 0, 499)
        add("fm_feed_list", "fm_feed_jump_end", "fm_feed_jump_start", "fm_feed_goto_field", "fm_feed_goto_go",
            "fm_feed_refresh")
        range("fm_feed_row_", 0, 9999)
        for (n in listOf("form", "name", "email", "phone", "account", "password", "address", "city", "postcode",
            "country", "date", "door", "gift", "giftnote", "card", "expiry", "cvv", "cardname", "savecard", "tip",
            "promo", "terms", "terms_agree", "terms_text", "menu", "edit", "edit_done", "notes", "place", "success",
            "loyalty", "order", "country_search", "country_list")) add("fm_checkout_$n")
        range("fm_checkout_window_", 0, 2)
        range("fm_checkout_promo_option_", 0, 7)
        range("fm_checkout_menu_item_", 0, 3)
        range("fm_checkout_edit_plus_", 0, 2)
        range("fm_checkout_rating_", 1, 5)
        val shared = sharedIds.orNull?.asFile
        if (shared != null && shared.isFile) {
            Regex("\"(fm-[A-Za-z0-9-]+)\"").findAll(shared.readText()).forEach { names.add(it.groupValues[1].replace('-', '_')) }
        }
        val out = outputDir.get().asFile.resolve("values")
        out.mkdirs()
        out.resolve("fm_ids.xml").writeText(buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n")
            for (n in names) append("    <item type=\"id\" name=\"").append(n).append("\" />\n")
            append("</resources>\n")
        })
        // Labels, verbatim: {n}-style placeholders stay as they are and are replaced in code.
        @Suppress("UNCHECKED_CAST")
        val root = groovy.json.JsonSlurper().parse(labels.get().asFile) as Map<String, Any?>
        fun esc(v: String) = v.replace("&", "&amp;").replace("<", "&lt;").replace("\\", "\\\\").replace("'", "\\'")
            .replace("\"", "\\\"").replace("@", "\\@").replace("?", "\\?").let { if (it.startsWith(" ")) "\"$it\"" else it }
        fun key(path: String) = "fm_l_" + path.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_')
        val xml = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!-- From shared/labels.json; do not edit. -->\n<resources>\n")
        fun walk(path: String, v: Any?) {
            when (v) {
                is Map<*, *> -> v.forEach { (k, x) -> if (k != "about") walk(if (path.isEmpty()) "$k" else "${path}_$k", x) }
                is List<*> -> {
                    xml.append("    <string-array name=\"").append(key(path)).append("\">\n")
                    v.forEach { xml.append("        <item formatted=\"false\">").append(esc(it.toString())).append("</item>\n") }
                    xml.append("    </string-array>\n")
                }
                null -> {}
                else -> xml.append("    <string name=\"").append(key(path)).append("\" formatted=\"false\">").append(esc(v.toString())).append("</string>\n")
            }
        }
        walk("", root)
        xml.append("</resources>\n")
        out.resolve("fm_labels.xml").writeText(xml.toString())
    }
}

android {
    namespace = "com.tracewayapp.fieldmart.ui"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        // The type every number comes from: the apps minify and shrink it (03-MEASUREMENT.md §3.3).
        create("bench") { initWith(getByName("release")) }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // A smoke test of the real screens under Robolectric (ui/src/test), before any device run.
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "1g"
                it.jvmArgs("-XX:+UseSerialGC", "-XX:MaxMetaspaceSize=512m")
                it.systemProperty("robolectric.logging.enabled", "false")
                it.systemProperty("java.io.tmpdir", it.temporaryDir.absolutePath)
                // Responses written by `node fieldtest/harness/fx-snapshot.mjs` (DataScreensTest skips without them).
                it.systemProperty("fm.snapshot", layout.buildDirectory.dir("fx-snapshot").get().asFile.absolutePath)
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// Resource ids for every element the driver finds by id (02-APP-SPEC.md §4: dashes become underscores), including
// the indexed ones (products, feed rows, reviews), so views bound in recycled rows carry a real resource name.
val generateFmIds by tasks.registering(GenerateFmIds::class) {
    val ids = rootProject.layout.projectDirectory.file("shared/ids.json")
    if (ids.asFile.isFile) sharedIds.set(ids)
    labels.set(rootProject.layout.projectDirectory.file("shared/labels.json"))
    outputDir.set(layout.buildDirectory.dir("generated/fm-ids/res"))
}

extensions.getByType(LibraryAndroidComponentsExtension::class.java).onVariants { variant ->
    variant.sources.res?.addGeneratedSourceDirectory(generateFmIds, GenerateFmIds::outputDir)
}

dependencies {
    api(project(":core"))

    api("androidx.core:core-ktx:1.13.1")
    api("androidx.appcompat:appcompat:1.7.0")
    api("androidx.fragment:fragment-ktx:1.8.5")
    api("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    api(composeBom)
    api("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    api("androidx.activity:activity-compose:1.9.3")
    api("androidx.navigation:navigation-compose:2.8.5")

    api("io.coil-kt:coil:2.7.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    api("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
