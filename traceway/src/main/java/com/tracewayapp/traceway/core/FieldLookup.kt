package com.tracewayapp.traceway.core

/**
 * Reads a field of an observed record for matching: `event.name`, `signal`,
 * `severity`, `body`, `span.name`, `duration_ms`, `attributes.<k>`,
 * `resource.<k>`, `global.<k>`, `session.id`, ... Null when absent.
 */
internal typealias FieldLookup = (String) -> Any?

/** The pointer action of a page event (`click`, `hover`) for `event` steps. */
internal const val KEY_POINTER_ACTION = "browser.action"

/** A `(String selector) -> Boolean` telling whether the pointer's target matches a selector. */
internal const val KEY_TARGET_MATCHES = "browser.target.matches"
