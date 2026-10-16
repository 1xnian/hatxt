package io.homeassistant.companion.android.common.util

import android.os.Build
import android.os.StrictMode
import android.os.strictmode.Violation
import androidx.annotation.RequiresApi
import java.util.concurrent.Executors
import timber.log.Timber

/**
 * Interface for rules that can be used to ignore specific StrictMode violations.
 * This allows to skip well known violations that we cannot do anything about.
 */
interface IgnoreViolationRule {
    fun shouldIgnore(violation: Violation): Boolean
}

object HAStrictMode {
    /**
     * This method enable strict mode (VM and Thread policies) with a custom configuration that is
     * specific to the Home Assistant application.
     *
     * The thread policy is configured to detect all potential issues and log them.
     *
     * If a violation is detected by the VM policy or the thread policy, the application will [FailFast] by default.
     * However, you can provide a list of [IgnoreViolationRule] instances to ignore specific violations.
     *
     * @see android.os.StrictMode
     */
    @RequiresApi(Build.VERSION_CODES.S)
    fun enable(
        vmPolicyIgnoredViolationRules: List<IgnoreViolationRule> = emptyList(),
        threadPolicyIgnoredViolationRules: List<IgnoreViolationRule> = emptyList(),
    ) {
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectIncorrectContextUse()
                .detectUnsafeIntentLaunch()
                .detectLeakedRegistrationObjects()
                .penaltyListener(Executors.newSingleThreadExecutor()) { violation ->
                    val matched = vmPolicyIgnoredViolationRules.filter { it.shouldIgnore(violation) }
                    if (matched.isEmpty()) {
                        Timber.e(
                            "StrictModeDiag: FATAL VM violation %s on thread %s (no rule matched) — entering FailFast",
                            violation.javaClass.simpleName,
                            Thread.currentThread().name,
                        )
                        FailFast.failWith(violation)
                    } else {
                        Timber.w(
                            "StrictModeDiag: ignored VM violation %s on thread %s via rule %s",
                            violation.javaClass.simpleName,
                            Thread.currentThread().name,
                            matched.first()::class.java.simpleName,
                        )
                    }
                }
                .build(),
        )

        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyListener(Executors.newSingleThreadExecutor()) { violation ->
                    val matched = threadPolicyIgnoredViolationRules.filter { it.shouldIgnore(violation) }
                    if (matched.isEmpty()) {
                        Timber.e(
                            "StrictModeDiag: FATAL thread violation %s on thread %s (no rule matched) — entering FailFast",
                            violation.javaClass.simpleName,
                            Thread.currentThread().name,
                        )
                        FailFast.failWith(violation)
                    } else {
                        Timber.w(
                            "StrictModeDiag: ignored thread violation %s on thread %s via rule %s",
                            violation.javaClass.simpleName,
                            Thread.currentThread().name,
                            matched.first()::class.java.simpleName,
                        )
                    }
                }
                .build(),
        )
    }
}
