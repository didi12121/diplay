package com.shilapi.xcertplay.projection

import com.shilapi.xcertplay.carlink.AndroidCarLinkMediaSinkProvider
import com.shilapi.xcertplay.carlink.CarLinkMediaSinkProvider
import com.shilapi.xcertplay.carlink.CarLinkProjectionBackend
import com.shilapi.xcertplay.carlink.CarLinkProtocolAdapter
import com.shilapi.xcertplay.carlink.MockCarLinkProtocolAdapter
import com.shilapi.xcertplay.carlink.UnavailableCarLinkProtocolAdapter
import com.shilapi.xcertplay.projection.display.ProjectionSurfaceHost

/**
 * Process-local projection host: one [ProjectionManager] with the registered
 * backends every UI surface shares, plus the process-level
 * [ProjectionSurfaceHost] the UI pushes rendering surfaces into.
 *
 * The CarPlay backend is registered by `CarPlayHostActivity` when it creates (or
 * adopts) its controller, because only that layer owns the Apple construction
 * parameters. The CarLink backend is registered with an injected protocol
 * adapter and a real media sink provider (the shared `AndroidMediaSink`
 * pipeline rendering into [display]'s surface).
 *
 * **Production default**: [UnavailableCarLinkProtocolAdapter]. Without an
 * official ICCOA CarLink SDK there is NO provider, and the UI must show
 * "CarLink protocol provider unavailable" — never mock devices or a fake
 * CONNECTED state. [MockCarLinkProtocolAdapter] is for unit tests and explicit
 * developer test harnesses only (see [registerCarLinkMock]).
 */
object ProjectionHost {
    val manager: ProjectionManager = ProjectionManager(logger = ProjectionLogger.ANDROID)

    /**
     * Process-level display host: projection host activities push their
     * SurfaceView/TextureView surfaces here; media providers attach renderers
     * to it. Lives as long as the process so an Activity recreate re-attaches
     * without dropping a live session.
     */
    val display: ProjectionSurfaceHost = ProjectionSurfaceHost()

    /** Stable no-provider instance so repeated registration is idempotent. */
    private val unavailableAdapter: CarLinkProtocolAdapter = UnavailableCarLinkProtocolAdapter()

    @Volatile
    private var carLinkAdapter: CarLinkProtocolAdapter = unavailableAdapter

    /**
     * Registers (or reuses) the CarLink backend. The production default is the
     * [UnavailableCarLinkProtocolAdapter]: without an official SDK there is no
     * provider. To plug in a real one later:
     * `registerCarLink(OfficialCarLinkSdkAdapter(...))` — the rest of the stack
     * (UI, media sinks, resource arbitration) works unchanged.
     *
     * Media sinks default to the shared Android rendering pipeline
     * ([AndroidCarLinkMediaSinkProvider]) rendering into [display]'s surface —
     * pass `context = applicationContext` so audio focus/routes work.
     */
    @Synchronized
    fun registerCarLink(
        adapter: CarLinkProtocolAdapter = unavailableAdapter,
        mediaSinks: CarLinkMediaSinkProvider? = null,
        context: android.content.Context? = null,
    ): CarLinkProjectionBackend {
        val existing = carLinkBackend
        if (existing != null && adapter === carLinkAdapter) return existing
        carLinkAdapter = adapter
        val backend = CarLinkProjectionBackend(
            adapter = adapter,
            mediaSinks = mediaSinks ?: AndroidCarLinkMediaSinkProvider(display, context),
            logger = ProjectionLogger.ANDROID,
        )
        manager.register(backend)
        return backend
    }

    /**
     * Explicit developer test harness entry: registers the in-memory
     * [MockCarLinkProtocolAdapter]. Never called by production UI code — the
     * mock exists so the pipeline can be exercised without real phones.
     */
    @Synchronized
    fun registerCarLinkMock(
        mediaSinks: CarLinkMediaSinkProvider,
        adapter: MockCarLinkProtocolAdapter = MockCarLinkProtocolAdapter(),
    ): CarLinkProjectionBackend = registerCarLink(adapter, mediaSinks)

    /** The registered CarLink backend, if any. */
    val carLinkBackend: CarLinkProjectionBackend?
        get() = manager.backend(CarLinkProjectionBackend.ID) as? CarLinkProjectionBackend

    /** The registered CarPlay backend, if any. */
    val carPlayBackend: com.shilapi.xcertplay.projection.carplay.CarPlayProjectionBackend?
        get() = manager.backend(com.shilapi.xcertplay.projection.carplay.CarPlayProjectionBackend.ID)
            as? com.shilapi.xcertplay.projection.carplay.CarPlayProjectionBackend

    /**
     * The registered CarLife backend, if any. The backend itself lives in the
     * `carlife-provider` module (open CarLife V2 SDK, Apache-2.0); it is
     * registered by the app layer so `shared` stays protocol-agnostic.
     */
    fun carLifeBackend(): com.shilapi.xcertplay.projection.ProjectionBackend? =
        manager.backend("carlife")
}
