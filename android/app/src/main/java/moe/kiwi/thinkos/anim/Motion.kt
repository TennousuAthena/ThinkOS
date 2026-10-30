package moe.kiwi.thinkos.anim

import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Choreographer
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import moe.kiwi.thinkos.core.Attention
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

object Motion {
    const val PAGE = 320L
    const val VARIANT = 350L
    const val NUMBER = 260L
    const val FEEDBACK_EXIT = 120L
    const val THEME = 2_000L
    const val NIGHT = 1_500L
    const val BREATH = 4_000L
    val EASE = DecelerateInterpolator(1.8f)
    val EXIT = AccelerateInterpolator(1.4f)

    /** Analytic damped spring carries finger velocity into the settling motion. */
    fun spring(from: Float, to: Float, velocity: Float = 0f, update: (Float) -> Unit, end: () -> Unit = {}): ValueAnimator {
        val damping = 0.86
        val frequency = 19.0
        val damped = frequency * sqrt(1 - damping * damping)
        val displacement = (from - to).toDouble()
        val coefficient = (velocity + damping * frequency * displacement) / damped
        return ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 520
            addUpdateListener {
                val seconds = currentPlayTime / 1000.0
                val value = to + exp(-damping * frequency * seconds) * (displacement * cos(damped * seconds) + coefficient * sin(damped * seconds))
                update(value.toFloat())
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!cancelled) { update(to); end() }
                }
            })
            start()
        }
    }
}

/** No update/listener/end actions: API 27 can drive these properties on RenderThread.
 * A single main-thread wakeup per half-cycle reverses direction. */
class PropertyBreath(private val view: View, private val lowAlpha: Float, private val lowScale: Float = 1f, private val highScale: Float = 1f) {
    private val handler = Handler(Looper.getMainLooper())
    private val ease = AccelerateDecelerateInterpolator()
    private var high = false
    var running = false
        private set
    private val next = object : Runnable {
        override fun run() {
            if (!running || !view.isAttachedToWindow) return
            val scale = if (high) highScale else lowScale
            view.animate().alpha(if (high) 1f else lowAlpha).scaleX(scale).scaleY(scale)
                .setStartDelay(0).setDuration(Motion.BREATH / 2).setInterpolator(ease)
                .setListener(null).setUpdateListener(null).withStartAction(null).withEndAction(null).start()
            high = !high
            val durationScale = Settings.Global.getFloat(view.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            handler.postDelayed(this, ((Motion.BREATH / 2) * durationScale).toLong().coerceAtLeast(16))
        }
    }
    fun start() {
        if (running) return
        running = true
        high = false
        next.run()
    }
    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(next)
        view.animate().cancel()
    }
}

class FrameGovernor {
    var attention = Attention.AWAKE
    var reducedMotion = false
    var activeAnimations = false
    val fps: Int
        get() = when {
            reducedMotion -> 5
            attention == Attention.ASLEEP -> if (activeAnimations) 5 else 1
            activeAnimations && attention in setOf(Attention.AWAKE, Attention.ENGAGED) -> 60
            activeAnimations -> 30
            attention == Attention.AMBIENT -> 3
            else -> 1
        }
}

/** The explicit scene clock sleeps when no custom animation needs a frame.
 * Cached View property animations are scheduled by Android's animator system. */
class MotionClock(private val governor: FrameGovernor, private val tick: (Long) -> Unit) : Choreographer.FrameCallback {
    private val choreographer = Choreographer.getInstance()
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private val schedule = Runnable { if (running) choreographer.postFrameCallback(this) }

    fun start() {
        if (!running) { running = true; wake() }
    }
    fun wake() {
        if (!running) return
        handler.removeCallbacks(schedule)
        choreographer.removeFrameCallback(this)
        choreographer.postFrameCallback(this)
    }
    fun stop() {
        running = false
        handler.removeCallbacks(schedule)
        choreographer.removeFrameCallback(this)
    }
    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        tick(frameTimeNanos / 1_000_000L)
        val interval = 1000L / governor.fps
        if (governor.fps == 60) choreographer.postFrameCallback(this)
        else handler.postDelayed(schedule, (interval - 8).coerceAtLeast(1))
    }
}
