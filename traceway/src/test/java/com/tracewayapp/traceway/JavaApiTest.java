package com.tracewayapp.traceway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * AND2-8, written in Java so that it compiles only while the Java API stays
 * Java-friendly: options built one at a time, nulls that mean the default,
 * an interaction made current in a try-with-resources.
 */
public class JavaApiTest {
    @Test
    public void optionsAreBuiltOneAtATime() {
        TracewayOptions options = TracewayOptions.builder()
                .token("YOUR_TOKEN")
                .screenCapture(true)
                .attributes(Collections.singletonMap("tier", "gold"))
                .ignoreErrors(Collections.singletonList(Pattern.compile("Declined")))
                .propagateTraceHeaderUrlPatterns(Pattern.compile("^https://api\\."))
                .beforeCapture(captured -> !captured.isMessage())
                .telemetry(TracewayTelemetry.builder().url("https://collector.test").build())
                .version(null)
                .replayImages(null)
                .build();
        assertEquals("YOUR_TOKEN", options.getToken());
        assertEquals(TracewayOptions.DEFAULT_BASE_URL, options.getBaseUrl());
        assertTrue(options.getScreenCapture());
        assertNull(options.getVersion());
        assertEquals(TracewayImagePolicy.ALWAYS, options.getReplayImages());
        assertEquals("https://collector.test", options.getTelemetry().getUrl());

        // The token is the constructor's first parameter, for the shortest start.
        assertEquals(new TracewayOptions("YOUR_TOKEN"), TracewayOptions.builder().token("YOUR_TOKEN").build());
        assertEquals(options.toBuilder().debug(true).build().getToken(), "YOUR_TOKEN");
    }

    @Test
    public void startingWithoutAnApplicationReturnsNullAndCallsDoNothing() {
        assertNull(Traceway.init(null, TracewayOptions.builder().token("t").build()));
        TracewayInteraction checkout = Traceway.startInteraction("checkout");
        try (TracewayScope ignored = checkout.makeCurrent()) {
            Traceway.log("inside");
        }
        checkout.end();
    }
}
