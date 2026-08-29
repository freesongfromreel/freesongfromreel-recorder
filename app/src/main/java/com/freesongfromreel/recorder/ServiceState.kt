package com.freesongfromreel.recorder

/**
 * Shared (in-process) recorder state, so the Activity can reflect the
 * Service's real recording state even after the Activity was backgrounded.
 */
object ServiceState {
    @Volatile var isRecording: Boolean = false
}