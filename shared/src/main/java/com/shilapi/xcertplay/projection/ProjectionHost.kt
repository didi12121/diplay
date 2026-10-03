package com.shilapi.xcertplay.projection

import com.shilapi.xcertplay.carlink.CarLinkProjectionBackend
import com.shilapi.xcertplay.carlink.CarLinkProtocolAdapter
import com.shilapi.xcertplay.carlink.MockCarLinkProtocolAdapter

/**
 * Process-local projection host: one [ProjectionManager] with the registered
 * backends every UI surface shares.
 *
 * The CarPlay backend is registered by `CarPlayHostActivity` when it creates (or
 * adopts) its controller, because only that layer owns the Apple construction
 * parameters. The CarLink backend is registered up-front with an injected
 * protocol adapter — [MockCarLinkProtocolAdapter] until an official ICCOA
 * CarLink SDK adapter exists.
 */
object ProjectionHost {
    val manager: ProjectionManager = ProjectionManager(logger = ProjectionLogger.ANDROID)

    @Volatile
    private var carLinkAdapter: CarLinkProtocolAdapter = MockCarLinkProtocolAdapter()

    /**
     * Registers (or reuses) the CarLink backend with [adapter]. Passing an
     * `OfficialCarLinkSdkAdapter` later switches the whole stack without UI
     * changes. Returns the backend for direct UI wiring. Idempotent: repeated
     * calls with the same adapter return the live backend and keep its state.
     */
    @Synchronized
    fun registerCarLink(adapter: CarLinkProtocolAdapter = carLinkAdapter): CarLinkProjectionBackend {
        val existing = carLinkBackend
        if (existing != null && adapter === carLinkAdapter) return existing
        carLinkAdapter = adapter
        val backend = CarLinkProjectionBackend(
            adapter = adapter,
            logger = ProjectionLogger.ANDROID,
        )
        manager.register(backend)
        return backend
    }

    /** The registered CarLink backend, if any. */
    val carLinkBackend: CarLinkProjectionBackend?
        get() = manager.backend(CarLinkProjectionBackend.ID) as? CarLinkProjectionBackend

    /** The registered CarPlay backend, if any. */
    val carPlayBackend: com.shilapi.xcertplay.projection.carplay.CarPlayProjectionBackend?
        get() = manager.backend(com.shilapi.xcertplay.projection.carplay.CarPlayProjectionBackend.ID)
            as? com.shilapi.xcertplay.projection.carplay.CarPlayProjectionBackend
}
