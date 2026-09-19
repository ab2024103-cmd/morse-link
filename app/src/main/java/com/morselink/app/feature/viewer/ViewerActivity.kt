package com.morselink.app.feature.viewer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.ColorDrawable
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import com.morselink.app.R

/**
 * Built-in viewers (mlogs3): image viewer with pinch zoom, video player and
 * music player — platform APIs only, so the APK stays small.
 *
 * Tap a photo/video/song's "Open" (or long-press it in Photos/Videos/Music)
 * and it plays inside MorseLink; no external app needed.
 */
class ViewerActivity : Activity() {

    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var seekBar: SeekBar? = null
    private var curText: TextView? = null
    private var durText: TextView? = null
    private var playButton: ImageButton? = null
    private var seekDragging = false

    private val ticker = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && p.isPlaying) {
                if (!seekDragging) seekBar?.progress = p.currentPosition
                curText?.text = fmtTime(p.currentPosition)
            }
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.getStringExtra("uri")
        val mime = intent.getStringExtra("mime") ?: ""
        val title = intent.getStringExtra("title") ?: ""
        if (uri.isNullOrBlank()) {
            finish()
            return
        }
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        when {
            mime.startsWith("image/") -> root.addView(buildImageView(uri, title))
            mime.startsWith("video/") -> root.addView(buildVideoView(uri, title))
            mime.startsWith("audio/") -> root.addView(buildAudioView(uri, title))
            else -> {
                Toast.makeText(this, R.string.history_file_missing, Toast.LENGTH_SHORT).show()
                finish()
                return
            }
        }
        setContentView(root)
    }

    // ---------------- top bar ----------------

    private fun topBar(title: String, root: FrameLayout) {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(px(12), px(10), px(8), px(10))
        bar.setBackgroundColor(0x66000000)

        val label = TextView(this)
        label.text = title
        label.setTextColor(Color.WHITE)
        label.textSize = 15f
        label.isSingleLine = true
        label.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        bar.addView(
            label,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val close = ImageButton(this)
        close.setImageResource(R.drawable.ic_close)
        close.setBackgroundColor(Color.TRANSPARENT)
        close.setColorFilter(Color.WHITE)
        close.contentDescription = getString(android.R.string.cancel)
        close.setOnClickListener { finish() }
        close.setPadding(px(10), px(6), px(10), px(6))
        bar.addView(close)

        root.addView(
            bar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
        )
    }

    // ---------------- image ----------------

    private fun buildImageView(uri: String, title: String): View {
        val root = FrameLayout(this)
        val zoom = ZoomableImageView(this)
        root.addView(
            zoom,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        topBar(title, root)
        Thread {
            // Sampled stream decode (memory-safe, handles any storage).
            val bmp = decodeSampledUri(uri, 2048)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (bmp != null) {
                    zoom.setImageBitmap(bmp)
                } else {
                    Toast.makeText(this, R.string.history_file_missing, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }.start()
        return root
    }

    /** Bounds-first sampled decode via a ContentResolver stream. */
    private fun decodeSampledUri(uriString: String, target: Int): android.graphics.Bitmap? {
        return try {
            val uri = Uri.parse(uriString)
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= target &&
                bounds.outHeight / (sample * 2) >= target
            ) {
                sample *= 2
            }
            contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(
                    it, null,
                    android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                )
            }
        } catch (_: OutOfMemoryError) {
            // Retry smaller before giving up.
            if (target > 256) decodeSampledUri(uriString, target / 2) else null
        } catch (_: Exception) {
            null
        }
    }

    // ---------------- video ----------------

    private fun buildVideoView(uri: String, title: String): View {
        val root = FrameLayout(this)
        val video = VideoView(this)
        root.addView(
            video,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )
        topBar(title, root)

        val controller = MediaController(this)
        controller.setAnchorView(video)
        video.setMediaController(controller)
        video.setVideoURI(Uri.parse(uri))
        video.setOnPreparedListener { mp ->
            mp.isLooping = false
            // Size the view to the video's aspect ratio ourselves — VideoView's
            // own measure misplaces wide/tall videos on some devices (mlogs3).
            val vw = mp.videoWidth
            val vh = mp.videoHeight
            if (vw > 0 && vh > 0 && root.width > 0 && root.height > 0) {
                val scale = minOf(root.width.toFloat() / vw, root.height.toFloat() / vh)
                val lp = video.layoutParams
                lp.width = (vw * scale).toInt()
                lp.height = (vh * scale).toInt()
                video.layoutParams = lp
            }
            video.start()
            controller.show(4000)
        }
        video.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, R.string.history_file_missing, Toast.LENGTH_SHORT).show()
            finish()
            true
        }
        video.setOnCompletionListener { controller.show(0) }
        return root
    }

    // ---------------- music ----------------

    private fun buildAudioView(uri: String, title: String): View {
        val root = FrameLayout(this)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(px(28), 0, px(28), 0)

        val art = ImageView(this)
        art.setImageResource(R.drawable.ic_cat_music)
        art.setColorFilter(0xFF1FA36B.toInt())
        val lp = LinearLayout.LayoutParams(px(140), px(140))
        lp.topMargin = px(90)
        lp.bottomMargin = px(28)
        col.addView(art, lp)

        val name = TextView(this)
        name.text = title
        name.textColorWhite()
        name.textSize = 17f
        name.gravity = Gravity.CENTER
        name.setPadding(0, 0, 0, px(4))
        col.addView(
            name,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val sub = TextView(this)
        sub.text = getString(R.string.viewer_now_playing)
        sub.setTextColor(0xFF9C9CA4.toInt())
        sub.textSize = 13f
        sub.gravity = Gravity.CENTER
        col.addView(
            sub,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val seek = SeekBar(this)
        seekBar = seek
        col.addView(
            seek,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = px(26) }
        )

        val times = LinearLayout(this)
        times.orientation = LinearLayout.HORIZONTAL
        val cur = TextView(this); cur.textColorWhite(); cur.textSize = 12f
        val dur = TextView(this); dur.textColorWhite(); dur.textSize = 12f
        times.addView(
            cur,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        times.addView(dur)
        curText = cur
        durText = dur
        col.addView(
            times,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val play = ImageButton(this)
        playButton = play
        play.setImageResource(R.drawable.ic_pause)
        play.setBackgroundColor(0xFF1FA36B.toInt())
        play.setColorFilter(Color.WHITE)
        play.setPadding(px(20), px(20), px(20), px(20))
        val plp = LinearLayout.LayoutParams(px(92), px(92))
        plp.topMargin = px(30)
        plp.bottomMargin = px(60)
        col.addView(play, plp)

        root.addView(
            col,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        topBar(title, root)

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    player?.seekTo(progress)
                    cur.text = fmtTime(progress)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                seekDragging = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                seekDragging = false
            }
        })
        play.setOnClickListener {
            val p = player ?: return@setOnClickListener
            if (p.isPlaying) {
                p.pause()
                play.setImageResource(R.drawable.ic_play)
            } else {
                p.start()
                play.setImageResource(R.drawable.ic_pause)
            }
        }

        try {
            val mp = MediaPlayer()
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC)
            mp.setDataSource(this, Uri.parse(uri))
            mp.setOnPreparedListener { m ->
                seek.max = m.duration
                dur.text = fmtTime(m.duration)
                m.start()
                handler.post(ticker)
            }
            mp.setOnCompletionListener {
                play.setImageResource(R.drawable.ic_play)
                seek.progress = seek.max
            }
            mp.setOnErrorListener { _, _, _ ->
                Toast.makeText(this, R.string.history_file_missing, Toast.LENGTH_SHORT).show()
                finish()
                true
            }
            mp.prepareAsync()
            player = mp
        } catch (_: Exception) {
            Toast.makeText(this, R.string.history_file_missing, Toast.LENGTH_SHORT).show()
            finish()
        }
        return root
    }

    // ---------------- lifecycle ----------------

    override fun onPause() {
        super.onPause()
        player?.takeIf { it.isPlaying }?.pause()
        playButton?.setImageResource(R.drawable.ic_play)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(ticker)
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
    }

    // ---------------- helpers ----------------

    private fun px(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun TextView.textColorWhite() {
        setTextColor(Color.WHITE)
    }

    private fun fmtTime(ms: Int): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    companion object {
        fun start(context: Context, uri: String, mime: String, title: String) {
            val intent = Intent(context, ViewerActivity::class.java)
            intent.putExtra("uri", uri)
            intent.putExtra("mime", mime)
            intent.putExtra("title", title)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(intent)
        }
    }

    // ---------------- zoomable image ----------------

    /** Pinch-zoom + pan + double-tap, matrix-based. */
    private class ZoomableImageView(context: Context) : ImageView(context) {

        private var baseScale = 1f
        private var zoom = 1f
        private var tx = 0f
        private var ty = 0f
        private var lastX = 0f
        private var lastY = 0f

        private val matrix = Matrix()

        private val scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val old = zoom
                    zoom = (zoom * detector.scaleFactor).coerceIn(1f, 10f)
                    val ratio = zoom / old
                    // Keep the content point under the fingers still: with the
                    // content centered at (view center + t), the pan update is
                    // t' = (focus - center) * (1 - ratio) + t * ratio.
                    tx = (detector.focusX - width / 2f) * (1 - ratio) + tx * ratio
                    ty = (detector.focusY - height / 2f) * (1 - ratio) + ty * ratio
                    apply()
                    return true
                }
            }
        )

        private val gestureDetector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (zoom > 1.05f) {
                        fit()
                    } else {
                        val ratio = 2.5f / 1f
                        zoom = 2.5f
                        tx = (e.x - width / 2f) * (1 - ratio)
                        ty = (e.y - height / 2f) * (1 - ratio)
                        clamp()
                        apply()
                    }
                    return true
                }
            }
        )

        init {
            scaleType = ScaleType.MATRIX
        }

        override fun setImageDrawable(d: android.graphics.drawable.Drawable?) {
            super.setImageDrawable(d)
            // The drawable can arrive long after layout (async decode) — without
            // this, the matrix stays identity and the photo draws at 1:1 from
            // the top-left corner, cropped off-screen (mlogs3).
            fit()
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            if (changed) fit()
        }

        private fun fit() {
            zoom = 1f
            tx = 0f
            ty = 0f
            apply()
        }

        private fun apply() {
            val d = drawable ?: return
            val bw = d.intrinsicWidth
            val bh = d.intrinsicHeight
            if (bw <= 0 || bh <= 0 || width <= 0 || height <= 0) return
            baseScale = minOf(width.toFloat() / bw, height.toFloat() / bh)
            val s = baseScale * zoom
            matrix.reset()
            matrix.postScale(s, s)
            // Center the scaled content at (view center + pan offset) — the
            // content can never end up anchored to a corner.
            matrix.postTranslate(
                width / 2f + tx - bw * s / 2f,
                height / 2f + ty - bh * s / 2f
            )
            imageMatrix = matrix
        }

        private fun clamp() {
            val d = drawable ?: return
            val s = baseScale * zoom
            val w = d.intrinsicWidth * s
            val h = d.intrinsicHeight * s
            val maxX = (w - width).coerceAtLeast(0f) / 2f
            val maxY = (h - height).coerceAtLeast(0f) / 2f
            tx = tx.coerceIn(-maxX, maxX)
            ty = ty.coerceIn(-maxY, maxY)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            gestureDetector.onTouchEvent(event)
            scaleDetector.onTouchEvent(event)
            if (!scaleDetector.isInProgress) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lastX = event.x
                        lastY = event.y
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (zoom > 1.01f) {
                            tx += event.x - lastX
                            ty += event.y - lastY
                            clamp()
                            apply()
                        }
                        lastX = event.x
                        lastY = event.y
                    }

                    MotionEvent.ACTION_UP -> {
                        if (zoom <= 1.01f) fit()
                    }
                }
            }
            return true
        }
    }
}
