// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.carlife

import com.shilapi.xcertplay.projection.ProjectionDevice
import com.shilapi.xcertplay.projection.ProjectionResource
import com.shilapi.xcertplay.projection.ProjectionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 9.1.1 hardening: terminal outcomes release the USB/AUDIO lease
 * immediately (session-stopped confirmation), the probe timeout classifies
 * failures precisely, and diagnostics come from the real provider data.
 */
class CarLifeTerminalReleaseTest {

    private class ScriptedProvider : CarLifeProvider {
        override val isAvailable = true
        var listener: ((CarLifeConnectionEvent) -> Unit)? = null
        var lastToken: CarLifeSessionToken? = null
        var diagnostics = CarLifeProviderDiagnostics(
            usbDevices = listOf("VID:PID 18d1:4ee1"),
            localProtocolVersion = 4,
            phoneCarLifeVersion = 3,
        )

        override fun initialize(context: android.content.Context, config: CarLifeProviderConfig) {}
        override fun startConnection(token: CarLifeSessionToken, listener: (CarLifeConnectionEvent) -> Unit) {
            lastToken = token
            this.listener = listener
        }

        override fun stopConnection(token: CarLifeSessionToken) {}
        override fun dispose() {}
        override fun diagnostics(): CarLifeProviderDiagnostics = diagnostics

        fun emit(event: CarLifeConnectionEvent) = listener?.invoke(event)
    }

    /** Deterministic scheduler: captures the timeout for manual firing. */
    private class ManualScheduler : CarLifeProbeTimeoutScheduler {
        var pending: (() -> Unit)? = null
        override fun schedule(timeoutMillis: Long, onTimeout: () -> Unit): AutoCloseable {
            pending = onTimeout
            return AutoCloseable { pending = null }
        }
    }

    private fun session(
        provider: ScriptedProvider = ScriptedProvider(),
        scheduler: ManualScheduler = ManualScheduler(),
    ): Triple<ProjectionManager, CarLifeProjectionBackend, ScriptedProvider> {
        val manager = ProjectionManager()
        val backend = CarLifeProjectionBackend(provider, scheduler, 60_000)
        manager.register(backend)
        return Triple(manager, backend, provider)
    }

    private fun ProjectionManager.held() = resourcesHeldBy(CarLifeProjectionBackend.ID)

    // ---- #8 terminal resource release ----

    @Test
    fun aoaDetachReleasesResourcesImmediately() {
        val (manager, backend, provider) = session()
        manager.connect(ProjectionDevice("d", "phone", CarLifeProjectionBackend.ID))
        provider.emit(CarLifeConnectionEvent.Established(provider.lastToken!!))
        assertTrue(manager.held().isNotEmpty())

        provider.emit(CarLifeConnectionEvent.Detached(provider.lastToken!!))
        assertTrue("USB/AUDIO must be released at the terminal event", manager.held().isEmpty())
    }

    @Test
    fun versionRejectReleasesResourcesImmediately() {
        val (manager, backend, provider) = session()
        manager.connect(ProjectionDevice("d", "phone", CarLifeProjectionBackend.ID))
        provider.emit(CarLifeConnectionEvent.VersionNotSupported(provider.lastToken!!))
        assertTrue(manager.held().isEmpty())
    }

    @Test
    fun authFailureReleasesResourcesImmediately() {
        val (manager, backend, provider) = session()
        manager.connect(ProjectionDevice("d", "phone", CarLifeProjectionBackend.ID))
        provider.emit(CarLifeConnectionEvent.AuthFailed(provider.lastToken!!))
        assertTrue(manager.held().isEmpty())
    }

    @Test
    fun transportFailureReleasesResourcesImmediately() {
        val (manager, backend, provider) = session()
        manager.connect(ProjectionDevice("d", "phone", CarLifeProjectionBackend.ID))
        provider.emit(CarLifeConnectionEvent.Failed(provider.lastToken!!, "usb crash"))
        assertTrue(manager.held().isEmpty())
    }

    @Test
    fun establishedKeepsLeaseUntilRealEnd() {
        val (manager, backend, provider) = session()
        manager.connect(ProjectionDevice("d", "phone", CarLifeProjectionBackend.ID))
        provider.emit(CarLifeConnectionEvent.Established(provider.lastToken!!))
        // Progress noise must never release a live lease.
        provider.emit(CarLifeConnectionEvent.Progress(provider.lastToken!!, 100))
        assertEquals(
            setOf(ProjectionResource.USB, ProjectionResource.AUDIO),
            manager.held(),
        )
    }

    // ---- #7 timeout classification ----

    @Test
    fun timeoutWithNoUsbCandidateIsNoRealDevice() {
        val scheduler = ManualScheduler()
        val provider = ScriptedProvider().also { it.diagnostics = CarLifeProviderDiagnostics() }
        val (_, backend, _) = session(provider, scheduler)
        backend.connect(null)
        scheduler.pending?.invoke()
        val report = backend.probeReport()
        assertEquals(CarLifeBlocker.NO_REAL_DEVICE, report.blocker)
    }

    @Test
    fun timeoutWithUsbCandidateButNoAoaIsAoaCompatibility() {
        val scheduler = ManualScheduler()
        val provider = ScriptedProvider() // diagnostics carry a VID:PID
        val (_, backend, _) = session(provider, scheduler)
        backend.connect(null)
        scheduler.pending?.invoke()
        val report = backend.probeReport()
        assertEquals(CarLifeBlocker.AOA_COMPATIBILITY, report.blocker)
    }

    @Test
    fun timeoutNeverFiresAfterEstablished() {
        val scheduler = ManualScheduler()
        val (manager, backend, provider) = session(scheduler = scheduler)
        backend.connect(null)
        provider.emit(CarLifeConnectionEvent.Established(provider.lastToken!!))
        // Watchdog cancelled: manual fire is a no-op.
        scheduler.pending?.invoke()
        assertTrue(backend.isSessionActive)
        assertEquals(CarLifeBlocker.NONE, backend.probeReport().blocker)
    }

    // ---- #6 real diagnostics ----

    @Test
    fun probeReportCarriesRealProviderDiagnostics() {
        val (_, backend, provider) = session()
        backend.connect(null)
        val report = backend.probeReport()
        assertEquals("VID:PID 18d1:4ee1", report.usbDevice)
        assertEquals(4, report.protocolVersion)
    }
}
