package io.homeassistant.companion.android

import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.telephony.TelephonyManager
import android.webkit.WebView
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.network.NetworkClient
import coil3.network.NetworkFetcher
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.okhttp.asNetworkClient
import dagger.hilt.android.HiltAndroidApp
import io.homeassistant.companion.android.common.data.endpoint.EndpointForegroundRefresher
import io.homeassistant.companion.android.common.data.endpoint.EndpointRefreshWorker
import io.homeassistant.companion.android.common.data.prefs.PrefsRepository
import io.homeassistant.companion.android.common.data.servers.ServerManager
import io.homeassistant.companion.android.common.sensors.AudioSensorManager
import io.homeassistant.companion.android.common.sensors.LastUpdateManager
import io.homeassistant.companion.android.common.sensors.SensorRepository
import io.homeassistant.companion.android.common.util.HAStrictMode
import io.homeassistant.companion.android.common.util.SdkVersion
import io.homeassistant.companion.android.common.util.configureComposeDiagnosticStackTrace
import io.homeassistant.companion.android.common.util.di.SuspendProvider
import io.homeassistant.companion.android.common.util.isAutomotive
import io.homeassistant.companion.android.database.settings.SensorUpdateFrequencySetting
import io.homeassistant.companion.android.database.settings.SettingsDao
import io.homeassistant.companion.android.sensors.SensorReceiver
import io.homeassistant.companion.android.settings.language.LanguagesManager
import io.homeassistant.companion.android.settings.shortcuts.HaShortcutManager
import io.homeassistant.companion.android.themes.NightModeManager
import io.homeassistant.companion.android.util.LifecycleHandler
import io.homeassistant.companion.android.util.QuestUtil
import io.homeassistant.companion.android.util.initCrashSaving
import io.homeassistant.companion.android.util.threadPolicyIgnoredViolationRules
import io.homeassistant.companion.android.util.vmPolicyIgnoredViolationRules
import io.homeassistant.companion.android.websocket.WebsocketBroadcastReceiver
import io.homeassistant.companion.android.widgets.button.ButtonWidget
import io.homeassistant.companion.android.widgets.entity.EntityWidget
import io.homeassistant.companion.android.widgets.mediaplayer.MediaPlayerControlsWidget
import io.homeassistant.companion.android.widgets.template.TemplateWidget
import io.homeassistant.companion.android.widgets.todo.TodoWidget
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber

@HiltAndroidApp
open class HomeAssistantApplication : Application() {

    private val ioScope: CoroutineScope = CoroutineScope(Dispatchers.IO + Job())

    @Inject
    lateinit var prefsRepository: PrefsRepository

    @Inject
    lateinit var okHttpClientProvider: SuspendProvider<OkHttpClient>

    @Inject
    lateinit var languagesManager: LanguagesManager

    @Inject
    lateinit var nightModeManager: NightModeManager

    @Inject
    lateinit var sensorRepository: SensorRepository

    @Inject
    lateinit var settingsDao: SettingsDao

    @Inject
    internal lateinit var shortcutManager: HaShortcutManager

    @Inject
    internal lateinit var endpointForegroundRefresher: EndpointForegroundRefresher

    @Inject
    internal lateinit var serverManager: ServerManager

    /**
     * Number of activities between `onActivityStarted` and `onActivityStopped`.
     *
     * Used instead of a `ProcessLifecycleOwner` observer so no new dependency is introduced. Reaching
     * 0 means the app left the foreground, 1 means it just came back.
     */
    private val startedActivities = AtomicInteger(0)

