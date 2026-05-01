package com.tracewayapp.traceway.test

import android.app.Application
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tracewayapp.traceway.TracewayOptions
import com.tracewayapp.traceway.events.NavigationEvent
import com.tracewayapp.traceway.internal.NavigationCapture
import com.tracewayapp.traceway.test.ui.TracewayTestActivity
import com.tracewayapp.traceway.test.ui.TracewayTestActivity2
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationCaptureInstrumentedTest {

    @get:Rule
    val rule = TracewayTestRule(
        options = TracewayOptions(
            debounceMs = 50,
            persistToDisk = false,
            captureNavigation = true,
            debug = true,
        ),
    )

    private val capture = NavigationCapture()
    private lateinit var app: Application

    @Before
    fun installLifecycleObserver() {
        app = ApplicationProvider.getApplicationContext()
        app.registerActivityLifecycleCallbacks(capture)
    }

    @After
    fun removeLifecycleObserver() {
        app.unregisterActivityLifecycleCallbacks(capture)
    }

    @Test
    fun openingAnActivityRecordsAPushNavigationEvent() {
        ActivityScenario.launch(TracewayTestActivity::class.java).use {
            val pushes = rule.client.bufferedActions()
                .filterIsInstance<NavigationEvent>()
                .filter { it.action == "push" }
            assertTrue(
                "expected at least one push NavigationEvent for TracewayTestActivity, got: $pushes",
                pushes.any { it.to == "TracewayTestActivity" },
            )
        }
    }

    @Test
    fun navigatingToSecondActivityRecordsBothPushes() {
        ActivityScenario.launch(TracewayTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.startActivity(
                    android.content.Intent(activity, TracewayTestActivity2::class.java)
                )
            }
            // Allow the lifecycle to settle.
            Thread.sleep(500)

            val pushes = rule.client.bufferedActions()
                .filterIsInstance<NavigationEvent>()
                .filter { it.action == "push" }

            assertTrue(
                "expected push to TracewayTestActivity, got: $pushes",
                pushes.any { it.to == "TracewayTestActivity" },
            )
            assertTrue(
                "expected push to TracewayTestActivity2, got: $pushes",
                pushes.any { it.to == "TracewayTestActivity2" },
            )
        }
    }
}
