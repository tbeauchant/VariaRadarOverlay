// SPDX-License-Identifier: Apache-2.0
// Copyright (C) 2026 JJ del Rio
// From bike-radar-docs. Modified for this app.
// Licence text: LICENSES/Apache-2.0.txt
package com.varia.radaroverlay

import android.util.Log

class RadarV1Decoder(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val staleMs: Long = STALE_MS,
) {
    private data class Track(
        val vehicle: Vehicle, 
        val lastSeen: Long,
        val firstSeen: Long = lastSeen
    )

    private val tracks = HashMap<Int, Track>()
    private var batteryPercent: Int = -1

    fun updateBattery(percent: Int): RadarState {
        batteryPercent = percent
        return snapshot(nowMs())
    }

    /**
     * Feed a single notification payload. Returns the new [RadarState] if the
     * packet changed anything visible (new/updated/dropped track), else null.
     */
    fun feed(payload: ByteArray): RadarState? {
        val now = nowMs()
        val changed = when {
            payload.size == 1 -> pruneStale(now)
            payload.size == 6 && payload[0] == 0x06.toByte() -> pruneStale(now)
            payload.size >= 4 && (payload.size - 1) % 3 == 0 -> ingestThreat(payload, now)
            else -> pruneStale(now)
        }
        return if (changed) snapshot(now) else null
    }

    private fun ingestThreat(payload: ByteArray, now: Long): Boolean {
        var changed = pruneStale(now)
        
        // Skip first byte (seq byte) and chunk into triplets
        val data = payload.drop(1)
        val numTriplets = data.size / 3
        
        for (i in 0 until numTriplets) {
            val idx = i * 3
            val vid = data[idx].toInt() and 0xFF
            val dist = data[idx + 1].toInt() and 0xFF
            val flag = data[idx + 2].toInt() and 0xFF // flag byte, not speed

            // Filter rules from specification:
            // - Bit 7 (0x80) is a "vehicle present" flag; ignore any record with vid < 0x80
            // - vid == 0x00 and vid == 0xFD are status/no-op markers; skip
            if (vid == 0x00 || vid == 0xFD || vid < 0x80) continue
            // - dist == 0xFF is far/uncertain sentinel; skip
            if (dist == 0xFF) continue

            val id = vid and 0x7F
            val existingTrack = tracks[id]
            
            // Calculate speed in m/s based on distance delta over time
            var estimatedSpeedMs = 0.0
            if (existingTrack != null) {
                val dt = (now - existingTrack.lastSeen) / 1000.0
                if (dt > 0.05 && dt < 3.0) {
                    val deltaDist = existingTrack.vehicle.distanceM - dist
                    // Positive speed means the vehicle is closing in
                    val instantSpeed = deltaDist / dt
                    
                    // Simple low-pass filter to smooth out speed measurements
                    estimatedSpeedMs = if (existingTrack.vehicle.relativeSpeedMs > 0) {
                        0.6 * existingTrack.vehicle.relativeSpeedMs + 0.4 * instantSpeed
                    } else {
                        instantSpeed
                    }
                } else {
                    estimatedSpeedMs = existingTrack.vehicle.relativeSpeedMs
                }
            }

            val size = existingTrack?.vehicle?.size ?: VehicleSize.CAR
            val lateral = existingTrack?.vehicle?.lateralPos ?: 0f

            tracks[id] = Track(
                vehicle = Vehicle(
                    id = id, 
                    distanceM = dist, 
                    relativeSpeedMs = estimatedSpeedMs, 
                    size = size, 
                    lateralPos = lateral,
                    lastUpdateTime = now
                ),
                lastSeen = now,
                firstSeen = existingTrack?.firstSeen ?: now
            )
            changed = true
        }
        return changed
    }

    fun pruneStale(now: Long): Boolean {
        val before = tracks.size
        tracks.values.removeAll { now - it.lastSeen > staleMs }
        return tracks.size != before
    }

    fun snapshot(now: Long): RadarState {
        val vehiclesList = tracks.values.map { it.vehicle }.sortedBy { it.distanceM }
        
        // Determine overall threat level
        val overallThreat = when {
            vehiclesList.isEmpty() -> ThreatLevel.CLEAR
            else -> {
                // If any vehicle is closing in rapidly (closing speed > 10 m/s = 36 km/h)
                // or is very close (< 25m) and closing (speed > 4 m/s)
                val hasHighThreat = vehiclesList.any { vehicle ->
                    vehicle.relativeSpeedMs > 10.0 || (vehicle.distanceM < 30 && vehicle.relativeSpeedMs > 5.0)
                }
                if (hasHighThreat) ThreatLevel.HIGH else ThreatLevel.MEDIUM
            }
        }

        return RadarState(
            vehicles = vehiclesList,
            timestamp = now,
            source = DataSource.V1,
            batteryPercent = batteryPercent,
            threatLevel = overallThreat
        )
    }

    fun reset() {
        tracks.clear()
    }

    companion object {
        const val STALE_MS = 2500L // Drop track if unseen for 2.5 seconds
        private const val TAG = "RadarV1Decoder"
    }
}
