package com.varia.radaroverlay

enum class VehicleSize { BIKE, CAR, TRUCK }

enum class DataSource { NONE, V1, V2 }

enum class ThreatLevel {
    CLEAR,      // Green: No cars detected
    MEDIUM,     // Amber: Car approaching at normal speed
    HIGH,       // Red: Car approaching at high speed / rapid close rate
    OFFLINE     // Gray: Radar disconnected/searching
}

data class Vehicle(
    val id: Int,
    val distanceM: Int,
    val relativeSpeedMs: Double, // Closing speed (estimated or from V2)
    val size: VehicleSize = VehicleSize.CAR,
    val lateralPos: Float = 0f, // -1.0 to 1.0
    val lastUpdateTime: Long = System.currentTimeMillis()
)

data class RadarState(
    val vehicles: List<Vehicle> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val source: DataSource = DataSource.NONE,
    val batteryPercent: Int = -1,
    val threatLevel: ThreatLevel = ThreatLevel.OFFLINE
) {
    val isClear: Boolean get() = vehicles.isEmpty()
}
