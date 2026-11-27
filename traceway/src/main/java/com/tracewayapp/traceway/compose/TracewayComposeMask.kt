package com.tracewayapp.traceway.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import com.tracewayapp.traceway.TracewayMaskMode

/**
 * Hides this composable in wireframe recordings, the Compose counterpart of
 * a view's privacy mask. With [TracewayMaskMode.blank] it is a box of that
 * color and nothing inside it is recorded; with [TracewayMaskMode.blur] (the
 * default) the layout is kept but its text becomes `*` and its images
 * placeholders. Tap records leave out the text it shows. The app looks the
 * same to its user.
 *
 * A `testTag` of `traceway-mask` or `traceway-blank` masks the same way.
 */
fun Modifier.tracewayMask(mode: TracewayMaskMode = TracewayMaskMode.blur()): Modifier =
    this.semantics { this[TracewayMaskKey] = mode }

/** The semantics property [tracewayMask] sets; read by the wireframe capture and tap recording. */
internal val TracewayMaskKey = SemanticsPropertyKey<TracewayMaskMode>("TracewayMask")
