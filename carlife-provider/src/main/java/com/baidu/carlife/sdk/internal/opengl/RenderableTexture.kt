package com.baidu.carlife.sdk.internal.opengl

import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.util.Logger
import java.util.concurrent.atomic.AtomicInteger

class RenderableTexture(val textureId: Int,
                        val width: Int,
                        val height: Int,
                        val scale: Float = 1.0f,
                        val xOffset: Int = 0,
                        val yOffset: Int = 0,
                        // padding， 左上右下
                        val padding: IntArray = intArrayOf(0, 0, 0, 0))
    : SurfaceTexture.OnFrameAvailableListener {
    private var needUpdateTex = AtomicInteger(0)
    @Volatile
    private var isReleased = false
    private var activated = false

    val texture: SurfaceTexture = SurfaceTexture(textureId)
    val transform = FloatArray(16)

    val surface: Surface = Surface(texture)

    var onFrameAvailableListener: SurfaceTexture.OnFrameAvailableListener? = null

    init {
        if (width != 0 && height != 0) {
            setDefaultBufferSize(width, height)
        }
        texture.setOnFrameAvailableListener(this)
        Logger.d(Constants.TAG, "RenderableTexture monitor create ", this)
    }

    fun setDefaultBufferSize(width: Int, height: Int) {
        texture.setDefaultBufferSize(width, height)
    }

    fun activate() {
        activated = true
    }

    fun deactivate() {
        activated = false
    }

    fun isActivated(): Boolean {
        return activated
    }

    fun updateTexImage(): Boolean {
        var updated = false
        if (!isReleased) {
            while (needUpdateTex.get() > 0 && needUpdateTex.getAndDecrement() > 0) {
                updated = true
                try {
                    texture.updateTexImage()
                    texture.getTransformMatrix(transform)
                } catch (e: Exception) {
                    Log.e(Constants.TAG, "RenderableTexture $this updateTexImage exception $e");
                }
            }
        }
        return updated
    }

    fun release(deleteTexture: Boolean = true) {
        if (!isReleased) {
            isReleased = true

            if (deleteTexture) {
                GLES20.glDeleteTextures(0, intArrayOf(textureId), 0)
            }
            texture.release()
            surface.release()
            Logger.d(Constants.TAG, "RenderableTexture monitor release $deleteTexture $this");
        }
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        needUpdateTex.incrementAndGet()
        onFrameAvailableListener?.onFrameAvailable(surfaceTexture)
    }

    override fun equals(other: Any?): Boolean {
        if (other == null || other !is RenderableTexture) {
            return false
        }
        return other.textureId == textureId
    }

    override fun hashCode(): Int {
        return textureId
    }

    override fun toString(): String {
        return super.toString() + " $textureId width $width height $height available frames $needUpdateTex"
    }
}