package com.shilapi.xcertplay.projection.carplay

import com.shilapi.xcertplay.projection.ProjectionDisplayGeometry
import com.shilapi.xcertplay.projection.ProjectionRect
import com.shilapi.xcertplay.projection.ProjectionTouchAction
import com.shilapi.xcertplay.projection.ProjectionTouchEvent
import com.shilapi.xcertplay.projection.ProjectionTouchPointer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Touch coordinate mapping: unified event -> normalized CarPlay HID contacts. */
class CarPlayInputAdapterTest {
    private val content = ProjectionRect(left = 24f, top = 48f, width = 1872f, height = 894f)
    private val geometry = ProjectionDisplayGeometry(
        screenWidth = 1920,
        screenHeight = 990,
        contentRect = content,
    )

    private fun event(
        action: ProjectionTouchAction = ProjectionTouchAction.MOVE,
        vararg pointers: ProjectionTouchPointer,
    ) = ProjectionTouchEvent(action, pointers.toList(), geometry)

    @Test
    fun contentCornersMapToNormalizedCorners() {
        val down = CarPlayInputAdapter.contacts(
            event(
                ProjectionTouchAction.DOWN,
                ProjectionTouchPointer(0, content.left, content.top, down = true),
            ),
        ).single()
        assertEquals(0.0, down.x, 1e-6)
        assertEquals(0.0, down.y, 1e-6)
        assertTrue(down.down)

        val up = CarPlayInputAdapter.contacts(
            event(
                ProjectionTouchAction.UP,
                ProjectionTouchPointer(0, content.left + content.width, content.top + content.height, down = false),
            ),
        ).single()
        assertEquals(1.0, up.x, 1e-6)
        assertEquals(1.0, up.y, 1e-6)
        assertFalse(up.down)
    }

    @Test
    fun centreRemainsCentred() {
        val contact = CarPlayInputAdapter.contacts(
            event(
                ProjectionTouchAction.MOVE,
                ProjectionTouchPointer(0, 24f + 936f, 48f + 447f, down = true),
            ),
        ).single()
        assertEquals(0.5, contact.x, 1e-6)
        assertEquals(0.5, contact.y, 1e-6)
    }

    @Test
    fun outsideContentClampsToEdges() {
        val contact = CarPlayInputAdapter.contacts(
            event(
                ProjectionTouchAction.MOVE,
                ProjectionTouchPointer(0, -500f, 5000f, down = true),
            ),
        ).single()
        assertEquals(0.0, contact.x, 1e-6)
        assertEquals(1.0, contact.y, 1e-6)
    }

    @Test
    fun multiTouchKeepsTwoContactsInOrder() {
        val contacts = CarPlayInputAdapter.contacts(
            event(
                ProjectionTouchAction.MOVE,
                ProjectionTouchPointer(0, content.left, content.top, down = true),
                ProjectionTouchPointer(1, content.left + content.width, content.top + content.height, down = true),
            ),
        )
        assertEquals(2, contacts.size)
        assertEquals(0, contacts[0].id)
        assertEquals(1, contacts[1].id)
        assertEquals(1.0, contacts[1].x, 1e-6)
    }

    @Test
    fun contactCountIsCappedAtTheHidLimit() {
        val contacts = CarPlayInputAdapter.contacts(
            event(
                ProjectionTouchAction.MOVE,
                ProjectionTouchPointer(0, 100f, 100f, true),
                ProjectionTouchPointer(1, 200f, 200f, true),
                ProjectionTouchPointer(2, 300f, 300f, true),
            ),
        )
        assertEquals(CarPlayInputAdapter.MAX_CONTACTS, contacts.size)
    }

    @Test
    fun liftedPointerIsReportedAsUp() {
        val contacts = CarPlayInputAdapter.contacts(
            event(
                ProjectionTouchAction.MOVE,
                ProjectionTouchPointer(0, 100f, 100f, down = true),
                ProjectionTouchPointer(1, 200f, 200f, down = false),
            ),
        )
        assertTrue(contacts[0].down)
        assertFalse(contacts[1].down)
    }

    @Test
    fun emptyPointerListProducesNoContacts() {
        assertTrue(CarPlayInputAdapter.contacts(event(ProjectionTouchAction.UP)).isEmpty())
    }

    @Test
    fun degenerateGeometryDoesNotDivideByZero() {
        val zeroRect = ProjectionRect(0f, 0f, 0f, 0f)
        val zeroGeometry = ProjectionDisplayGeometry(0, 0, zeroRect)
        val contact = CarPlayInputAdapter.contacts(
            ProjectionTouchEvent(
                ProjectionTouchAction.MOVE,
                listOf(ProjectionTouchPointer(0, 0f, 0f, true)),
                zeroGeometry,
            ),
        ).single()
        assertEquals(0.0, contact.x, 1e-6)
        assertEquals(0.0, contact.y, 1e-6)
    }
}