    override fun onCreate() {
        // We should initialize the logger as early as possible in the lifecycle of the application
        Timber.plant(Timber.DebugTree())
        val networkClient = CompletableDeferred<NetworkClient>()
        // Register the image loader as early as possible, before anything (e.g. a widget) can request
        // an image: the first Coil access creates the singleton, and if its default loader is created
        // first, initializeCoil() crashes when it calls setSafe().
        initializeCoil { networkClient.await() }

        super.onCreate()

        if (SdkVersion.isAtLeast(Build.VERSION_CODES.S) &&
            BuildConfig.DEBUG &&
            !BuildConfig.NO_STRICT_MODE
        ) {
            HAStrictMode.enable(
                vmPolicyIgnoredViolationRules = vmPolicyIgnoredViolationRules,
                threadPolicyIgnoredViolationRules = threadPolicyIgnoredViolationRules,
            )
        }

        Timber.i("Running ${BuildConfig.VERSION_NAME} on SDK $SdkVersion")
        // Startup diagnostic: every fresh Application instance prints one line with the PID.
        // If the app "returns to the desktop" and the next log line comes from a *new* PID with
        // no crash/FailFast in between, the process was killed externally (e.g. MIUI security
        // centre). If the same PID logs an exit first, it is the app itself leaving.
        Timber.i(
            "PermissionDiag: process start pid=%d pkg=%s debug=%s",
            android.os.Process.myPid(),
            BuildConfig.APPLICATION_ID,
            BuildConfig.DEBUG,
        )

        registerActivityLifecycleCallbacks(LifecycleHandler)
        registerEndpointRefreshLifecycle()

        ioScope.launch {
            initCrashReporting(
                applicationContext,
                prefsRepository.isCrashReporting(),
            )
            initCrashSaving(applicationContext)
            val okHttpClient = okHttpClientProvider()
            networkClient.complete(okHttpClient.asNetworkClient())

            configureWebViewDebugging(enabled = BuildConfig.DEBUG || prefsRepository.isWebViewDebugEnabled())

            languagesManager.applyCurrentLang()
            nightModeManager.applyCurrentNightMode()
            shortcutManager.migrateLegacyShortcuts()
        }

        configureComposeDiagnosticStackTrace(isDebug = BuildConfig.DEBUG)

        // This will make sure we start/stop when we actually need too.
        ContextCompat.registerReceiver(
            this,
            WebsocketBroadcastReceiver(),
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )

        val sensorReceiver = SensorReceiver()
        // This will cause the sensor to be updated every time the OS broadcasts that a cable was plugged/unplugged.
        // This should be nearly instantaneous allowing automations to fire immediately when a phone is plugged
        // in or unplugged. Updates will also be triggered when the system reports low battery and when it recovers.
        ContextCompat.registerReceiver(
            this,
            sensorReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_LOW)
                addAction(Intent.ACTION_BATTERY_OKAY)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )

        // This will cause interactive and power save to update upon a state change
        ContextCompat.registerReceiver(
            this,
            sensorReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )

        // Update Quest only sensors when the device is a Quest
        if (QuestUtil.isQuest) {
            ContextCompat.registerReceiver(
                this,
                sensorReceiver,
                IntentFilter().apply {
                    addAction("com.oculus.intent.action.MOUNT_STATE_CHANGED")
                },
                ContextCompat.RECEIVER_EXPORTED,
            )
        }

