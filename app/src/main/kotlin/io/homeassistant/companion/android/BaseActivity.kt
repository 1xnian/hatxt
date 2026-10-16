package io.homeassistant.companion.android

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.homeassistant.companion.android.common.util.isAutomotive
import io.homeassistant.companion.android.util.PermissionRequestMediator
import io.homeassistant.companion.android.util.enableEdgeToEdgeCompat
import javax.inject.Inject
import kotlin.math.absoluteValue
import kotlinx.coroutines.launch
import timber.log.Timber

@AndroidEntryPoint
open class BaseActivity : AppCompatActivity() {

    @Inject
    lateinit var permissionRequestMediator: PermissionRequestMediator

    private var car: Car? = null
    private var carRestrictionManager: CarUxRestrictionsManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdgeCompat()
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                permissionRequestMediator.eventFlow.collect { permissionToRequest ->
                    Timber.i(
                        "PermissionDiag: requesting %s from %s (resumed=%s, finishing=%s)",
                        permissionToRequest,
                        this@BaseActivity.javaClass.simpleName,
                        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                        isFinishing,
                    )
                    ActivityCompat.requestPermissions(
                        this@BaseActivity,
                        arrayOf(permissionToRequest),
                        permissionToRequest.hashCode().absoluteValue,
                    )
                }
            }
        }
    }

    /**
     * Diagnostic hook: nothing in the app currently consumes the result of the legacy
     * `requestPermissions` path, so the outcome of a system permission dialog is invisible in
     * logs. Log the result (no sensitive data — permission name and grant state only) so a
     * device run can show whether the dialog was answered and whether the process survived it.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val result = permissions.indices.joinToString { i ->
            "${permissions[i]}=${if (grantResults[i] == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"}"
        }
        Timber.i("PermissionDiag: result for request %d -> %s", requestCode, result)
    }

    override fun onResume() {
        super.onResume()
        Timber.d("PermissionDiag: %s onResume (finishing=%s)", javaClass.simpleName, isFinishing)
        registerListener()
    }

    override fun onPause() {
        super.onPause()
        Timber.d("PermissionDiag: %s onPause (finishing=%s)", javaClass.simpleName, isFinishing)
    }

    override fun onStop() {
        super.onStop()
        Timber.d("PermissionDiag: %s onStop (finishing=%s)", javaClass.simpleName, isFinishing)
        carRestrictionManager?.unregisterListener()
        car?.disconnect()
        car = null
    }

    private fun registerListener() {
        if (isAutomotive()) {
            car = Car.createCar(this)
            carRestrictionManager =
                car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            val listener =
                CarUxRestrictionsManager.OnUxRestrictionsChangedListener { restrictions ->
                    if (restrictions.isRequiresDistractionOptimization) {
                        startCarAppActivity()
                    }
                }
            carRestrictionManager?.registerListener(listener)
        }
    }

    private fun startCarAppActivity() {
        startActivity(
            Intent(
                this,
                Class.forName("androidx.car.app.activity.CarAppActivity"),
            ).putExtra("TRANSITION_LAUNCH", true).addFlags(FLAG_ACTIVITY_NEW_TASK),
        )
        overridePendingTransition(
            androidx.appcompat.R.anim.abc_slide_in_bottom,
            androidx.appcompat.R.anim.abc_slide_in_bottom,
        )
    }
}
