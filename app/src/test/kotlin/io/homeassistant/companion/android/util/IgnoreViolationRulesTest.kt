package io.homeassistant.companion.android.util

import android.os.strictmode.DiskReadViolation
import android.os.strictmode.Violation
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guards the StrictMode ignore rules that keep [io.homeassistant.companion.android.common.util.HAStrictMode]
 * from being fatal for violations the app cannot control.
 *
 * The MediaTek case is a regression test for a real crash: on a Redmi K70 Ultra the vendor boost
 * framework read the disk on the main thread from inside the touch-event pipeline, no rule matched,
 * and `FailFast` exited the process (`System.exit called, status: 1`). The frames below are the
 * ones captured in the device logcat.
 */
class IgnoreViolationRulesTest {

    /** Frames of the fatal `DiskReadViolation` from the Redmi K70 Ultra logcat. */
    private val mediatekBoostFwkTouchViolation = diskReadViolation(
        StackTraceElement("java.io.File", "exists", "File.java", -2),
        StackTraceElement("com.mediatek.boostfwk.utils.Util", "isGameApp", "Util.java", -2),
        StackTraceElement(
            "com.mediatek.boostfwk.identify.scroll.ScrollIdentify",
            "checkAppType",
            "ScrollIdentify.java",
            -2,
        ),
        StackTraceElement(
            "com.mediatek.boostfwk.BoostModuleDispatcher",
            "dispatch",
            "BoostModuleDispatcher.java",
            -2,
        ),
        StackTraceElement(
            "android.view.ViewRootImpl\$ViewPostImeInputStage",
            "processPointerEvent",
            "ViewRootImpl.java",
            -2,
        ),
    )

    /** A disk read performed by the app's own code must stay fatal. */
    private val appCodeDiskRead = diskReadViolation(
        StackTraceElement("java.io.File", "exists", "File.java", -2),
        StackTraceElement(
            "io.homeassistant.companion.android.sensors.SensorReceiver",
            "onReceive",
            "SensorReceiver.kt",
            -2,
        ),
    )

    @Test
    fun `Mediatek boost framework disk read is ignored`() {
        assertTrue(
            threadPolicyIgnoredViolationRules.any { it.shouldIgnore(mediatekBoostFwkTouchViolation) },
            "The vendor boost framework violation must not be fatal",
        )
    }

    @Test
    fun `Disk read from the app's own code is still fatal`() {
        assertFalse(
            threadPolicyIgnoredViolationRules.any { it.shouldIgnore(appCodeDiskRead) },
            "A disk read with no vendor frame must keep triggering FailFast",
        )
    }

    @Test
    fun `Exactly one rule covers the boost framework violation`() {
        val matching = threadPolicyIgnoredViolationRules.filter { it.shouldIgnore(mediatekBoostFwkTouchViolation) }
        assertEquals(1, matching.size, "Exactly one rule should match the boost framework violation")
    }

    @Test
    fun `Boost framework rule does not ignore other violation types`() {
        val notADiskRead = mockk<Violation>()
        assertFalse(
            threadPolicyIgnoredViolationRules.any { it.shouldIgnore(notADiskRead) },
            "The Mediatek rule must stay scoped to DiskReadViolation",
        )
    }

    /**
     * `DiskReadViolation`'s constructor is package-private in the SDK stubs, so the violation is
     * mocked instead of built. Only [Violation.stackTrace] matters to the rules under test.
     */
    private fun diskReadViolation(vararg frames: StackTraceElement): DiskReadViolation = mockk {
        every { stackTrace } returns frames
    }
}
