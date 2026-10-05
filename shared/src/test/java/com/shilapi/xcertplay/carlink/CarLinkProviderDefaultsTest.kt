package com.shilapi.xcertplay.carlink

import com.shilapi.xcertplay.projection.ProjectionAudioSink
import com.shilapi.xcertplay.projection.ProjectionErrorCode
import com.shilapi.xcertplay.projection.ProjectionHost
import com.shilapi.xcertplay.projection.ProjectionState
import com.shilapi.xcertplay.projection.ProjectionVideoSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Production host defaults: without an official ICCOA CarLink SDK there is NO
 * protocol provider — the host must never fall back to the mock adapter or
 * expose fake devices / fake CONNECTED states in a normal APK.
 */
class CarLinkProviderDefaultsTest {

    private fun noMedia() = StaticCarLinkMediaSinkProvider(ProjectionVideoSink.NOOP, ProjectionAudioSink.NOOP)

    @Test
    fun productionProjectionHostDoesNotDefaultToMockAdapter() {
        // The registration default (no explicit adapter) must be the
        // unavailable provider, not MockCarLinkProtocolAdapter.
        val backend = ProjectionHost.registerCarLink(mediaSinks = noMedia())
        assertFalse(
            "production default must not be the mock adapter",
            backend.carLink.providerName == "mock",
        )
        assertEquals("none", backend.carLink.providerName)
        assertFalse(backend.carLink.available)
    }

    @Test
    fun unavailableProviderExposesNoFakeDevices() {
        val backend = CarLinkProjectionBackend(
            adapter = UnavailableCarLinkProtocolAdapter(),
            mediaSinks = noMedia(),
        )
        backend.initialize()
        backend.start()

        assertTrue(backend.discoveredDevices().isEmpty())
        val error = backend.state
        assertTrue(error is ProjectionState.Error)
        (error as ProjectionState.Error).let {
            assertEquals(ProjectionErrorCode.PROVIDER_UNAVAILABLE, it.code)
            assertEquals("CarLink protocol provider unavailable", it.message)
        }
    }

    @Test
    fun unavailableProviderNeverReportsConnected() {
        val backend = CarLinkProjectionBackend(
            adapter = UnavailableCarLinkProtocolAdapter(),
            mediaSinks = noMedia(),
        )
        backend.initialize()
        backend.start()
        backend.connect(null)
        assertFalse(backend.isSessionActive)
        assertTrue(backend.state is ProjectionState.Error)

        // Even after a disconnect request nothing pretends to be a session.
        backend.disconnect()
        assertFalse(backend.isSessionActive)
    }

    @Test
    fun mockRegistrationIsExplicit() {
        // The mock only enters through the explicit developer/test entry point.
        val backend = ProjectionHost.registerCarLinkMock(mediaSinks = noMedia())
        assertEquals("mock", backend.carLink.providerName)
        // Restore the production default for other tests.
        ProjectionHost.registerCarLink(mediaSinks = noMedia())
        assertEquals("none", ProjectionHost.carLinkBackend?.carLink?.providerName)
    }
}
