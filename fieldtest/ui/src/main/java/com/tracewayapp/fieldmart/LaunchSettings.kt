package com.tracewayapp.fieldmart

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle

/**
 * The launch settings of 02-APP-SPEC.md §6. On Android they arrive as intent string extras on [ConfigActivity],
 * which stores them here before the measured cold start, so `Application.onCreate` (where the SDK starts, as the
 * docs say) can read them.
 */
class LaunchSettings(
    val run: String,
    val variant: String,
    val scenario: String,
    val baseUrl: String,
    val fixtures: String,
    val token: String,
    val evidence: Boolean,
    val fps: Int?,
    val toolkit: String,
    val remote: Boolean,
    val target: String,
    val campaign: String,
) {
    val feedViews: Boolean get() = toolkit == "views"

    fun describe(): String =
        "run=$run variant=$variant scenario=$scenario evidence=${if (evidence) 1 else 0} toolkit=$toolkit fps=${fps ?: "default"} " +
            "remote=${if (remote) 1 else 0} target=$target campaign=$campaign baseUrl=$baseUrl fixtures=$fixtures"

    companion object {
        const val PREFS = "fieldmart-launch"
        const val DEFAULT_BASE = "http://127.0.0.1:18190"

        /** Every key the app reads; [ConfigActivity] stores these and nothing else. */
        val KEYS = listOf(
            "fmRun", "fmVariant", "fmScenario", "fmBaseUrl", "fmFixtures", "fmToken", "fmEvidence", "fmFps",
            "fmChart", "fmToolkit", "fmRemote", "fmAutopilot", "fmTarget", "fmCampaign",
        )

        fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun read(context: Context): LaunchSettings {
            val p = prefs(context)
            fun s(k: String, d: String) = p.getString(k, null)?.takeIf { it.isNotBlank() } ?: d
            return LaunchSettings(
                run = s("fmRun", "manual"),
                variant = s("fmVariant", "core"),
                scenario = s("fmScenario", "none"),
                baseUrl = s("fmBaseUrl", DEFAULT_BASE),
                fixtures = s("fmFixtures", "$DEFAULT_BASE/fx").trimEnd('/'),
                token = s("fmToken", "android-dev-token"),
                evidence = s("fmEvidence", "0") == "1",
                fps = p.getString("fmFps", null)?.toIntOrNull(),
                toolkit = s("fmToolkit", "compose"),
                remote = s("fmRemote", "0") == "1",
                target = s("fmTarget", "unknown"),
                campaign = s("fmCampaign", "none"),
            )
        }

        /** Replaces the stored settings with the `fm*` extras of [extras]; synchronous, before the process is stopped. */
        fun store(context: Context, extras: Bundle?): Boolean {
            val e = prefs(context).edit().clear()
            if (extras != null) for (k in KEYS) extras.get(k)?.let { e.putString(k, it.toString()) }
            return e.commit()
        }
    }
}
