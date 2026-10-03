package com.shilapi.xcertplay.projection

/** How a phone is (or would be) attached to the head unit. */
enum class ProjectionTransport {
    USB,
    BLUETOOTH,
    WIFI,
    WIFI_DIRECT,
    WIFI_HOTSPOT,
    UNKNOWN,
}

/** Runtime capability flags that differ across head units and Android versions. */
object ProjectionRuntimeCapabilities {
    /** USB accessory/CarPlay wiring is available on every supported Android version. */
    fun usbAccessory(sdkInt: Int): Boolean = sdkInt >= 28

    /**
     * Wi-Fi Direct group ownership needs the P2P APIs; DiPlay gates the newer
     * hotspot flows on Android 10 like the wireless CarPlay stack does.
     */
    fun wifiDirect(sdkInt: Int): Boolean = sdkInt >= 29

    /** Local-only hotspot soft-AP control needs Android 10+. */
    fun localOnlyHotspot(sdkInt: Int): Boolean = sdkInt >= 29
}
