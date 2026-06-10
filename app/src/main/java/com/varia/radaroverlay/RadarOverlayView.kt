package com.varia.radaroverlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.GestureDetector
import android.view.ContextThemeWrapper
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration

@SuppressLint("ViewConstructor")
class RadarOverlayView(
    context: Context,
    private val windowManager: WindowManager,
    private val layoutParams: WindowManager.LayoutParams
) : View(context) {

    private var radarState = RadarState()
    private val dpScale = context.resources.displayMetrics.density

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            showExitDialog()
        }
    })

    // Drawing paints
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E60C1322") // Glassmorphic translucent dark background
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2.5f)
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4B5563")
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2f)
        pathEffect = DashPathEffect(floatArrayOf(dpToPx(6f), dpToPx(6f)), 0f)
    }

    private val cyclistOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#06B6D4") // Glowing Cyan for rider
        style = Paint.Style.FILL
    }

    private val cyclistInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val vehiclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dpToPx(10f)
        textAlign = Paint.Align.CENTER
        style = Paint.Style.FILL
        strokeWidth = dpToPx(1f)
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9CA3AF")
        textSize = dpToPx(8f)
        textAlign = Paint.Align.CENTER
    }

    // Positions tracking for smooth animations
    private val vehicleAnimations = HashMap<Int, Float>() // Map of Vehicle ID -> Animated Y pos

    // Pulsing animation for High Threat (Red alert)
    private var pulseGlowRadius = dpToPx(6f)
    private var pulseAnimator: ValueAnimator? = null

    init {
        // Disable hardware acceleration to allow setShadowLayer glow effects
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        startPulseAnimation()
        applyPositionForOrientation(context.resources.configuration.orientation)
    }

    private fun startPulseAnimation() {
        pulseAnimator = ValueAnimator.ofFloat(dpToPx(4f), dpToPx(10f)).apply {
            duration = 1000
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animator ->
                pulseGlowRadius = animator.animatedValue as Float
                postInvalidate()
            }
            start()
        }
    }

    fun updateRadarState(state: RadarState) {
        this.radarState = state
        postInvalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator?.cancel()
    }

    private fun dpToPx(dp: Float): Float {
        return dp * dpScale
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val rect = RectF(dpToPx(4f), dpToPx(4f), w - dpToPx(4f), h - dpToPx(4f))
        val radius = dpToPx(16f)

        // 1. Draw Translucent Dark Background
        canvas.drawRoundRect(rect, radius, radius, bgPaint)

        // 2. Draw Glow Border based on Threat Level
        val threat = radarState.threatLevel
        val borderColor = when (threat) {
            ThreatLevel.CLEAR -> Color.parseColor("#10B981") // Glowing green
            ThreatLevel.MEDIUM -> Color.parseColor("#F59E0B") // Glowing amber
            ThreatLevel.HIGH -> Color.parseColor("#EF4444") // Glowing red
            ThreatLevel.OFFLINE -> Color.parseColor("#4B5563") // Gray
        }
        
        borderPaint.color = borderColor
        if (threat != ThreatLevel.OFFLINE) {
            val glowRadius = if (threat == ThreatLevel.HIGH) pulseGlowRadius else dpToPx(6f)
            borderPaint.setShadowLayer(glowRadius, 0f, 0f, borderColor)
        } else {
            borderPaint.clearShadowLayer()
        }
        canvas.drawRoundRect(rect, radius, radius, borderPaint)

        // 3. Draw Track
        val topPadding = dpToPx(45f)
        val bottomPadding = dpToPx(35f)
        val trackHeight = h - topPadding - bottomPadding
        val centerX = w / 2f
        canvas.drawLine(centerX, topPadding, centerX, h - bottomPadding, trackPaint)

        // 4. Draw Rider (Cyclist) at the top of the track (0 meters)
        canvas.drawCircle(centerX, topPadding, dpToPx(7f), cyclistOuterPaint)
        canvas.drawCircle(centerX, topPadding, dpToPx(3f), cyclistInnerPaint)

        // 5. Draw battery/connection text at the top
        val statusText = when (threat) {
            ThreatLevel.OFFLINE -> "OFFLINE"
            else -> {
                if (radarState.batteryPercent >= 0) "${radarState.batteryPercent}%" else "ACTIVE"
            }
        }
        labelPaint.color = if (threat == ThreatLevel.OFFLINE) Color.parseColor("#9CA3AF") else Color.parseColor("#06B6D4")
        canvas.drawText(statusText, centerX, dpToPx(22f), labelPaint)

        // 6. Draw Approaching Vehicles
        val maxRadarDistance = 140.0 // Garmin radar tracks up to 140 meters
        
        val activeIds = HashSet<Int>()

        for (vehicle in radarState.vehicles) {
            activeIds.add(vehicle.id)

            // Target Y pos: 0m is at cyclist (topPadding), 140m is at bottom of track
            val clampedDist = vehicle.distanceM.coerceAtMost(maxRadarDistance.toInt())
            val targetY = topPadding + ((clampedDist / maxRadarDistance) * trackHeight).toFloat()

            // Smoothly interpolate current Y position towards target Y position (60fps slide)
            val currentY = vehicleAnimations[vehicle.id]
            val animatedY = if (currentY == null) {
                targetY // First frame, snap to target
            } else {
                currentY + (targetY - currentY) * 0.18f // Lerp
            }
            vehicleAnimations[vehicle.id] = animatedY

            // Draw vehicle pill shape
            // Red if closing fast, Amber if normal
            val vehColor = if (vehicle.relativeSpeedMs > 10.0 || (vehicle.distanceM < 30 && vehicle.relativeSpeedMs > 5.0)) {
                Color.parseColor("#EF4444") // Red alert vehicle
            } else {
                Color.parseColor("#F59E0B") // Amber vehicle
            }
            
            vehiclePaint.color = vehColor
            vehiclePaint.setShadowLayer(dpToPx(4f), 0f, 0f, vehColor)

            val pillW = dpToPx(24f)
            val pillH = dpToPx(14f)
            val pillRect = RectF(centerX - pillW / 2f, animatedY - pillH / 2f, centerX + pillW / 2f, animatedY + pillH / 2f)
            canvas.drawRoundRect(pillRect, dpToPx(7f), dpToPx(7f), vehiclePaint)

            // Draw distance text inside the pill
            textPaint.color = Color.WHITE
            canvas.drawText("${vehicle.distanceM}", centerX, animatedY + dpToPx(3.5f), textPaint)
        }

        // Clean up animations map for dropped tracks
        vehicleAnimations.keys.retainAll(activeIds)

        // If there are running animations, request another frame
        if (vehicleAnimations.isNotEmpty()) {
            postInvalidateOnAnimation()
        }
    }

    // Touch Dragging Mechanics
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    private fun showExitDialog() {
        try {
            val contextThemeWrapper = ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            val builder = AlertDialog.Builder(contextThemeWrapper)
            builder.setTitle(context.getString(R.string.exit_overlay_title))
            builder.setMessage(context.getString(R.string.exit_overlay_message))
            builder.setPositiveButton(context.getString(R.string.exit_overlay_yes)) { dialog, _ ->
                dialog.dismiss()
                
                var stopped = false
                var currentContext = this@RadarOverlayView.context
                while (currentContext is android.content.ContextWrapper) {
                    if (currentContext is RadarService) {
                        currentContext.stopSelf()
                        stopped = true
                        break
                    }
                    currentContext = currentContext.baseContext
                }
                
                if (!stopped) {
                    val appContext = this@RadarOverlayView.context.applicationContext
                    appContext.stopService(Intent(appContext, RadarService::class.java))
                }
            }
            builder.setNegativeButton(context.getString(R.string.exit_overlay_no)) { dialog, _ ->
                dialog.dismiss()
            }
            val dialog = builder.create()
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            dialog.show()
        } catch (e: Exception) {
            android.util.Log.e("RadarOverlayView", "Error showing exit dialog", e)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = layoutParams.x
                initialY = layoutParams.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = (event.rawX - initialTouchX).toInt()
                val deltaY = (event.rawY - initialTouchY).toInt()
                layoutParams.x = initialX + deltaX
                layoutParams.y = initialY + deltaY
                
                // Update layout view
                try {
                    windowManager.updateViewLayout(this, layoutParams)
                } catch (e: Exception) {
                    // Ignore window detached exceptions
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                saveCurrentPosition()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun applyPositionForOrientation(orientation: Int) {
        val prefs = context.getSharedPreferences("RadarOverlayPrefs", Context.MODE_PRIVATE)
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val density = displayMetrics.density
        
        val widthPx = (65 * density).toInt()
        val heightPx = (300 * density).toInt()

        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            val defaultX = screenWidth - widthPx - (16 * density).toInt()
            val defaultY = (screenHeight - heightPx) / 2
            layoutParams.x = prefs.getInt("overlay_x_landscape", defaultX)
            layoutParams.y = prefs.getInt("overlay_y_landscape", defaultY)
        } else {
            val defaultX = screenWidth - widthPx - (16 * density).toInt()
            val defaultY = (screenHeight - heightPx) / 2
            layoutParams.x = prefs.getInt("overlay_x_portrait", defaultX)
            layoutParams.y = prefs.getInt("overlay_y_portrait", defaultY)
        }

        try {
            if (isAttachedToWindow) {
                windowManager.updateViewLayout(this, layoutParams)
            }
        } catch (e: Exception) {
            // Ignore if view not attached or other issues
        }
    }

    private fun saveCurrentPosition() {
        val prefs = context.getSharedPreferences("RadarOverlayPrefs", Context.MODE_PRIVATE)
        val orientation = context.resources.configuration.orientation
        val editor = prefs.edit()
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            editor.putInt("overlay_x_landscape", layoutParams.x)
            editor.putInt("overlay_y_landscape", layoutParams.y)
        } else {
            editor.putInt("overlay_x_portrait", layoutParams.x)
            editor.putInt("overlay_y_portrait", layoutParams.y)
        }
        editor.apply()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyPositionForOrientation(newConfig.orientation)
    }
}