        // Update doze mode immediately on supported devices
        ContextCompat.registerReceiver(
            this,
            sensorReceiver,
            IntentFilter().apply {
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )

        // This will trigger an update any time the wifi state has changed
        ContextCompat.registerReceiver(
            this,
            sensorReceiver,
            IntentFilter().apply {
                addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
            },
            ContextCompat.RECEIVER_EXPORTED,
        )

        // This will cause the phone state sensor to be updated every time the OS broadcasts that a call triggered.
        ContextCompat.registerReceiver(
            this,
            sensorReceiver,
            IntentFilter().apply {
                addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )

        // Listen for bluetooth state changes
        ContextCompat.registerReceiver(
            this,
            sensorReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )

        // Listen for NFC state changes
        ContextCompat.registerReceiver(
            this,
            sensorReceiver,
            IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )

        // Listen to changes to the audio input/output on the device
        ContextCompat.registerReceiver(
            this,
            sensorReceiver,
            IntentFilter().apply {
                addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                addAction(AudioManager.ACTION_HEADSET_PLUG)
                addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
                addAction(AudioSensorManager.VOLUME_CHANGED_ACTION)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )

        // Listen for microphone mute changes
        if (SdkVersion.isAtLeast(Build.VERSION_CODES.P)) {
            ContextCompat.registerReceiver(
                this,
                sensorReceiver,
                IntentFilter(AudioManager.ACTION_MICROPHONE_MUTE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED,
            )
        }

        // Listen for speakerphone state changes
        if (SdkVersion.isAtLeast(Build.VERSION_CODES.Q)) {
            ContextCompat.registerReceiver(
                this,
                sensorReceiver,
                IntentFilter(AudioManager.ACTION_SPEAKERPHONE_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED,
            )
        }

        // Add receiver for DND changes on devices that support it
        ContextCompat.registerReceiver(
            this,
            sensorReceiver,
            IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )

        ContextCompat.registerReceiver(
            this,
            sensorReceiver,
            IntentFilter("androidx.car.app.connection.action.CAR_CONNECTION_UPDATED"),
            ContextCompat.RECEIVER_EXPORTED,
        )

        // Add a receiver for the shutdown event to attempt to send 1 final sensor update
        ContextCompat.registerReceiver(
            this,
            sensorReceiver,
            IntentFilter(Intent.ACTION_SHUTDOWN),
            ContextCompat.RECEIVER_EXPORTED,
        )

        // Register for all saved user intents
        ioScope.launch {
            val allSettings = sensorRepository.getSettings(LastUpdateManager.lastUpdate.id)
            for (setting in allSettings) {
                if (setting.value != "" && setting.value != "SensorWorker") {
                    val settingSplit = setting.value.split(',')
                    ContextCompat.registerReceiver(
                        this@HomeAssistantApplication,
                        sensorReceiver,
                        IntentFilter().apply {
                            addAction(settingSplit[0])
                            if (settingSplit.size > 1) {
                                val categories = settingSplit.minus(settingSplit[0])
                                categories.forEach { addCategory(it) }
                            }
                        },
                        ContextCompat.RECEIVER_EXPORTED,
                    )
                }
            }
        }

        // Register for changes to the managed profile availability
        if (SdkVersion.isAtLeast(Build.VERSION_CODES.N)) {
            ContextCompat.registerReceiver(
                this,
                sensorReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
                    addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
                },
                ContextCompat.RECEIVER_EXPORTED,
            )
        }

        // Register for faster sensor updates if enabled
        ioScope.launch {
            // 0 is used for storing app level settings
            val settings = settingsDao.get(0)
            if (settings != null &&
                (
                    settings.sensorUpdateFrequency == SensorUpdateFrequencySetting.FAST_WHILE_CHARGING ||
                        settings.sensorUpdateFrequency == SensorUpdateFrequencySetting.FAST_ALWAYS
                    )
            ) {
                ContextCompat.registerReceiver(
                    this@HomeAssistantApplication,
                    sensorReceiver,
                    IntentFilter(Intent.ACTION_TIME_TICK),
                    ContextCompat.RECEIVER_EXPORTED,
                )
            }
        }

        // Register for changes to the configuration
        ContextCompat.registerReceiver(
            this,
            sensorReceiver,
            IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )

        if (!isAutomotive()) {
            // Update widgets when the screen turns on, updates are skipped if widgets were not added.
            //
            // Deferred until the first activity has been resumed. `onCreate` already registers a
            // couple of dozen receivers on the main thread, and these four only matter once the
            // user can actually see a widget on the home screen. Moving them off the startup path
            // shortens the window between `am_proc_start` and the first drawn frame, which is the
            // measurement that matters for perceived launch time. Nothing depends on the widgets
            // being registered before the first activity is up: widget updates are driven by
            // `ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF` and by explicit broadcasts.
            registerActivityLifecycleCallbacks(
                object : Application.ActivityLifecycleCallbacks {
                    private var registered = false

                    override fun onActivityResumed(activity: Activity) {
                        if (registered) return
                        registered = true
                        unregisterActivityLifecycleCallbacks(this)

                        val buttonWidget = ButtonWidget()
                        val entityWidget = EntityWidget()
                        val mediaPlayerWidget = MediaPlayerControlsWidget()
                        val templateWidget = TemplateWidget()
                        TodoWidget().register(this@HomeAssistantApplication)

                        val screenIntentFilter = IntentFilter()
                        screenIntentFilter.addAction(Intent.ACTION_SCREEN_ON)
                        screenIntentFilter.addAction(Intent.ACTION_SCREEN_OFF)

                        ContextCompat.registerReceiver(
                            this@HomeAssistantApplication,
                            buttonWidget,
                            screenIntentFilter,
                            ContextCompat.RECEIVER_NOT_EXPORTED,
                        )
                        ContextCompat.registerReceiver(
                            this@HomeAssistantApplication,
                            entityWidget,
                            screenIntentFilter,
                            ContextCompat.RECEIVER_NOT_EXPORTED,
                        )
                        ContextCompat.registerReceiver(
                            this@HomeAssistantApplication,
                            mediaPlayerWidget,
                            screenIntentFilter,
                            ContextCompat.RECEIVER_NOT_EXPORTED,
                        )
                        ContextCompat.registerReceiver(
                            this@HomeAssistantApplication,
                            templateWidget,
                            screenIntentFilter,
                            ContextCompat.RECEIVER_NOT_EXPORTED,
                        )
                    }

                    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                    override fun onActivityStarted(activity: Activity) = Unit
                    override fun onActivityPaused(activity: Activity) = Unit
                    override fun onActivityStopped(activity: Activity) = Unit
                    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                    override fun onActivityDestroyed(activity: Activity) = Unit
                },
            )
        }
    }

    /**
     * Keeps the dynamic endpoint refresher in step with the app being visible.
     *
     * While the app is in the foreground the configured interval is honoured exactly, including the
     * short intervals that Android's background scheduler cannot deliver. When the app leaves the
     * foreground the loop stops and the periodic `WorkManager` job takes over at its own, slower
     * pace. `start()` and `stop()` are idempotent, so a configuration change that recreates the
     * activity cannot create a second timer.
     */
    private fun registerEndpointRefreshLifecycle() {
        registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                /**
                 * Whether the periodic `WorkManager` job has already been reconciled once.
                 *
                 * [scheduleEndpointBackgroundRefresh] reads the server list and the endpoint
                 * settings (SharedPreferences) and then enqueues or cancels a periodic worker.
                 * Reconfiguring it on every foreground transition was pointless: the settings can
                 * only change from the settings screens, and those already re-schedule explicitly.
                 */
                private var backgroundRefreshScheduled = false

                override fun onActivityStarted(activity: Activity) {
                    val visible = startedActivities.incrementAndGet()
                    if (visible != 1) return
                    endpointForegroundRefresher.start()
                    // Re-check the (possibly changed) dynamic address on every return to the
                    // foreground. The resolver is single-flight, so a burst of transitions during
                    // first-launch permission prompts is coalesced into one lookup, and a changed
                    // TXT/SRV record is picked up immediately instead of waiting for the interval.
                    endpointForegroundRefresher.refreshNow()
                    if (!backgroundRefreshScheduled) {
                        backgroundRefreshScheduled = true
                        ioScope.launch { scheduleEndpointBackgroundRefresh() }
                    }
                }

                override fun onActivityStopped(activity: Activity) {
                    if (startedActivities.decrementAndGet() <= 0) {
                        startedActivities.set(0)
                        endpointForegroundRefresher.stop()
                    }
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    private suspend fun scheduleEndpointBackgroundRefresh() {
        val server = serverManager.getServer() ?: return
        val settings = serverManager.endpointRepository(server.id).settings()
        if (settings.periodicRefreshEnabled && settings.mode.usesDnsRecord) {
            EndpointRefreshWorker.start(this, settings.refreshIntervalSeconds)
        } else {
            EndpointRefreshWorker.stop(this)
        }
    }

    /**
     * Enables WebView contents debugging and logs the current WebView package.
     *
     * Runs on the main thread because [WebView.setWebContentsDebuggingEnabled] requires it in
     * release builds.
     */
    private suspend fun configureWebViewDebugging(enabled: Boolean) = withContext(Dispatchers.Main) {
        WebView.setWebContentsDebuggingEnabled(enabled)
        if (SdkVersion.isAtLeast(Build.VERSION_CODES.O)) {
            val webviewPackage = WebViewCompat.getCurrentWebViewPackage(this@HomeAssistantApplication)
            Timber.d(
                "Current webview package ${webviewPackage?.packageName} and version ${webviewPackage?.versionName}",
            )
        }
    }
}

@OptIn(ExperimentalCoilApi::class)
private fun Context.initializeCoil(networkClient: suspend () -> NetworkClient) {
    SingletonImageLoader.setSafe {
        ImageLoader.Builder(this)
            .components { add(NetworkFetcher.Factory(networkClient = { deferredNetworkClient { networkClient() } })) }
            .build()
    }
}

private fun deferredNetworkClient(client: suspend () -> NetworkClient): NetworkClient = object : NetworkClient {
    override suspend fun <T> executeRequest(
        request: NetworkRequest,
        block: suspend (response: NetworkResponse) -> T,
    ): T = client().executeRequest(request, block)
}
