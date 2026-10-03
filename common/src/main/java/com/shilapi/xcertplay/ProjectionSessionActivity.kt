// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.app.Activity
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
 * screen [SurfaceView], forwards touch to the active projection backend and
 * shows session state. Reusable for CarLink today and CarLife/HiCar later —
 * it knows nothing about any protocol.
 *
 * ```
 * ProjectionSessionActivity / SurfaceView        (this file, UI layer)
 *          │ push Surface events
 *          ▼
 * ProjectionHost.display (ProjectionSurfaceHost)
 *          │ Listener callbacks
 *          ▼
 * CarLinkMediaSinkProvider → AndroidMediaSink → MediaCodec
 * ```
 *
 * Surface lifecycle: created/changed attach the renderer; destroyed detaches
 * it so no MediaCodec outputs to a dead surface. Because the display host
 * lives at process scope, an Activity recreate (rotation, dark-mode change)
 * simply pushes the new surface and the live session keeps rendering.
 */
class ProjectionSessionActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var surfaceView: SurfaceView
    private lateinit var statusView: TextView
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    private val stateListener = ProjectionStateListener { _, state ->
        runOnUiThread {
            if (!isFinishing && !isDestroyed) statusView.text = statusText(state)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surfaceView = SurfaceView(this).also {
            it.holder.addCallback(this)
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
        val state = ProjectionHost.manager.activeBackend?.state
        statusView.text = state?.let { statusText(it) }
        maybeFinishWithoutSession()
    }

    override fun onDestroy() {
        ProjectionHost.manager.removeStateListener(stateListener)
        super.onDestroy()
    }

    // ---- Surface lifecycle ----

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceWidth = holder.surfaceFrame.width()
        surfaceHeight = holder.surfaceFrame.height()
        ProjectionHost.display.surfaceAvailable(holder.surface, surfaceWidth, surfaceHeight)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        ProjectionHost.display.surfaceChanged(holder.surface, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // Detach the renderer; the protocol session itself keeps running and
        // re-attaches when a new surface appears (Activity recreate).
        ProjectionHost.display.surfaceDestroyed()
    }

    // ---- Input: unified touch events to the active backend ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val width = if (surfaceWidth > 0) surfaceWidth else viewWidth()
        val height = if (surfaceHeight > 0) surfaceHeight else viewHeight()
        val geometry = ProjectionDisplayGeometry(
            screenWidth = width,
            screenHeight = height,
            contentRect = ProjectionRect(0f, 0f, width.toFloat(), height.toFloat()),
        )
        val backend = ProjectionHost.manager.activeBackend ?: return super.onTouchEvent(event)
        return backend.onTouchEvent(ProjectionTouchEvents.from(event, geometry)) || super.onTouchEvent(event)
    }

    override fun onBackPressed() {
        // Leaving the page detaches the surface but keeps the session; the
        // session ends through the explicit disconnect control.
        super.onBackPressed()
    }

    // ---- Helpers ----

    private fun viewWidth(): Int = window.decorView.width
    private fun viewHeight(): Int = window.decorView.height

    private fun maybeFinishWithoutSession() {
        val backend = ProjectionHost.manager.activeBackend
        if (backend == null || (!backend.isSessionActive && backend.state !is ProjectionState.Connected)) {
            statusView.visibility = View.VISIBLE
            statusView.text = statusText(backend?.state ?: ProjectionState.Idle)
        }
    }

    private fun statusText(state: ProjectionState): String = when (state) {
        ProjectionState.Connecting -> "Connecting…"
        ProjectionState.Connected -> "Connected — waiting for video"
        ProjectionState.Disconnecting -> "Disconnecting…"
        ProjectionState.Discovering -> "Discovering…"
        ProjectionState.Initializing, ProjectionState.Ready -> "Starting…"
        ProjectionState.Idle -> "No projection session"
        is ProjectionState.Error -> "Error: ${state.message}"
    }.also { statusView.visibility = if (state is ProjectionState.Connected) View.GONE else View.VISIBLE }
}
