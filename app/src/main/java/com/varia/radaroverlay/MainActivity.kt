package com.varia.radaroverlay

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.varia.radaroverlay.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var bleClient: VariaBleClient? = null
    
    private val scannedDevices = HashMap<String, BluetoothDevice>()
    private val scanHandler = Handler(Looper.getMainLooper())
    private var isScanning = false
    
    private var selectedMacAddress: String? = null

    private val serviceStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateServiceStatusUI()
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val SCAN_PERIOD_MS = 12000L
    }

    // Permission launcher for BLE/Location
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            checkOverlayAndScan()
        } else {
            Toast.makeText(this, "Permissions are required to scan for Varia Radar.", Toast.LENGTH_LONG).show()
        }
    }

    // Overlay permission launcher
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            updateServiceStatusUI()
        } else {
            Toast.makeText(this, "Overlay permission is required to show the radar display.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Load saved device MAC
        val prefs = getSharedPreferences(RadarService.PREFS_NAME, Context.MODE_PRIVATE)
        selectedMacAddress = prefs.getString(RadarService.KEY_MAC_ADDRESS, null)
        val audioEnabled = prefs.getBoolean(RadarService.KEY_AUDIO_ALERTS, true)
        
        updateSelectedDeviceText()

        // Init BLE client for scanning
        bleClient = VariaBleClient(this).apply {
            listener = object : VariaBleClient.Listener {
                override fun onConnectionStateChanged(state: VariaBleClient.ConnectionState) {}
                override fun onRadarStateUpdated(state: RadarState) {}
                override fun onDeviceFound(device: BluetoothDevice) {
                    addScannedDevice(device)
                }
            }
        }

        // Setup audio alert switch
        binding.switchAudioAlerts.isChecked = audioEnabled
        binding.switchAudioAlerts.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(RadarService.KEY_AUDIO_ALERTS, isChecked).apply()
            // If service is running, notify it (we can restart or just trigger intent)
            if (isServiceRunning(RadarService::class.java)) {
                val intent = Intent(this, RadarService::class.java)
                startService(intent)
            }
        }

        // Service Start/Stop Toggle Button
        binding.btnToggleService.setOnClickListener {
            toggleOverlayService()
        }

        // Scan button
        binding.btnScan.setOnClickListener {
            checkPermissionsAndScan()
        }

        updateServiceStatusUI()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(RadarService.ACTION_SERVICE_STATUS_CHANGED)
        ContextCompat.registerReceiver(
            this,
            serviceStatusReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        updateServiceStatusUI()
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatusUI()
    }

    override fun onStop() {
        unregisterReceiver(serviceStatusReceiver)
        super.onStop()
    }

    private fun checkPermissionsAndScan() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            checkOverlayAndScan()
        }
    }

    private fun checkOverlayAndScan() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
            return
        }

        startScanning()
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        if (isScanning) return
        
        isScanning = true
        scannedDevices.clear()
        binding.deviceListContainer.removeAllViews()
        binding.scanProgressBar.visibility = View.VISIBLE
        binding.btnScan.text = "Scanning..."
        binding.btnScan.isEnabled = false

        bleClient?.startScanning()

        // Scan for 12 seconds
        scanHandler.postDelayed({
            stopScanning()
        }, SCAN_PERIOD_MS)
    }

    private fun stopScanning() {
        if (!isScanning) return
        isScanning = false
        bleClient?.stopScanning()
        binding.scanProgressBar.visibility = View.GONE
        binding.btnScan.text = "Scan for Radar"
        binding.btnScan.isEnabled = true
        
        if (scannedDevices.isEmpty()) {
            Toast.makeText(this, "No Garmin Varia radar found", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun addScannedDevice(device: BluetoothDevice) {
        val address = device.address
        if (scannedDevices.containsKey(address)) return

        scannedDevices[address] = device
        
        val density = resources.displayMetrics.density
        
        // Build card programmatically for discovered device
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, (8 * density).toInt())
            }
            cardElevation = 0f
            radius = 12 * density
            setCardBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.divider))
            strokeWidth = if (address == selectedMacAddress) (2 * density).toInt() else 0
            strokeColor = ContextCompat.getColor(this@MainActivity, R.color.accent)
            isClickable = true
            isFocusable = true
            
            // Ripple effect
            val outValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            foreground = ContextCompat.getDrawable(context, outValue.resourceId)
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
        }

        val nameView = TextView(this).apply {
            text = device.name ?: "Garmin Varia Radar"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textSize = 16f
            paintFlags = paintFlags or android.graphics.Paint.SUBPIXEL_TEXT_FLAG
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val addressView = TextView(this).apply {
            text = address
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textSize = 12f
        }

        layout.addView(nameView)
        layout.addView(addressView)
        card.addView(layout)

        card.setOnClickListener {
            selectDevice(device)
            // Re-render outlines
            for (i in 0 until binding.deviceListContainer.childCount) {
                val child = binding.deviceListContainer.getChildAt(i) as? MaterialCardView
                child?.strokeWidth = 0
            }
            card.strokeWidth = (2 * density).toInt()
        }

        binding.deviceListContainer.addView(card)
    }

    private fun selectDevice(device: BluetoothDevice) {
        selectedMacAddress = device.address
        val prefs = getSharedPreferences(RadarService.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(RadarService.KEY_MAC_ADDRESS, selectedMacAddress).apply()
        
        updateSelectedDeviceText()
        Toast.makeText(this, "Selected: ${device.name ?: device.address}", Toast.LENGTH_SHORT).show()

        // If service is running, send command to connect to the new device
        if (isServiceRunning(RadarService::class.java)) {
            val intent = Intent(this, RadarService::class.java).apply {
                putExtra(RadarService.EXTRA_MAC_ADDRESS, selectedMacAddress)
            }
            startService(intent)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateSelectedDeviceText() {
        if (selectedMacAddress.isNullOrEmpty()) {
            binding.tvSelectedDevice.text = "Selected: None (Scan below)"
            binding.tvSelectedDevice.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        } else {
            binding.tvSelectedDevice.text = "Selected Radar MAC: $selectedMacAddress"
            binding.tvSelectedDevice.setTextColor(ContextCompat.getColor(this, R.color.accent))
        }
    }

    private fun toggleOverlayService() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
            return
        }

        if (selectedMacAddress.isNullOrEmpty()) {
            Toast.makeText(this, "Please scan and select your Varia radar device first.", Toast.LENGTH_LONG).show()
            return
        }

        val serviceIntent = Intent(this, RadarService::class.java).apply {
            putExtra(RadarService.EXTRA_MAC_ADDRESS, selectedMacAddress)
        }

        if (isServiceRunning(RadarService::class.java)) {
            stopService(serviceIntent)
            Toast.makeText(this, "Overlay Service Stopped", Toast.LENGTH_SHORT).show()
        } else {
            ContextCompat.startForegroundService(this, serviceIntent)
            Toast.makeText(this, "Overlay Service Started", Toast.LENGTH_SHORT).show()
        }

        // Wait a brief moment to update status
        scanHandler.postDelayed({
            updateServiceStatusUI()
        }, 300)
    }

    @SuppressLint("SetTextI18n")
    private fun updateServiceStatusUI() {
        val running = isServiceRunning(RadarService::class.java)
        if (running) {
            binding.tvServiceStatus.text = "RUNNING"
            binding.tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.threat_clear))
            binding.btnToggleService.text = "Stop Overlay Service"
            binding.btnToggleService.setBackgroundColor(ContextCompat.getColor(this, R.color.threat_high))
        } else {
            binding.tvServiceStatus.text = "STOPPED"
            binding.tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
            binding.btnToggleService.text = "Start Overlay Service"
            binding.btnToggleService.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
        }
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        if (serviceClass.name == RadarService::class.java.name) {
            return RadarService.isRunning
        }
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    override fun onDestroy() {
        scanHandler.removeCallbacksAndMessages(null)
        if (isScanning) {
            bleClient?.stopScanning()
        }
        bleClient = null
        super.onDestroy()
    }
}
