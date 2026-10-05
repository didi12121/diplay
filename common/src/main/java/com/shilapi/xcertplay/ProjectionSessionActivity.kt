// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.shilapi.xcertplay.projection.ProjectionDisplayGeometry
import com.shilapi.xcertplay.projection.ProjectionHost
import com.shilapi.xcertplay.projection.ProjectionRect
import com.shilapi.xcertplay.projection.ProjectionState
import com.shilapi.xcertplay.projection.ProjectionStateListener
import com.shilapi.xcertplay.projection.ProjectionTouchEvents

/**
 * Protocol-neutral projection host page: renders the phone screen into a full
 * screen [SurfaceView], forwards touch to **one** bound projection backend and
 * shows that backend's session state. Reusable for CarLink today and
 * CarLife/HiCar later — it knows nothing about any protocol.
 *
 * ```
 * ProjectionSessionActivity / SurfaceView        (this file, UI layer)
 *          │ push Surface events (owner-tagged)
 *          ▼
 * ProjectionHost.display (ProjectionSurfaceHost)
 *          │ Listener callbacks
 *          ▼
 * CarLinkMediaSinkProvider → AndroidMediaSink → MediaCodec
 * ```
 *
 * Binding contract: the Activity captures [sessionBackendId] once at creation
 * (via [EXTRA_BACKEND_ID]) and ALL state/touch/disconnect/status traffic goes
 * to `manager.backend(sessionBackendId)` — never to whatever backend happens
 * to be "active" later. A background selection change cannot silently redirect
 * touches to another protocol.
 *
 * Surface lifecycle: created/changed attach the renderer; destroyed detaches
 * it so no MediaCodec outputs to a dead surface. Surface events are tagged
 * with this Activity instance as owner, so a late destroy callback from an old
 * instance cannot clear the surface of a recreated one. Because the display
 * host lives at process scope, an Activity recreate simply pushes the new
 * surface and the live session keeps rendering.
 */
class ProjectionSessionActivity : Activity(), SurfaceHolder.Callback {

    /** Which backend's session this Activity displays; fixed at creation. */
    private var sessionBackendId: String = ""

