/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import android.animation.ValueAnimator
import android.database.ContentObserver
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.ImageView
import me.kavishdevar.librepods.R
import kotlin.math.min

/** The original silent Pro animation, with static art until a frame is actually rendered. */
internal class PairingArtwork(private val container: View) : TextureView.SurfaceTextureListener {
    private val context = container.context
    private val video = container.findViewById<TextureView>(R.id.popup_video)
    private val poster = container.findViewById<ImageView>(R.id.popup_artwork)
    private var active = false
    private var animatedModel = false
    private var failed = false
    private var fallbackArt = 0
    private var rendering = false
    private var observing = false
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private val motion = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = updatePlayback()
    }

    init {
        video.isOpaque = false
        video.surfaceTextureListener = this
        container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) = stop()
        })
    }

    fun start(modelId: Int) {
        stop()
        fallbackArt = OverlayArtwork.pairing(modelId)
        poster.setImageResource(fallbackArt)
        animatedModel = modelId in setOf(0x0e20, 0x1420, 0x2420, 0x2720)
        active = true
        failed = false
        if (animatedModel) {
            observing = runCatching {
                context.contentResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, motion)
            }.isSuccess
        }
        updatePlayback()
    }

    fun stop() {
        active = false
        if (observing) runCatching { context.contentResolver.unregisterContentObserver(motion) }
        observing = false
        releasePlayer()
        video.visibility = View.GONE
    }

    private fun updatePlayback() {
        if (!active || !animatedModel || failed || !ValueAnimator.areAnimatorsEnabled()) {
            releasePlayer()
            if (fallbackArt != 0) poster.setImageResource(fallbackArt)
            video.visibility = View.GONE
            return
        }
        video.visibility = View.VISIBLE
        if (player == null) poster.setImageResource(R.drawable.pairing_spin_poster)
        val texture = video.surfaceTexture ?: return
        if (player != null) return
        val next = try { MediaPlayer() } catch (error: RuntimeException) {
            fail(error)
            return
        }
        player = next
        try {
            surface = Surface(texture)
            next.setSurface(surface)
            next.setVolume(0f, 0f)
            next.isLooping = true
            next.setOnPreparedListener {
                if (player === next && active) {
                    try {
                        fit(next.videoWidth, next.videoHeight)
                        next.start()
                    } catch (error: RuntimeException) { fail(error) }
                }
            }
            next.setOnInfoListener { _, info, _ ->
                if (player === next && active && info == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    rendering = true
                }
                false
            }
            next.setOnErrorListener { _, _, _ ->
                if (player === next) fail()
                true
            }
            next.setDataSource(context, Uri.parse("android.resource://${context.packageName}/${R.raw.pairing_spin}"))
            next.prepareAsync()
        } catch (error: Exception) { fail(error) }
    }

    private fun fail(error: Exception? = null) {
        Log.w("PairingArtwork", "Cannot play pairing animation; showing static artwork", error)
        failed = true
        releasePlayer()
        if (fallbackArt != 0) poster.setImageResource(fallbackArt)
        video.visibility = View.GONE
    }

    private fun releasePlayer() {
        val previous = player
        player = null
        rendering = false
        runCatching { previous?.release() }
        surface?.release()
        surface = null
        poster.visibility = View.VISIBLE
    }

    private fun fit(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || video.width <= 0 || video.height <= 0) return
        val scale = min(video.width.toFloat() / width, video.height.toFloat() / height)
        val contentWidth = width * scale
        val contentHeight = height * scale
        video.setTransform(Matrix().apply {
            setScale(contentWidth / video.width, contentHeight / video.height)
            postTranslate((video.width - contentWidth) / 2f, (video.height - contentHeight) / 2f)
        })
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) = updatePlayback()
    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        player?.let { fit(it.videoWidth, it.videoHeight) }
    }
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        releasePlayer()
        return true
    }
    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        if (active && rendering && player != null && texture === video.surfaceTexture) poster.visibility = View.INVISIBLE
    }
}
