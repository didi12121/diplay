package com.shilapi.xcertplay.projection

/**
 * A phone discovered by a projection backend.
 *
 * Deliberately free of protocol specifics: discovery identifiers are opaque
 * strings whose meaning belongs to the backend that produced them.
 */
data class ProjectionDevice(
    /** Backend-scoped stable identifier for connect(). */
    val id: String,
    /** Human readable name, e.g. the phone's Bluetooth name. */
    val name: String,
    /** Which backend discovered this device, e.g. `carplay`, `carlink`. */
    val backendId: String,
    /** Best-effort transport the device would use; UNKNOWN when not yet known. */
    val transport: ProjectionTransport = ProjectionTransport.UNKNOWN,
    /** Optional vendor hint such as `xiaomi`, `vivo`, `oppo`, `apple`; never authoritative. */
    val vendorHint: String? = null,
)
