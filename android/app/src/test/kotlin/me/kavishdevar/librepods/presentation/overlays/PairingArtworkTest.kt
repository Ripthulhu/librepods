/* SPDX-License-Identifier: GPL-3.0-or-later */
package me.kavishdevar.librepods.presentation.overlays

import android.app.Application
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.TextureView
import android.view.View
import android.widget.ImageView
import me.kavishdevar.librepods.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PairingArtworkTest {
    private val context get() = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_LibrePods)
    private lateinit var container: View
    private lateinit var artwork: PairingArtwork
    private lateinit var video: TextureView
    private lateinit var poster: ImageView
    private var media: MediaPlayer? = null

    @Before fun setUp() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        container = LayoutInflater.from(context).inflate(R.layout.popup_window, null)
            .findViewById(R.id.popup_artwork_container)
        video = container.findViewById(R.id.popup_video)
        poster = container.findViewById(R.id.popup_artwork)
        artwork = PairingArtwork(container)
        ShadowMediaPlayer.setCreateListener { player, _ -> media = player }
        val source = DataSource.toDataSource(context,
            Uri.parse("android.resource://${context.packageName}/${R.raw.pairing_spin}"))
        ShadowMediaPlayer.addMediaInfo(source, ShadowMediaPlayer.MediaInfo(5_933, 100))
    }

    @After fun tearDown() {
        artwork.stop()
        video.surfaceTexture?.release()
        ShadowMediaPlayer.setCreateListener(null)
    }

    @Test fun staticFallbackCoversOtherModelsAndReducedMotionChangesWhileOpen() {
        artwork.start(0x1320)
        assertNotNull(poster.drawable)
        assertEquals(OverlayArtwork.pairing(0x1320), shadowOf(poster.drawable).createdFromResId)
        assertEquals(View.GONE, video.visibility)
        artwork.start(0x2720)
        assertEquals(View.VISIBLE, video.visibility)
        assertEquals(View.VISIBLE, poster.visibility)
        assertEquals(R.drawable.pairing_spin_poster, shadowOf(poster.drawable).createdFromResId)
        assertNull(media)

        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.GONE, video.visibility)
        assertEquals(View.VISIBLE, poster.visibility)
        assertEquals(OverlayArtwork.pairing(0x2720), shadowOf(poster.drawable).createdFromResId)
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE, video.visibility)
        assertEquals(R.drawable.pairing_spin_poster, shadowOf(poster.drawable).createdFromResId)

        artwork.stop()
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.GONE, video.visibility)
    }

    @Test fun silentLoopKeepsPosterUntilRenderedAndFallsBackOnDecodeError() {
        video.setSurfaceTexture(SurfaceTexture(0))
        artwork.start(0x2720)
        val player = requireNotNull(media)
        val playback = shadowOf(player)
        assertEquals(0f, playback.leftVolume, 0.0001f)
        assertEquals(0f, playback.rightVolume, 0.0001f)
        assertTrue(player.isLooping)
        assertEquals(View.VISIBLE, poster.visibility)
        assertEquals(R.drawable.pairing_spin_poster, shadowOf(poster.drawable).createdFromResId)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertTrue(player.isPlaying)
        assertEquals("The trimmed clip must start at its first visible frame", 0, player.currentPosition)
        artwork.onSurfaceTextureUpdated(requireNotNull(video.surfaceTexture))
        assertEquals(View.VISIBLE, poster.visibility)
        playback.invokeInfoListener(MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START, 0)
        assertEquals(View.VISIBLE, poster.visibility)
        artwork.onSurfaceTextureUpdated(requireNotNull(video.surfaceTexture))
        assertEquals(View.INVISIBLE, poster.visibility)

        playback.invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)
        assertEquals(ShadowMediaPlayer.State.END, playback.state)
        assertEquals(View.VISIBLE, poster.visibility)
        assertEquals(OverlayArtwork.pairing(0x2720), shadowOf(poster.drawable).createdFromResId)
        assertEquals(View.GONE, video.visibility)
    }

    @Test fun stoppingDuringPreparationRetiresLateCallbacksAndReleasesPlayer() {
        video.setSurfaceTexture(SurfaceTexture(0))
        artwork.start(0x2720)
        val player = requireNotNull(media)
        val playback = shadowOf(player)
        val prepared = playback.onPreparedListener
        artwork.stop()
        prepared.onPrepared(player)
        playback.invokeInfoListener(MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START, 0)
        artwork.onSurfaceTextureUpdated(requireNotNull(video.surfaceTexture))
        assertEquals(ShadowMediaPlayer.State.END, playback.state)
        assertEquals(View.VISIBLE, poster.visibility)
        assertEquals(View.GONE, video.visibility)
    }
}
