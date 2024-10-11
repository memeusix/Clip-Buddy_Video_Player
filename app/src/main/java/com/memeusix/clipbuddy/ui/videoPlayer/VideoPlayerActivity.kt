package com.memeusix.clipbuddy.ui.videoPlayer

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView.ControllerVisibilityListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.memeusix.clipbuddy.R
import com.memeusix.clipbuddy.base.BaseActivity
import com.memeusix.clipbuddy.data.model.VideoModel
import com.memeusix.clipbuddy.databinding.ActivityVideoPlayerBinding
import com.memeusix.clipbuddy.ui.videoPlayer.dialog.AudioTrackDialog
import com.memeusix.clipbuddy.utils.FilePathUtils
import com.memeusix.clipbuddy.utils.formatDuration
import com.memeusix.clipbuddy.utils.gone
import com.memeusix.clipbuddy.utils.parcelize
import com.memeusix.clipbuddy.utils.visible
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

@UnstableApi
class VideoPlayerActivity : BaseActivity() {
    private lateinit var binding: ActivityVideoPlayerBinding
    private lateinit var player: ExoPlayer
    private lateinit var gestureDetector: GestureDetector
    private lateinit var trackSelector: DefaultTrackSelector

    /**
     *  Coroutine job
     */
    private var hideJob: Job? = null

    /**
     * Views
     */
    private lateinit var seekBar: DefaultTimeBar
    private lateinit var backBtn: ImageButton
    private lateinit var prevBtn: ImageButton
    private lateinit var reverseBtn: ImageButton
    private lateinit var playPauseBtn: ImageButton
    private lateinit var forwardBtn: ImageButton
    private lateinit var nextBtn: ImageButton
    private lateinit var videoTitle: TextView

    /**
     * Fast-forward and rewind intervals (in milliseconds)
     */
    private val fastForwardMs = 10_000 // 10 seconds
    private val rewindMs = 10_000 // 10 seconds

    /**
     * Volume and brightness Controls
     */
    private lateinit var audioManager: AudioManager
    private var brightness: Float = 0f
    private var maxVolume: Int = 0
    private var currentVolume: Int = 0

    /**
     * extra variables
     */
    private var accumulatedVolumeDelta = 0f
    private var initialSec = 0

