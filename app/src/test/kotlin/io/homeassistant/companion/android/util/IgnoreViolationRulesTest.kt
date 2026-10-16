package io.homeassistant.companion.android.util

import android.os.strictmode.DiskReadViolation
import android.os.strictmode.DiskWriteViolation
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
 * Every stack used below is a verbatim copy of a frame list captured on the Redmi K70 Ultra
 * (HyperOS / Android 14) in `ha-crash2.txt`; nothing here is invented. Two separate real crashes
 * are covered:
 *
 * * the MediaTek boost framework, which reads the disk (`File.exists()`) **and** writes it
 *   (`Context.getCodeCacheDir()` → `mkdir()`) from the touch-event pipeline — the write half used
 *   to be fatal because the rule only matched [DiskReadViolation];
 * * the MIUI enterprise framework, which reads the disk (`File.exists()`) while
 *   `NotificationManager.notifyAsUser()` initialises it, i.e. on the very first notification.
 *
 * Both hit `FailFast` → `exitProcess(1)` (`System.exit called, status: 1`) because no rule matched.
 */
class IgnoreViolationRulesTest {

    /** Frames of the `DiskReadViolation` raised by the boost framework inside the touch pipeline. */
    private val mediatekBoostFwkTouchDiskRead = diskReadViolation(
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

    /**
     * Frames of the `DiskWriteViolation` from the very same `getCodeCacheDir()` call: the read above
     * probed with `File.exists()`, this one created the directory with `mkdir()`. It was fatal.
     */
    private val mediatekBoostFwkTouchDiskWrite = diskWriteViolation(
        StackTraceElement("libcore.io.BlockGuardOs", "mkdir", "BlockGuardOs.java", -2),
        StackTraceElement("android.system.Os", "mkdir", "Os.java", -2),
        StackTraceElement("android.app.ContextImpl", "ensurePrivateDirExists", "ContextImpl.java", -2),
        StackTraceElement("android.app.ContextImpl", "getCodeCacheDir", "ContextImpl.java", -2),
        StackTraceElement("android.content.ContextWrapper", "getCodeCacheDir", "ContextWrapper.java", -2),
        StackTraceElement("com.mediatek.boostfwk.utils.TasksUtil", "isFlutterApp", "TasksUtil.java", -2),
        StackTraceElement(
            "com.mediatek.boostfwk.info.ActivityInfo",
            "getPageType",
            "ActivityInfo.java",
            -2,
        ),
        StackTraceElement(
            "com.mediatek.boostfwk.identify.scroll.ScrollIdentify\$ScrollGestureListener",
            "onScroll",
            "ScrollIdentify.java",
            -2,
        ),
        StackTraceElement(
            "android.view.ViewRootImpl\$ViewPostImeInputStage",
            "processPointerEvent",
            "ViewRootImpl.java",
            -2,
        ),
    )

    /**
     * Frames of the `DiskReadViolation` raised by MIUI's enterprise framework while
     * `NotificationManager.notifyAsUser()` initialises it. This is what killed the process on startup.
     */
    private val miuiEnterpriseFrameworkDiskRead = diskReadViolation(
        StackTraceElement("java.io.File", "exists", "File.java", -2),
        StackTraceElement(
            "miui.enterprise.EpFrameworkFactory",
            "isEnterpriseJarExists",
            "EpFrameworkFactory.java",
            -2,
        ),
        StackTraceElement("miui.enterprise.EpFrameworkFactory", "get", "EpFrameworkFactory.java", -2),
        StackTraceElement(
            "miui.enterprise.ApplicationHelperStub\$SingletonHolder",
            "<clinit>",
            "ApplicationHelperStub.java",
            -2,
        ),
        StackTraceElement(
            "miui.enterprise.ApplicationHelperStub",
            "getInstance",
            "ApplicationHelperStub.java",
            -2,
        ),
        StackTraceElement(
            "android.app.NotificationManager",
            "notifyAsUser",
            "NotificationManager.java",
            -2,
        ),
        StackTraceElement(
            "androidx.work.impl.foreground.SystemForegroundService",
            "notify",
            "SystemForegroundService.java",
            -2,
        ),
    )

    /**
     * Verbatim frames of the `DiskReadViolation` from `ha-permission-crash.txt` (Redmi K70 Ultra,
     * HyperOS): when the system shows the permission dialog (`com.lbe.security.miui`), MIUI's
     * app-lock snapshot check runs `File.exists()` on the binder thread inside the app process
     * while the Activity is started (`System.exit called, status: 1` followed).
     */
    private val miuiAppLockSnapshotDiskRead = diskReadViolation(
        StackTraceElement("android.os.StrictMode\$AndroidBlockGuardPolicy", "onReadFromDisk", "StrictMode.java", 1666),
        StackTraceElement("libcore.io.BlockGuardOs", "access", "BlockGuardOs.java", 74),
        StackTraceElement("java.io.UnixFileSystem", "checkAccess", "UnixFileSystem.java", 313),
        StackTraceElement("java.io.File", "exists", "File.java", 813),
        StackTraceElement("com.miui.server.AccessController", "haveAccessControlPassword", "AccessController.java", 457),
        StackTraceElement("com.miui.server.SecurityManagerService", "haveAccessControlPassword", "SecurityManagerService.java", 522),
        StackTraceElement("com.android.server.wm.AppTransitionInjector", "disableSnapshotForApplock", "AppTransitionInjector.java", 1558),
        StackTraceElement("com.android.server.wm.ActivityRecordImpl", "allowTaskSnapshot", "ActivityRecordImpl.java", 533),
        StackTraceElement("com.android.server.wm.ActivityRecord", "addStartingWindow", "ActivityRecord.java", 2863),
        StackTraceElement("com.android.server.wm.Task", "startActivityLocked", "Task.java", 6104),
        StackTraceElement("com.android.server.wm.ActivityStarter", "execute", "ActivityStarter.java", 953),
        StackTraceElement("com.android.server.wm.ActivityTaskManagerService", "startActivity", "ActivityTaskManagerService.java", 1442),
        StackTraceElement("android.app.IActivityTaskManager\$Stub", "onTransact", "IActivityTaskManager.java", 1286),
        StackTraceElement("android.os.Binder", "execTransact", "Binder.java", 1282),
    )

    // region MediaTek boost framework

    @Test
    fun `Mediatek boost framework disk read is ignored`() {
        assertTrue(
            threadPolicyIgnoredViolationRules.any { it.shouldIgnore(mediatekBoostFwkTouchDiskRead) },
            "The vendor boost framework disk read must not be fatal",
        )
    }

    @Test
    fun `Mediatek boost framework disk write is ignored`() {
        assertTrue(
            threadPolicyIgnoredViolationRules.any { it.shouldIgnore(mediatekBoostFwkTouchDiskWrite) },
            "The vendor boost framework disk write must not be fatal",
        )
    }

    @Test
    fun `Exactly one rule covers the boost framework disk write`() {
        val matching = threadPolicyIgnoredViolationRules.filter { it.shouldIgnore(mediatekBoostFwkTouchDiskWrite) }
        assertEquals(1, matching.size, "Exactly one rule should match the boost framework disk write")
    }

    // endregion

    // region MIUI enterprise framework

    @Test
    fun `MIUI enterprise framework disk read is ignored`() {
        assertTrue(
            threadPolicyIgnoredViolationRules.any { it.shouldIgnore(miuiEnterpriseFrameworkDiskRead) },
            "The vendor enterprise framework disk read must not be fatal",
        )
    }

    @Test
    fun `MIUI enterprise framework disk write is still fatal`() {
        val enterpriseDiskWrite = diskWriteViolation(
            StackTraceElement("libcore.io.BlockGuardOs", "mkdir", "BlockGuardOs.java", -2),
            StackTraceElement(
                "miui.enterprise.EpFrameworkFactory",
                "isEnterpriseJarExists",
                "EpFrameworkFactory.java",
                -2,
            ),
            StackTraceElement(
                "android.app.NotificationManager",
                "notifyAsUser",
                "NotificationManager.java",
                -2,
            ),
        )
        assertFalse(
            threadPolicyIgnoredViolationRules.any { it.shouldIgnore(enterpriseDiskWrite) },
            "The MIUI enterprise rule must stay scoped to DiskReadViolation",
        )
    }

    // endregion

    // region MIUI app-lock snapshot check

    @Test
    fun `MIUI app-lock snapshot disk read is ignored`() {
        assertTrue(
            threadPolicyIgnoredViolationRules.any { it.shouldIgnore(miuiAppLockSnapshotDiskRead) },
            "The vendor app-lock snapshot disk read must not be fatal",
        )
    }

    @Test
    fun `MIUI app-lock snapshot rule does not ignore a disk write`() {
        val appLockDiskWrite = diskWriteViolation(
            StackTraceElement("libcore.io.BlockGuardOs", "write", "BlockGuardOs.java", -2),
            StackTraceElement("com.miui.server.AccessController", "haveAccessControlPassword", "AccessController.java", -2),
        )
        assertFalse(
            threadPolicyIgnoredViolationRules.any { it.shouldIgnore(appLockDiskWrite) },
            "The app-lock rule must stay scoped to DiskReadViolation",
        )
    }

    @Test
    fun `Exactly one rule covers the MIUI app-lock snapshot disk read`() {
        val matching = threadPolicyIgnoredViolationRules.filter { it.shouldIgnore(miuiAppLockSnapshotDiskRead) }
        assertEquals(
            1,
            matching.size,
            "Only the app-lock rule may match a com.miui.server. frame",
        )
    }

    // endregion

    // region The app's own code must stay fatal

    @Test
    fun `Disk read from the app's own code is still fatal`() {
        assertFalse(
            threadPolicyIgnoredViolationRules.any { it.shouldIgnore(appCodeDiskRead) },
            "A disk read with no vendor frame must keep triggering FailFast",
        )
    }

    @Test
    fun `Disk write from the app's own code is still fatal`() {
        assertFalse(
            threadPolicyIgnoredViolationRules.any { it.shouldIgnore(appCodeDiskWrite) },
            "A disk write with no vendor frame must keep triggering FailFast",
        )
    }

    // endregion

    // region Neither rule may widen beyond its own violation types

    @Test
    fun `Vendor rules do not ignore unrelated violation types`() {
        val notADiskViolation = mockk<Violation>()
        assertFalse(
            threadPolicyIgnoredViolationRules.any { it.shouldIgnore(notADiskViolation) },
            "The vendor rules must stay scoped to DiskRead/DiskWrite violations",
        )
    }

    @Test
    fun `Mediatek rule does not ignore a disk read from the MIUI enterprise framework`() {
        val onlyMediatek = threadPolicyIgnoredViolationRules.filter {
            it.shouldIgnore(miuiEnterpriseFrameworkDiskRead)
        }
        assertEquals(
            1,
            onlyMediatek.size,
            "Only the MIUI rule may match a miui.enterprise frame, not the MediaTek rule",
        )
    }

    @Test
    fun `MIUI enterprise rule does not ignore a boost framework disk read`() {
        val matching = threadPolicyIgnoredViolationRules.filter { it.shouldIgnore(mediatekBoostFwkTouchDiskRead) }
        assertEquals(
            1,
            matching.size,
            "Only the MediaTek rule may match a com.mediatek.boostfwk frame, not the MIUI rule",
        )
    }

    // endregion

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

    /** A disk write performed by the app's own code must stay fatal. */
    private val appCodeDiskWrite = diskWriteViolation(
        StackTraceElement("libcore.io.BlockGuardOs", "mkdir", "BlockGuardOs.java", -2),
        StackTraceElement(
            "io.homeassistant.companion.android.sensors.SensorReceiver",
            "onReceive",
            "SensorReceiver.kt",
            -2,
        ),
    )

    /**
     * `DiskReadViolation`'s constructor is package-private in the SDK stubs, so the violation is
     * mocked instead of built. Only [Violation.stackTrace] matters to the rules under test.
     */
    private fun diskReadViolation(vararg frames: StackTraceElement): DiskReadViolation = mockk {
        every { stackTrace } returns frames
    }

    /** Same, for `DiskWriteViolation`. */
    private fun diskWriteViolation(vararg frames: StackTraceElement): DiskWriteViolation = mockk {
        every { stackTrace } returns frames
    }
}
