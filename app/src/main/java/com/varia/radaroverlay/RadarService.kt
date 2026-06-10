package com.varia.radaroverlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import android.content.res.Configuration

class RadarService : LifecycleService() {

    private lateinit var windowManager: WindowManager
    private var overlayView: RadarOverlayView? = null
    private var bleClient: VariaBleClient? = null
    
    private var lastState = RadarState()
    private var knownVehicleIds = HashSet<Int>()
    private var toneGenerator: ToneGenerator? = null
    
    private var targetMacAddress: String? = null
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var isDestroyed = false
    private var enableAudioAlerts = true

    companion object {
        private const val TAG = "RadarService"
        private const val CHANNEL_ID = "VariaRadarOverlayChannel"
        private const val NOTIFICATION_ID = 4820
        private const val RECONNECT_DELAY_MS = 8000L
        
        const val EXTRA_MAC_ADDRESS = "extra_mac_address"
        const val PREFS_NAME = "VariaPrefs"
        const val KEY_MAC_ADDRESS = "varia_mac_address"
        const val KEY_AUDIO_ALERTS = "varia_audio_alerts"
        const val ACTION_SERVICE_STATUS_CHANGED = "com.varia.radaroverlay.ACTION_SERVICE_STATUS_CHANGED"

        @Volatile
        var isRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ToneGenerator", e)
        }

        // Load settings
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        targetMacAddress = prefs.getString(KEY_MAC_ADDRESS, null)
        enableAudioAlerts = prefs.getBoolean(KEY_AUDIO_ALERTS, true)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Searching for radar..."))
        
        showOverlay()
        initBleClient()
        sendBroadcast(Intent(ACTION_SERVICE_STATUS_CHANGED).setPackage(packageName))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        val newMac = intent?.getStringExtra(EXTRA_MAC_ADDRESS)
        if (newMac != null && newMac != targetMacAddress) {
            targetMacAddress = newMac
            // Save in prefs
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_MAC_ADDRESS, newMac)
                .apply()
            
            // Re-init connection
            bleClient?.disconnect()
            reconnectHandler.removeCallbacksAndMessages(null)
            connectToRadar()
        }

        return START_STICKY
    }

    private fun initBleClient() {
        bleClient = VariaBleClient(this).apply {
            listener = object : VariaBleClient.Listener {
                override fun onConnectionStateChanged(state: VariaBleClient.ConnectionState) {
                    val statusText = when (state) {
                        VariaBleClient.ConnectionState.DISCONNECTED -> {
                            updateNotification(buildNotification("Disconnected. Retrying..."))
                            scheduleReconnect()
                            "Disconnected"
                        }
                        VariaBleClient.ConnectionState.CONNECTING -> {
                            updateNotification(buildNotification("Connecting..."))
                            "Connecting"
                        }
                        VariaBleClient.ConnectionState.CONNECTED -> {
                            updateNotification(buildNotification("Connected to Varia Radar"))
                            reconnectHandler.removeCallbacksAndMessages(null)
                            "Connected"
                        }
                    }
                    Log.d(TAG, "BLE Connection State: $statusText")
                }

                override fun onRadarStateUpdated(state: RadarState) {
                    lastState = state
                    overlayView?.updateRadarState(state)
                    checkNewThreats(state)
                }

                override fun onDeviceFound(device: BluetoothDevice) {
                    // Not scanning in service, activity handles scanning
                }
            }
        }
        
        connectToRadar()
    }

    private fun connectToRadar() {
        val mac = targetMacAddress
        if (mac.isNullOrEmpty()) {
            updateNotification(buildNotification("Please select a device in app settings"))
            return
        }
        
        bleClient?.connect(mac)
    }

    private fun scheduleReconnect() {
        if (isDestroyed) return
        reconnectHandler.removeCallbacksAndMessages(null)
        reconnectHandler.postDelayed({
            if (bleClient?.connectionState == VariaBleClient.ConnectionState.DISCONNECTED) {
                Log.d(TAG, "Attempting periodic reconnect...")
                connectToRadar()
            }
        }, RECONNECT_DELAY_MS)
    }

    private fun checkNewThreats(state: RadarState) {
        val currentIds = state.vehicles.map { it.id }.toSet()
        val newVehicles = state.vehicles.filter { it.id !in knownVehicleIds }
        
        if (newVehicles.isNotEmpty() && enableAudioAlerts) {
            // Check if any new vehicle is approaching rapidly (high threat)
            val hasHighThreat = newVehicles.any { vehicle ->
                vehicle.relativeSpeedMs > 10.0 || (vehicle.distanceM < 30 && vehicle.relativeSpeedMs > 5.0)
            }
            
            playAlertTone(hasHighThreat)
        }
        
        knownVehicleIds.clear()
        knownVehicleIds.addAll(currentIds)
    }

    private fun playAlertTone(highThreat: Boolean) {
        try {
            if (highThreat) {
                // High Threat: Two rapid high-pitched beeps
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                Handler(Looper.getMainLooper()).postDelayed({
                    toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                }, 180)
            } else {
                // Medium Threat: Single warning beep
                toneGenerator?.startTone(ToneGenerator.TONE_CDMA_PIP, 200)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing alert tone", e)
        }
    }

    private fun showOverlay() {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val density = displayMetrics.density
        
        val widthPx = (65 * density).toInt()
        val heightPx = (300 * density).toInt()

        val params = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Position on right-center side of screen by default
            x = screenWidth - widthPx - (16 * density).toInt()
            y = (screenHeight - heightPx) / 2
        }

        overlayView = RadarOverlayView(this, windowManager, params)
        windowManager.addView(overlayView, params)
    }

    private fun removeOverlay() {
        overlayView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay", e)
            }
        }
        overlayView = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Garmin Varia background radar display"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_compass) // Standard fallback icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        isRunning = false
        isDestroyed = true
        reconnectHandler.removeCallbacksAndMessages(null)
        
        bleClient?.disconnect()
        bleClient = null
        
        removeOverlay()
        
        toneGenerator?.release()
        toneGenerator = null
        
        sendBroadcast(Intent(ACTION_SERVICE_STATUS_CHANGED).setPackage(packageName))
        
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlayView?.applyPositionForOrientation(newConfig.orientation)
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}
