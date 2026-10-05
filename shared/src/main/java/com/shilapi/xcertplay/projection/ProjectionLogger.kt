package com.shilapi.xcertplay.projection

/**
 * Minimal logging seam for the projection layer.
 *
 * Backends log through this instead of `android.util.Log` directly so unit tests
 * can capture output and so every line can be tagged with its backend id.
 */
fun interface ProjectionLogger {
    fun log(message: String)

    companion object {
        /** Drops everything; the default for library code and tests. */
        val NONE = ProjectionLogger { }

        /** Writes to logcat when running on Android; safe to call from unit tests. */
        val ANDROID = ProjectionLogger { message ->
            try {
                android.util.Log.i(TAG, message)
            } catch (_: Throwable) {
                // Unit tests have no Android runtime; diagnostics must never fail a call.
            }
        }

        private const val TAG = "DiPlay-Projection"
    }
}