    /**
     * video parameters
     */
    private var playWhenReady = true
    private var videoUri: String? = null
    private var currentPlaybackPosition: Long = 0
    private var selectedAudioTrack: Tracks.Group? = null
    private var selectedSubTitle : Tracks.Group? = null


    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)


        /**
         * Views initialization
         */
        seekBar = findViewById(R.id.exo_progress)
        backBtn = findViewById(R.id.btnBackArrow)

        nextBtn = findViewById(R.id.exo_next)
        reverseBtn = findViewById(R.id.exo_rew)
        playPauseBtn = findViewById(R.id.exo_play_pause)
        forwardBtn = findViewById(R.id.exo_ffwd)
        prevBtn = findViewById(R.id.exo_prev)

        videoTitle = findViewById(R.id.txtVideoName)


        /**
         * hiding system ui
         */
        FilePathUtils.hideSystemUI(window)
        binding.playerView.setControllerVisibilityListener(ControllerVisibilityListener { visibility ->
            if (visibility == View.VISIBLE) {
                // Show system UI (status bar and nav bar)
                FilePathUtils.showSystemUI(window)
            } else {
                // Hide system UI (status bar and nav bar)
                FilePathUtils.hideSystemUI(window)
            }
        })

        /**
         * Initialize audio manager for controlling volume
         */
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        /**
         *  Initialize Gesture Detector
         */
        gestureDetector = GestureDetector(this, GestureListener())

        /**
         * Detect Touch events on the player view
         */
        binding.playerView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        /**
         * keep the screen on while playing
         */
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val args = intent.extras
        args?.let {
            val video = it.parcelize<VideoModel>("VIDEO_DETAILS")
            videoUri = video?.path
            videoTitle.text = video?.name
        }
    }

    override fun onStart() {
        super.onStart()
        playerSetUp()
        initializePlayerView()
    }

    private fun playerSetUp() {
        trackSelector = DefaultTrackSelector(applicationContext)

        val renderersFactory = NextRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setRenderersFactory(renderersFactory)
            .setAudioAttributes(getAudioAttributes(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(playbackListener)

        // Initialize player with media item
        if (videoUri != null) {
            val mediaItem = MediaItem.fromUri(Uri.parse(videoUri))
            player.setMediaItem(mediaItem)
            player.playWhenReady = playWhenReady
            player.prepare()
        }
        player.seekTo(currentPlaybackPosition)
    }

    private val playbackListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY && player.playWhenReady) {
                seekBar.setDuration(player.duration)
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            seekBar.setPosition(player.currentPosition)
        }

        override fun onTracksChanged(tracks: Tracks) {
            super.onTracksChanged(tracks)
            selectedAudioTrack?.let { switchTrack(it, C.TRACK_TYPE_AUDIO) }
        }

        override fun onPlayerError(error: PlaybackException) {
            super.onPlayerError(error)
            val alertDialog = MaterialAlertDialogBuilder(this@VideoPlayerActivity).apply {
                setTitle("Error : Can't play")
                setMessage(error.message ?: "Unknown error")
                setNegativeButton("Exit") { _, _ ->
                    finish()
                }
            }.create()
            alertDialog.show()
            super.onPlayerError(error)
        }
    }

    private fun getAudioAttributes(): AudioAttributes {
        return AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
    }

    private fun initializePlayerView() {
        binding.playerView.player = player
        binding.playerView.setControllerAnimationEnabled(false)

        forwardBtn.setOnClickListener {
            forwardWithValue()
        }

        reverseBtn.setOnClickListener {
            rewindWithValues()
        }

        videoTitle.setOnClickListener {
            val availableTracks: List<Tracks.Group> =
                player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            AudioTrackDialog(
                context = this,
                type = "",
                title = "Subtitles",
                list = availableTracks,
                selectedItem = availableTracks.find { it.isSelected }?.mediaTrackGroup?.id,
                onItemSelected = { selectedSubtitle ->
                    selectedSubTitle = selectedSubtitle
                    switchTrack(selectedSubtitle, C.TRACK_TYPE_TEXT)
                }
            )
        }

        backBtn.setOnClickListener {
            val availableTracks: List<Tracks.Group> =
                player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            AudioTrackDialog(
                context = this,
                type = "",
                title = "Audio Tracks",
                list = availableTracks,
                selectedItem = availableTracks.find { it.isSelected }?.mediaTrackGroup?.id,
                onItemSelected = { selectedTracks ->
                    // Build the track selection parameters
                    if (selectedTracks != null) {
                        selectedAudioTrack = selectedTracks
                        switchTrack(selectedTracks, C.TRACK_TYPE_AUDIO)
                    }
                }
            )
        }
    }

    private fun switchTrack(selectedTracks: Tracks.Group?, type: Int) {
        // Get the current track selection parameters
        val parameters = player.trackSelectionParameters

        val newTrackSelectionParameters = parameters.buildUpon().apply {
            // Modify the track selection parameters to switch the audio track
            if (selectedTracks == null) {
                this.setTrackTypeDisabled(type, true)
            } else {
                val trackSelectionOverride = TrackSelectionOverride(
                    selectedTracks.mediaTrackGroup,
                    0
                )
                this.setTrackTypeDisabled(type, false) // Ensure audio is enabled
                this.setOverrideForType(trackSelectionOverride) // Override with the selected track
            }
        }.build()

        // Apply the new parameters to the player without stopping the video playback
        player.trackSelectionParameters = newTrackSelectionParameters
    }


    private fun rewindWithValues() {
        val newPosition = player.currentPosition - rewindMs
        if (newPosition < 0) {
            player.seekTo(0)
        } else {
            player.seekTo(newPosition)
        }
        initialSec += rewindMs
        infoLayout('-')
    }

    private fun forwardWithValue() {
        val newPosition = player.currentPosition + fastForwardMs
        if (newPosition < player.duration) {
            player.seekTo(newPosition)
        } else {
            player.seekTo(player.duration)
        }
        initialSec += fastForwardMs
        infoLayout('+')
    }


    override fun onStop() {
        currentPlaybackPosition = player.currentPosition
        selectedAudioTrack =
            player.currentTracks.groups.find { it.isSelected && it.type == C.TRACK_TYPE_AUDIO }
        playWhenReady = player.playWhenReady
        hideJob?.cancel()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        player.removeListener(playbackListener)
        player.release()
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
        hideJob?.cancel()
        player.removeListener(playbackListener)
    }


    inner class GestureListener : GestureDetector.SimpleOnGestureListener() {

        private val SWIPE_THRESHOLD = 100

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (binding.playerView.isControllerFullyVisible) {
                binding.playerView.hideController()
            } else {
                binding.playerView.showController()
            }
            return true
        }


        override fun onDoubleTap(e: MotionEvent): Boolean {
            val width = binding.playerView.width
            if (e.x < width / 2) {
                // rewind  video
                rewindWithValues()
            } else {
                // forward video
                forwardWithValue()
            }
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            e1?.let { initialEvent ->
                val deltaX = e2.x.minus(initialEvent.x) ?: 0f
                val deltaY = e2.y.minus(initialEvent.y) ?: 0f

                if (abs(deltaX) > abs(deltaY) && abs(deltaX) > SWIPE_THRESHOLD) {
//                    if (deltaX > SWIPE_THRESHOLD) {
//                        forwardVideo()
//                    } else {
//                        rewindVideo()
//                    }
                } else if (abs(deltaY) > SWIPE_THRESHOLD) {
                    if (initialEvent.x > binding.playerView.width / 2) {
                        adjustVolume(distanceY)
                    } else {
                        adjustBrightness(distanceY)
                    }
                }
            }
            return true
        }
    }


    private fun forwardVideo() {
        val currentPosition = player.currentPosition
        val forwardPosition = currentPosition + 1000

        player.seekTo(forwardPosition)
        initialSec += 1000
        infoLayout('+')
    }

    private fun rewindVideo() {
        val currentPosition = player.currentPosition
        val rewindPosition = currentPosition - 1000
        player.seekTo(rewindPosition)
        initialSec += 1000
        infoLayout('-')
    }

    private fun infoLayout(sign: Char) {

        binding.infoText.text = player.currentPosition.formatDuration()
        if (player.currentPosition <= 0) {
            binding.infoSubtext.text = getString(R.string._0_00)
        } else {
            binding.infoSubtext.text =
                getString(R.string._info_sec, sign, initialSec.toLong().formatDuration())
        }

        showGestureLayout(binding.infoLayout)

    }

    private fun adjustVolume(deltaY: Float) {
        // Get the current and max volume from the AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        var currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        // Sensitivity scale factor (higher sensitivity for slow swipes)
        val sensitivity = 2f  // Increase this value for more sensitivity

        // Accumulate the deltaY to handle slow swiping
        accumulatedVolumeDelta += (deltaY * sensitivity)

        // Only change the volume when the accumulated change is significant
        val volumeDelta = (accumulatedVolumeDelta / binding.playerView.height * maxVolume).toInt()

        if (volumeDelta != 0) {
            // Adjust the current volume and clamp it within the valid range
            currentVolume += volumeDelta
            currentVolume = currentVolume.coerceIn(0, maxVolume)

            // Set the new volume
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, currentVolume, 0)

            // Update the volume UI
            showGestureLayout(binding.volumeGestureLayout)

            binding.volumeProgressText.text =
                ((currentVolume.toFloat() / maxVolume) * 100).toInt().toString()
            binding.volumeProgressBar.progress =
                ((currentVolume.toFloat() / maxVolume) * 100).toInt()

            // Reset the accumulated delta after applying the change
            accumulatedVolumeDelta = 0f
        }

    }

    private fun adjustBrightness(distanceY: Float) {
        val layoutParams = window.attributes
        brightness = layoutParams.screenBrightness
        if (brightness < 0) brightness = 0.5f

        val brightnessDelta = distanceY / binding.playerView.height
        brightness += brightnessDelta
        brightness = brightness.coerceIn(0f, 1f)

        layoutParams.screenBrightness = brightness
        window.attributes = layoutParams

        showGestureLayout(binding.brightnessGestureLayout)

        binding.brightnessProgressText.text = (brightness * 100).toInt().toString()
        binding.brightnessProgressBar.progress = (brightness * 100).toInt()

    }


    private fun showGestureLayout(visibleLayout: View) {
        binding.volumeGestureLayout.gone()
        binding.brightnessGestureLayout.gone()
        binding.infoLayout.gone()

        visibleLayout.visible()

        // Hide the layout after a delay
        hideJob?.cancel()
        hideJob = lifecycleScope.launch {
            delay(500)
            initialSec = 0
            visibleLayout.gone()
        }
    }


    companion object {
        private val TAG = VideoPlayerActivity::class.java.name
    }
}