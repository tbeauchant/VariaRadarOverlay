# Varia Radar Overlay

An Android application that connects to a Garmin Varia radar (such as the RTL515) via Bluetooth Low Energy (BLE) to display a real-time, floating threat overlay on top of cycling maps or any other app.

---

## Features

- **Live Overlay:** Displays vehicle positions, distance (in meters), and approach status on a compact, floating vertical track.
- **Glassmorphic Threat UI:** Color-coded glowing borders change dynamically depending on the current threat level:
  - 🟢 **Green (Clear):** No threats detected.
  - 🟡 **Amber (Medium):** Normal speed vehicle approaching.
  - 🔴 **Red (High):** Fast-approaching vehicle (includes a pulsing glow animation).
- **Audio Alerts:** Generates distinct beeping alarms when new vehicles are detected (double beep for high-speed threats, single beep for medium threats).
- **Persistent Drag-and-Drop:** Move the overlay anywhere on screen. It remembers and applies separate positions for **Portrait** and **Landscape** orientations.
- **Long-Press to Exit:** A simple long-press on the overlay brings up a direct confirmation dialog to stop the foreground service and close the widget instantly.
- **BLE Decoder:** Connects directly to the official Garmin Varia BLE service to decode battery status and real-time tracking data.

---

## ⚡ Vibe Coding Note
> [!NOTE]  
> This application is **vibe coded** with Antigravity/Gemini. I am not an Android developer, so this app may have some issues. Please use it at your own risk.