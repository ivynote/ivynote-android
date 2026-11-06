package app.ivynote.core

import kotlinx.serialization.json.Json

/** Timestamps are Unix milliseconds throughout (guide 4). */
typealias Millis = Long

/**
 * The JSON settings for the wire and the local store: unknown fields are
 * ignored, missing or null fields take their zero value (guide 12 #5), and
 * nulls are left out when encoding (`baseRev`, `fields` of a delete).
 */
val IvyJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
}

fun nowMillis(): Millis = System.currentTimeMillis()