    private lateinit var surfaceView: SurfaceView
    private lateinit var statusView: TextView
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    private val stateListener = ProjectionStateListener { backendId, state ->
        // Only the bound backend's state drives this page.
        if (backendId != sessionBackendId) return@ProjectionStateListener
        runOnUiThread {
            if (!isFinishing && !isDestroyed) statusView.text = statusText(state)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Bind once: every later lookup uses this id, never a drifting
        // "active backend". Default to whatever is active at launch so a plain
        // startActivity still works.
        sessionBackendId = intent.getStringExtra(EXTRA_BACKEND_ID)
            ?: ProjectionHost.manager.activeBackend?.id
            ?: ""
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surfaceView = SurfaceView(this).also {
            it.holder.addCallback(this)
            // Phase 9.2b: capture touch on the ACTUAL projection surface. The
            // listener always consumes after forwarding, so one MotionEvent
            // becomes exactly one ProjectionTouchEvent for the bound backend —
            // the same event can never fall through to Activity.onTouchEvent
            // and be delivered twice (no double DOWN/MOVE/UP).
            it.setOnTouchListener { _, motionEvent ->
                dispatchProjectionTouch(motionEvent)
                true
            }
            root.addView(it, FrameLayout.LayoutParams(-1, -1))
        }
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x80000000.toInt())
            gravity = Gravity.CENTER
            textSize = 16f
            setPadding(24, 24, 24, 24)
            visibility = View.GONE
        }
        root.addView(
            statusView,
            FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP),
        )
        setContentView(root)
        ProjectionHost.manager.addStateListener(stateListener)
        val state = boundBackend()?.state
        statusView.text = state?.let { statusText(it) }
        maybeFinishWithoutSession()
    }

    override fun onDestroy() {
        ProjectionHost.manager.removeStateListener(stateListener)
        super.onDestroy()
    }

    /** The single backend this Activity is bound to. */
    private fun boundBackend() =
        if (sessionBackendId.isEmpty()) null else ProjectionHost.manager.backend(sessionBackendId)

    // ---- Surface lifecycle (owner-tagged: this instance is the owner) ----

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceWidth = holder.surfaceFrame.width()
        surfaceHeight = holder.surfaceFrame.height()
        ProjectionHost.display.surfaceAvailable(this, holder.surface, surfaceWidth, surfaceHeight)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        ProjectionHost.display.surfaceChanged(this, holder.surface, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Detach the renderer; the protocol session itself keeps running and
        // re-attaches when a new surface appears (Activity recreate). Tagged
        // with `this` so a stale callback never clears a newer surface.
        ProjectionHost.display.surfaceDestroyed(this)
    }

    // ---- Input: unified touch events to the BOUND backend only ----

    /**
     * The single touch-forwarding seam: one Android [MotionEvent] → one
     * `ProjectionTouchEvent` → the backend bound at creation. Coordinates stay
     * raw view pixels plus geometry; each backend maps them (CarLife maps into
     * content-local space and lets the SDK do the final video mapping).
     */
    private fun dispatchProjectionTouch(event: MotionEvent): Boolean {
        val backend = boundBackend() ?: return false
        val width = if (surfaceWidth > 0) surfaceWidth else viewWidth()
        val height = if (surfaceHeight > 0) surfaceHeight else viewHeight()
        val geometry = ProjectionDisplayGeometry(
            screenWidth = width,
            screenHeight = height,
            contentRect = ProjectionRect(0f, 0f, width.toFloat(), height.toFloat()),
        )
        return backend.onTouchEvent(ProjectionTouchEvents.from(event, geometry))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Fallback only: touches the surfaceView already claimed are consumed
        // by its listener above and never reach this method, so there is no
        // duplicate delivery.
        return dispatchProjectionTouch(event) || super.onTouchEvent(event)
    }

    override fun onBackPressed() {
        // Leaving the page detaches the surface but keeps the session; the
        // session ends through the explicit disconnect control.
        super.onBackPressed()
    }

    /** Disconnects the bound backend's session. */
    fun disconnectSession() {
        if (sessionBackendId.isNotEmpty()) {
            ProjectionHost.manager.disconnectBackend(sessionBackendId)
        }
    }

    // ---- Helpers ----

    private fun viewWidth(): Int = window.decorView.width
    private fun viewHeight(): Int = window.decorView.height

    private fun maybeFinishWithoutSession() {
        val backend = boundBackend()
        if (backend == null || (!backend.isSessionActive && backend.state !is ProjectionState.Connected)) {
            statusView.visibility = View.VISIBLE
            statusView.text = statusText(backend?.state ?: ProjectionState.Idle)
        }
    }

    private fun statusText(state: ProjectionState): String {
        val text = when (state) {
            ProjectionState.Connecting -> "Connecting…"
            ProjectionState.Connected -> "Connected — waiting for video"
            ProjectionState.Disconnecting -> "Disconnecting…"
            ProjectionState.Discovering -> "Discovering…"
            ProjectionState.Initializing, ProjectionState.Ready -> "Starting…"
            ProjectionState.Idle -> "No projection session"
            is ProjectionState.Error -> "Error: ${state.message}"
        }
        statusView.visibility = if (state is ProjectionState.Connected) View.GONE else View.VISIBLE
        return text
    }

    companion object {
        /** Which backend session to display, e.g. `carlink`, `carplay`. */
        const val EXTRA_BACKEND_ID = "projection.backendId"

        fun createIntent(context: Context, backendId: String): Intent =
            Intent(context, ProjectionSessionActivity::class.java).putExtra(EXTRA_BACKEND_ID, backendId)
    }
}
