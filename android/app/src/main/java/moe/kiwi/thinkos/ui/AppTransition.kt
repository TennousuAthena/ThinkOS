package moe.kiwi.thinkos.ui

import android.animation.ValueAnimator
import android.graphics.PointF
import android.os.Handler
import android.view.View
import moe.kiwi.thinkos.anim.Motion

/**
 * Opening an app turns the Start page away like a row of doors hinged on their left edges, the touched tile last, and the app
 * turns in on the same hinge; closing it plays the same thing backwards (ADR-040). The poses are OS vocabulary: a plugin cannot
 * ask for another one, only open or close an app.
 *
 * Everything is a view property animation without listeners, which Android 8.1 can run on the render thread, so a busy
 * main thread (the incoming page is being built) does not stutter it, and the clock keeps its own schedule. The end is a
 * single handler callback.
 */
internal class AppTransition(private val density: Float, private val handler: Handler) {
    private var views = emptyList<View>()
    private var finish: (() -> Unit)? = null
    private val fades = mutableListOf<ValueAnimator>()
    private val end = Runnable { snap() }

    /** The finger-down flip of a tile that would still be running is cut short too: a new transition or touch snaps this one to its end. */
    val running: Boolean get() = finish != null

    /** Milliseconds since the start by which the transition is over; zero when none runs. */
    var millis = 0L
        private set

    /** [out] turns away while [into] turns in. [origin] is where the finger was, if a finger started it. */
    fun launch(out: PageView, into: PageView, origin: PointF?, done: () -> Unit) {
        snap()
        val targets = out.flipTargets(origin)
        swingAway(targets, 0L)
        swingIn(listOf(into), OVERLAP_MILLIS)
        // The Start page's own backdrop goes once the app is on its way in, instead of vanishing when the old page is released.
        fadeBackdrop(out, 255, 0, OVERLAP_MILLIS, IN_MILLIS)
        begin(targets + into, staying = listOf(into), maxOf(lastDelay(targets) + OUT_MILLIS, OVERLAP_MILLIS + IN_MILLIS), done)
    }

    /** [app] turns away while the Start page [tiles] turns back in, the tile the app came from first. */
    fun back(app: PageView, tiles: PageView, origin: PointF?, done: () -> Unit) {
        snap()
        val targets = tiles.flipTargets(origin).reversed()
        app.bringToFront()
        swingAway(listOf(app), 0L)
        swingIn(targets, RETURN_DELAY_MILLIS)
        fadeBackdrop(tiles, 0, 255, 0L, IN_MILLIS)
        begin(targets + app, staying = targets, maxOf(OUT_MILLIS, RETURN_DELAY_MILLIS + lastDelay(targets) + IN_MILLIS), done)
    }

    /** Ends whatever runs at once: every view rests in its final pose and the page that went away is released. */
    fun snap() {
        handler.removeCallbacks(end)
        val done = finish ?: return
        finish = null
        views.forEach { it.animate().cancel(); it.setLayerType(View.LAYER_TYPE_NONE, null) }
        views = emptyList()
        fades.forEach(ValueAnimator::cancel)
        fades.clear()
        millis = 0L
        done()
    }

    private fun begin(touched: List<View>, staying: List<View>, duration: Long, done: () -> Unit) {
        views = touched
        millis = duration
        finish = {
            // The views that stay come to rest as they are drawn everywhere else: upright, opaque, turning about their middle.
            staying.forEach { view ->
                view.background?.alpha = 255
                view.rotationY = 0f; view.alpha = 1f
                view.pivotX = view.width / 2f; view.pivotY = view.height / 2f
            }
            done()
        }
        handler.postDelayed(end, duration + SLACK_MILLIS)
    }

    private fun fadeBackdrop(page: PageView, from: Int, to: Int, after: Long, duration: Long) {
        val backdrop = page.background ?: return
        backdrop.alpha = from
        fades += ValueAnimator.ofInt(from, to).apply {
            startDelay = after
            this.duration = duration
            addUpdateListener { backdrop.alpha = it.animatedValue as Int }
            start()
        }
    }

    private fun lastDelay(targets: List<View>): Long = delay(targets.size - 1)
    private fun delay(index: Int): Long = (index.coerceAtLeast(0) * STAGGER_MILLIS).coerceAtMost(MAX_STAGGER_MILLIS)

    private fun hinge(view: View) {
        view.cameraDistance = density * CAMERA_DP
        view.pivotX = 0f
        view.pivotY = view.height / 2f
    }

    private fun swingAway(targets: List<View>, after: Long) {
        targets.forEachIndexed { index, view ->
            hinge(view)
            view.animate().cancel()
            // A whole page fades as one picture instead of being re-composed child by child at every step.
            if (view is PageView) view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            view.animate().rotationY(ANGLE).alpha(0f).setStartDelay(after + delay(index)).setDuration(OUT_MILLIS)
                .setInterpolator(Motion.EXIT).setListener(null).setUpdateListener(null).withStartAction(null).withEndAction(null).start()
        }
    }

    private fun swingIn(targets: List<View>, after: Long) {
        targets.forEachIndexed { index, view ->
            hinge(view)
            view.animate().cancel()
            view.rotationY = ANGLE
            view.alpha = 0f
            view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            view.animate().rotationY(0f).alpha(1f).setStartDelay(after + delay(index)).setDuration(IN_MILLIS)
                .setInterpolator(Motion.EASE).setListener(null).setUpdateListener(null).withStartAction(null).withEndAction(null).start()
        }
    }

    companion object {
        /** How far a door swings: nearly edge-on, so it is gone before it is flat. */
        const val ANGLE = 84f
        const val OUT_MILLIS = 240L
        const val IN_MILLIS = 320L
        const val STAGGER_MILLIS = 26L
        const val MAX_STAGGER_MILLIS = 180L
        /** The app starts turning in while the last tiles are still leaving. */
        const val OVERLAP_MILLIS = 200L
        const val RETURN_DELAY_MILLIS = 90L
        const val CAMERA_DP = 1600f
        private const val SLACK_MILLIS = 40L
    }
}
