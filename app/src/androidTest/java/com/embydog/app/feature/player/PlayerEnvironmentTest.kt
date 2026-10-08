package com.embydog.app.feature.player

import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.embydog.app.MainActivity
import org.junit.Assert.*
import org.junit.Test

class PlayerEnvironmentTest {
    @Test fun playerRotationAndBrightnessAreScopedAndRestored() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val activity = instrument.startActivitySync(Intent(instrument.targetContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        val originalOrientation = activity.requestedOrientation
        val originalBrightness = activity.window.attributes.screenBrightness
        val environment = PlayerEnvironment(activity)
        try {
            instrument.runOnMainSync {
                environment.rotate(PlayerRotation.LANDSCAPE)
                environment.setBrightness(0.35f)
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
                assertEquals(0.35f, environment.brightness(), 0.001f)
            }
            instrument.waitForIdleSync()
            assertFalse("Rotation must not recreate the playback activity", activity.isDestroyed)
            instrument.runOnMainSync {
                environment.rotate(PlayerRotation.PORTRAIT)
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, activity.requestedOrientation)
                environment.rotate(PlayerRotation.AUTOMATIC)
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR, activity.requestedOrientation)
                environment.rotate(PlayerRotation.SYSTEM)
                assertEquals(originalOrientation, activity.requestedOrientation)
                environment.restore()
                assertEquals(originalOrientation, activity.requestedOrientation)
                assertEquals(originalBrightness, activity.window.attributes.screenBrightness, 0.001f)
            }
        } finally {
            instrument.runOnMainSync { environment.restore(); activity.finish() }
        }
    }
}
