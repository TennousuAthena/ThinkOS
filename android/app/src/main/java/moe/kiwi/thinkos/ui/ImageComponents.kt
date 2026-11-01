package moe.kiwi.thinkos.ui

import android.content.Context
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import moe.kiwi.thinkos.anim.Motion
import moe.kiwi.thinkos.core.Attention
import moe.kiwi.thinkos.image.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Future
import kotlin.math.abs

/** API 3 factory. Plugins declare data; gestures, pixels and motion remain OS-owned. */
internal class ImageComponents(private val loader: ImageLoader, private val authorize: (String, String) -> String? = { _, _ -> null }) {
    private val positions = mutableMapOf<String, String>()
    private data class Poster(val grant: ImageOwner, val src: String, val width: Int, val height: Int, val bitmap: Bitmap)
    // Immutable decoded pixels only: no Views, requests or disk leases survive page removal.
    private val posters = LinkedHashMap<String, Poster>(4, .75f, true)
    private var posterBytes = 0
    private fun remember(key: String, grant: ImageOwner, photo: Photo, width: Int, height: Int, bitmap: Bitmap) {
        posters.remove(key)?.let { posterBytes -= it.bitmap.allocationByteCount }
        // Mutable library files are refreshed asynchronously when their retained page resumes.
        if (photo.src.startsWith('/') || photo.src.startsWith("file:")) return
        if (bitmap.allocationByteCount > 12 * 1024 * 1024) return
        posters[key] = Poster(grant, photo.src, width, height, bitmap)
        posterBytes += bitmap.allocationByteCount
        while (posters.size > 4 || posterBytes > 12 * 1024 * 1024) {
            posterBytes -= posters.remove(posters.keys.first())!!.bitmap.allocationByteCount
        }
    }
    fun create(context: Context, pluginId: String, definition: JSONObject): RenderComponent? =
        if (definition.optString("type") in setOf("image", "carousel")) Picture(context, pluginId, definition) else null

    private inner class Picture(private val context: Context, private val owner: String, private val definition: JSONObject) : RenderComponent {
        private val handler = Handler(Looper.getMainLooper())
        private val carousel = definition.optString("type") == "carousel"
        private val key = "$owner:$definition"
        private val exportAlias = moe.kiwi.thinkos.plugin.PublicData.imageAlias(definition)
        /** A public export's images load under its provider's grant; nothing else crosses plugins. */
        private fun authority() = if (exportAlias == null) owner else authorize(owner, exportAlias).orEmpty()
        private val sequence = PhotoSequence().apply { current = positions[key] }
        private val failed = hashSetOf<String>()
        private var grant: ImageOwner? = null
        private var foreground = false
        private var attention = Attention.AWAKE
        private var serial = 0
        private var pending: Future<*>? = null
        private val touchConfig = ViewConfiguration.get(context)
        private val pointer = PhotoDrag(touchConfig.scaledTouchSlop.toFloat(), touchConfig.scaledPagingTouchSlop.toFloat(),
            700 * context.resources.displayMetrics.density)
        private var settling: ValueAnimator? = null
        private var settleGeneration = 0
        private inner class Layer(val view: FrameLayout, val image: ImageView, val frame: ImageGeometry.Frame, val asset: LoadedImage)
        private inner class Browse(val direction: Int, val origin: String?, var target: Photo?) {
            var offset = 0f
            var released = false
            var commit = false
            var layer: Layer? = null
            var placeholder: FrameLayout? = null
        }
        private val layers = mutableSetOf<Layer>()
        private val placeholders = mutableSetOf<FrameLayout>()
        private var current: Layer? = null
        private var incoming: Layer? = null
        private var browse: Browse? = null
        private var shown: Photo? = null
        private var interval = 30_000L
        private var duration = 800L
        private var speed = 45f
        private var panDuration = 12_000L
        private var panDirection = "random"
        private var panLayer: Layer? = null
        private var panTurn: Runnable? = null
        private val panEase = AccelerateDecelerateInterpolator()
        private var fit = "pan"
        private var pan = true
        private var transition = "fade"
        private var caption = "auto"
        private var captionHold = 6_000L
        private var shuffle = false
        private var revealNext = false
        private var reduced = false
        private var animateScale = 1f
        private fun label(size: Float, accessible: Boolean = true) = TextView(context).apply {
            setTextColor(Color.WHITE); textSize = size; includeFontPadding = false
            setShadowLayer(2f * resources.displayMetrics.density, 0f, 1f, 0x99000000.toInt())
            gravity = Gravity.START or Gravity.TOP
            setLineSpacing(3 * resources.displayMetrics.density, 1f)
            visibility = View.GONE
            if (!accessible) importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val captionView = label(18f, false).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }
        private val creditView = label(12f)
        private val captionBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false; clipToPadding = false
            addView(captionView, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = (8 * resources.displayMetrics.density).toInt()
            })
            addView(creditView, LinearLayout.LayoutParams(-1, -2))
        }
        private val root = object : FrameLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val w = MeasureSpec.getSize(widthMeasureSpec)
                val h = MeasureSpec.getSize(heightMeasureSpec)
                val d = resources.displayMetrics.density
                val edge = minOf((24 * d).toInt(), w / 2, h / 2)
                (captionBlock.layoutParams as FrameLayout.LayoutParams).apply {
                    width = minOf((560 * d).toInt(), w - 2 * edge)
                    leftMargin = edge; rightMargin = edge; bottomMargin = edge
                }
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            }
            override fun onAttachedToWindow() { super.onAttachedToWindow(); reconcile() }
            override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
            override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); reconcile() }
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                if (w != oldw || h != oldh) { stop(); reconcile() }
            }
        }.apply {
            clipChildren = true; clipToPadding = true
            addView(captionBlock, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.START))
        }
        override val view: View get() = root
        override val handlesTap get() = carousel
        override val bottomOverlayInset get() = if (captionBlock.measuredHeight > 0)
            captionBlock.measuredHeight + (captionBlock.layoutParams as FrameLayout.LayoutParams).bottomMargin else 0
        override fun burnInOffset(x: Int, y: Int) {
            captionBlock.translationX = x.toFloat()
            captionBlock.translationY = y.toFloat()
        }
        private val advance = Runnable { if (running() && browse == null) showNext() }
        private val finish = Runnable {
            incoming?.let { layer ->
                layer.view.animate().cancel()
                current?.let(::removeLayer)
                current = layer; incoming = null
                layer.view.apply { alpha = 1f; translationX = 0f; translationY = 0f; scaleX = 1f; scaleY = 1f }
            }
        }
        private val hideCaption = Runnable {
            captionView.animate().alpha(0f).setDuration(300).setInterpolator(Motion.EASE).start()
        }

        override fun bind(snapshot: JSONObject, variant: String, strings: (String) -> String?) {
            fun text(name: String, fallback: String) = if (definition.has(name)) Binding.compile(definition.optString(name)).render(snapshot, variant, strings) else fallback
            fun number(name: String, fallback: Double, low: Double, high: Double) = text(name, "$fallback").toDoubleOrNull()?.takeIf { it.isFinite() }?.coerceIn(low, high) ?: fallback
            val oldSettings = settings()
            interval = (number("interval", 30.0, .1, 86400.0) * 1000).toLong()
            duration = number("duration", 800.0, 0.0, 10000.0).toLong()
            speed = number("pan_speed", 45.0, 1.0, 120.0).toFloat()
            panDuration = (number("pan_seconds", 12.0, .1, 120.0) * 1000).toLong()
            panDirection = text("pan_direction", "random")
            fit = text("fit", "pan"); pan = text("pan", "true") == "true"
            transition = text("transition", "fade"); shuffle = text("shuffle", "false") == "true"
            caption = text("caption", "auto"); captionHold = (number("caption_hold", 6.0, 1.0, 60.0) * 1000).toLong()
            val source = if (carousel) {
                val path = definition.optString("items").removePrefix("{{").removeSuffix("}}").trim()
                PhotoSequence.parse(Binding.lookup(snapshot, path) as? JSONArray)
            } else text("src", "").takeIf { it.isNotBlank() }?.let { listOf(Photo(it, it)) }.orEmpty()
            val nextGrant = loader.grant(authority())
            val revoked = grant !== nextGrant
            val changed = sequence.items.toSet() != source.toSet() || revoked
            grant = nextGrant
            if (changed) failed.clear()
            sequence.update(source, shuffle)
            if (revoked || changed && (browse != null || shown != sequence.selected() || pending != null) || oldSettings != settings()) stop()
            reconcile()
        }
        private fun settings() = listOf(interval, duration, fit, pan, speed, panDuration, panDirection, transition, caption, captionHold, shuffle)
        override fun colors(value: Palette) { root.setBackgroundColor(Color.BLACK) }
        override fun active(value: Boolean) {
            if (foreground == value) return
            val retained = current != null
            foreground = value
            reconcile()
            if (value && retained && running()) resume()
        }
        override fun tick(monotonic: Long, attention: Attention) {
            val scale = Settings.Global.getFloat(root.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f).coerceAtLeast(0f)
            val waking = this.attention == Attention.ASLEEP && attention != Attention.ASLEEP
            val disabled = scale == 0f || !android.animation.ValueAnimator.areAnimatorsEnabled()
            val changed = scale != animateScale || reduced != disabled
            this.attention = attention; animateScale = scale; reduced = disabled
            if (attention == Attention.ASLEEP) { pause(); cancelRequest() }
            else if (changed) { stop(); reconcile() }
            else if (waking) { reconcile(); if (running()) resume() }
            followGrant()
        }
        /** A restart or upgrade changes the grant with no state change to bind, and drops requests unanswered. */
        private fun followGrant() {
            val next = loader.grant(authority())
            if (next === grant) return
            grant = next
            cancelRequest(); failed.clear()
            // What is on screen stays until the plugin is back; only new requests need the grant.
            if (next != null) { reconcile(); if (running()) schedule(incoming ?: current) }
        }
        override fun localeChanged() { stop(); reconcile() }
        private fun scaled(ms: Long) = (ms * animateScale).toLong().coerceAtLeast(1L)
        // Attached previews may decode a still image; only the selected page advances or pans.
        private fun drawable() = root.isAttachedToWindow && root.windowVisibility == View.VISIBLE && root.width > 0 && root.height > 0
        private fun readable() = drawable() && attention != Attention.ASLEEP
        private fun running() = foreground && readable()
        private fun reconcile() {
            if (!drawable()) { stop(); return }
            if (!readable()) { pause(); cancelRequest(); return }
            if (!foreground) pause()
            if (shown == null && pending == null) sequence.selected()?.let(::load)
        }
        private fun resume() {
            handler.removeCallbacks(finish)
            discardBrowse(true); cleanRetired()
            showCaption()
            val layer = incoming ?: current
            (incoming ?: current?.takeIf { it.view.alpha < 1f })?.let {
                val length = if (incoming != null) duration else Motion.NUMBER
                val remaining = (length * (1f - it.view.alpha).coerceIn(0f, 1f)).toLong()
                if (reduced || remaining == 0L) {
                    it.view.alpha = 1f; it.view.translationX = 0f; it.view.scaleX = 1f; it.view.scaleY = 1f
                    finish.run()
                } else {
                    reveal(it, remaining)
                    handler.postDelayed(finish, scaled(remaining))
                }
            }
            layer?.let { startPan(it); schedule(it) }
            shown?.takeIf { it.src.startsWith('/') || it.src.startsWith("file:") }?.let(::load)
        }
        private fun reveal(layer: Layer, millis: Long) {
            layer.view.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f)
                .setDuration(millis).setInterpolator(Motion.EASE).start()
        }
        private fun cancelRequest() { serial++; pending?.cancel(true); pending = null }
        private fun request(photo: Photo, success: (LoadedImage) -> Unit, failure: () -> Unit) {
            val token = ++serial
            pending = loader.load(authority(), photo.src, root.width, root.height) { result ->
                if (token != serial || !readable()) { result.getOrNull()?.close(); return@load }
                pending = null
                result.fold(success, { error ->
                    failed += photo.id
                    android.util.Log.w("ThinkOS", "[image:$owner] ${error.message}")
                    failure()
                })
            }
        }
        private fun showNext() {
            if (pending != null) return
            if (failed.size >= sequence.items.size) failed.clear()
            val next = sequence.next(failed) ?: return
            if (next.id == shown?.id) { revealNext = false; schedule(current); return }
            load(next)
        }
        private fun load(photo: Photo) {
            fun display(decoded: LoadedImage, cached: Boolean = false) {
                handler.removeCallbacks(finish); finish.run(); cleanRetired()
                val layer = createLayer(decoded)
                select(photo, layer)
                if (current == null) {
                    current = layer
                    if (!cached && running() && root.isShown && !reduced && ValueAnimator.areAnimatorsEnabled()) {
                        layer.view.alpha = 0f
                        reveal(layer, Motion.NUMBER)
                    }
                } else {
                    incoming = layer
                    val type = if (reduced) "fade" else if (transition == "random") listOf("fade", "slide", "zoom").random() else transition
                    val ms = if (reduced) 0L else duration
                    layer.view.alpha = 0f
                    if (type == "slide") layer.view.translationX = root.width * .25f * if (rtl()) -1 else 1
                    if (type == "zoom") { layer.view.scaleX = .97f; layer.view.scaleY = .97f }
                    layer.view.animate().alpha(1f).translationX(0f).scaleX(1f).scaleY(1f).setDuration(ms).setInterpolator(Motion.EASE).start()
                    handler.postDelayed(finish, scaled(ms))
                }
                if (running()) { startPan(layer); schedule(layer) }
            }
            val poster = posters[key]?.takeIf {
                it.grant === grant && it.src == photo.src && it.width == root.width && it.height == root.height
            }
            if (current == null && poster != null) display(LoadedImage(poster.bitmap), true)
            else request(photo, { display(it) }, { sequence.next(failed)?.let(::load) })
        }
        private fun rtl() = android.text.TextUtils.getLayoutDirectionFromLocale(java.util.Locale.getDefault()) == View.LAYOUT_DIRECTION_RTL
        private fun createLayer(asset: LoadedImage): Layer {
            val decoded = asset.bitmap
            var frame = ImageGeometry.frame(decoded.width, decoded.height, root.width, root.height,
                fit == "pan" && pan && !reduced, rtl(), interval, speed * root.resources.displayMetrics.density, panDuration)
            if (panDirection == "reverse" || panDirection == "random" && kotlin.random.Random.nextBoolean()) frame = frame.reversed()
            val image = ImageView(root.context).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setImageBitmap(decoded)
                translationX = frame.startX; translationY = frame.startY
                if (frame.moving && frame.width <= 4096 && frame.height <= 4096 &&
                    frame.width.toLong() * frame.height <= 4_000_000) setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }
            val view = FrameLayout(root.context).apply {
                clipChildren = true; setBackgroundColor(Color.BLACK); layoutDirection = View.LAYOUT_DIRECTION_LTR
                addView(image, FrameLayout.LayoutParams(frame.width, frame.height))
            }
            return Layer(view, image, frame, asset).also { layers += it; addLayer(view) }
        }
        private fun addLayer(view: View) {
            root.addView(view, root.indexOfChild(captionBlock), FrameLayout.LayoutParams(-1, -1))
            // A poster can arrive from onSizeChanged, inside layout: unmeasured, this layer would keep a 0x0 frame.
            view.measure(View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, root.width, root.height)
        }
        private fun startPan(layer: Layer) {
            stopPan(); panLayer = layer
            fun leg(frame: ImageGeometry.Frame) {
                if (!running() || panLayer !== layer || !frame.moving) return
                // No listeners/actions: Android can interpolate fractional translations on RenderThread.
                // System animator scale applies here once; only our wakeup uses scaled wall time.
                layer.image.animate().translationX(frame.endX).translationY(frame.endY).setStartDelay(0)
                    .setDuration(frame.panMillis).setInterpolator(panEase).setListener(null).setUpdateListener(null)
                    .withStartAction(null).withEndAction(null).start()
                panTurn = Runnable {
                    if (!carousel || sequence.items.count { it.id !in failed } <= 1) leg(frame.reversed())
                }.also { handler.postDelayed(it, scaled(frame.dwellMillis)) }
            }
            leg(layer.frame)
            if (moe.kiwi.thinkos.BuildConfig.DEBUG && layer.frame.moving) android.util.Log.d("ThinkOS",
                "[image:$owner] native-pan ${layer.frame.startX},${layer.frame.startY}->${layer.frame.endX},${layer.frame.endY} ${layer.frame.panMillis}ms")
        }
        private fun stopPan() {
            panTurn?.let(handler::removeCallbacks); panTurn = null
            panLayer?.image?.animate()?.cancel(); panLayer = null
        }
        private fun schedule(layer: Layer?) {
            handler.removeCallbacks(advance)
            if (running() && carousel && sequence.items.size > 1 && layer != null && browse == null)
                handler.postDelayed(advance, maxOf(interval, scaled(layer.frame.dwellMillis)) + if (incoming != null) scaled(duration) else 0L)
        }
        private fun select(photo: Photo, layer: Layer) {
            sequence.current = photo.id; positions[key] = photo.id; shown = photo
            grant?.let { remember(key, it, photo, root.width, root.height, layer.asset.bitmap) }
            root.contentDescription = photo.title.takeIf(String::isNotBlank)
            showCaption(revealNext); revealNext = false
            if (moe.kiwi.thinkos.BuildConfig.DEBUG) android.util.Log.d("ThinkOS", "[image:$owner] displayed ${photo.id}")
        }
        private fun showCaption(tapped: Boolean = false) {
            handler.removeCallbacks(hideCaption); captionView.animate().cancel()
            captionView.text = shown?.title.orEmpty(); creditView.text = shown?.credit.orEmpty()
            creditView.visibility = if (carousel && creditView.text.isNotEmpty()) View.VISIBLE else View.GONE
            val gap = if (creditView.visibility == View.VISIBLE) (8 * root.resources.displayMetrics.density).toInt() else 0
            (captionView.layoutParams as LinearLayout.LayoutParams).let {
                if (it.bottomMargin != gap) { it.bottomMargin = gap; captionView.layoutParams = it }
            }
            captionView.visibility = if (carousel && caption != "off" && (caption == "auto" || tapped) && captionView.text.isNotEmpty()) View.VISIBLE else View.GONE
            captionView.alpha = 1f
            if (running() && captionView.visibility == View.VISIBLE) handler.postDelayed(hideCaption, captionHold)
        }

        override fun dragVertical(offset: Float): Boolean {
            if (!carousel || !running()) return false
            handler.removeCallbacks(advance)
            var session = browse
            if (session == null) {
                cancelRequest()
                handler.removeCallbacks(finish); finish.run(); cleanRetired()
                failed.clear()
                val direction = if (offset < 0) 1 else -1
                session = Browse(direction, sequence.current, sequence.adjacent(direction)).also { browse = it }
                pointer.begin(0f, offset)
                if (session.target != null) loadPreview(session)
            } else if (session.released) {
                val requestedDirection = if (offset < 0) 1 else -1
                val queued = if (session.commit && session.layer == null && requestedDirection == session.direction)
                    sequence.adjacent(requestedDirection, session.target?.id) else null
                val anchor = session.offset
                val previousCommit = session.commit
                cancelSettling()
                if (queued != null && queued.id != shown?.id) {
                    val origin = session.target?.id
                    discardBrowse(false)
                    session = Browse(requestedDirection, origin, queued).also { it.offset = anchor; browse = it }
                    loadPreview(session)
                }
                session.released = false; session.commit = false
                pointer.begin(anchor, offset, previousCommit, dragResistance(session, anchor))
            }
            val coordinate = pointer.move(offset)
            val position = (coordinate * dragResistance(session, coordinate)).coerceIn(-root.height.toFloat(), root.height.toFloat())
            val direction = pointer.direction(position, session.direction)
            if (direction != session.direction) {
                val origin = session.origin
                discardBrowse(false)
                val target = sequence.adjacent(direction, origin, failed)?.takeIf { it.id != shown?.id }
                session = Browse(direction, origin, target).also { it.offset = position; browse = it }
                if (session.target != null) loadPreview(session)
            }
            session.offset = position
            positionBrowse(session)
            return true
        }
        private fun dragResistance(session: Browse, coordinate: Float): Float {
            val direction = if (coordinate < 0) 1 else -1
            val neighbor = if (direction == session.direction) session.target else sequence.adjacent(direction, session.origin, failed)
            return if (neighbor != null && neighbor.id != shown?.id) 1f else .16f
        }
        private fun positionBrowse(session: Browse) {
            val y = session.offset
            current?.view?.translationY = y
            session.layer?.view?.translationY = y + session.direction * root.height
            session.placeholder?.translationY = y + session.direction * root.height
        }
        private fun loadPreview(session: Browse) {
            val target = session.target ?: return
            session.placeholder = FrameLayout(root.context).apply {
                setBackgroundColor(Color.BLACK)
                addView(ProgressBar(context), FrameLayout.LayoutParams((36 * resources.displayMetrics.density).toInt(),
                    (36 * resources.displayMetrics.density).toInt(), Gravity.CENTER))
                translationY = session.offset + session.direction * root.height
            }.also { placeholders += it; addLayer(it) }
            request(target, { asset ->
                if (browse !== session) { asset.close(); return@request }
                session.placeholder?.let(::removePlaceholder); session.placeholder = null
                session.layer = createLayer(asset)
                positionBrowse(session)
                if (session.released && session.commit) settleBrowse(session, true, 0f)
            }, {
                if (browse !== session) return@request
                session.placeholder?.let(::removePlaceholder); session.placeholder = null
                session.target = sequence.adjacent(session.direction, target.id, failed)
                if (session.target == null) cancelBrowse(true) else loadPreview(session)
            })
        }
        override fun releaseVertical(offset: Float, velocity: Float) {
            val session = browse ?: return
            val cancelled = offset == 0f && velocity == 0f
            if (!cancelled) {
                val coordinate = pointer.move(offset)
                session.offset = (coordinate * dragResistance(session, coordinate)).coerceIn(-root.height.toFloat(), root.height.toFloat())
            }
            positionBrowse(session)
            val speed = velocity.coerceIn(-touchConfig.scaledMaximumFlingVelocity.toFloat(), touchConfig.scaledMaximumFlingVelocity.toFloat())
            val commit = session.target != null && pointer.commit(session.offset, root.height, speed, cancelled)
            settleBrowse(session, commit, if (commit) speed else 0f)
        }
        private fun cancelSettling() {
            settleGeneration++
            val old = settling; settling = null
            old?.cancel()
        }
        private fun settleBrowse(session: Browse, commit: Boolean, velocity: Float) {
            cancelSettling()
            session.released = true; session.commit = commit
            if (!commit) cancelRequest()
            // Keep the existing offset while IO finishes. Returning to zero here would reverse the
            // first few frames of a quick flick before the decoded preview arrived.
            if (commit && session.layer == null) return
            val target = if (commit) -session.direction * root.height.toFloat() else 0f
            if (reduced || !ValueAnimator.areAnimatorsEnabled() || abs(session.offset-target) < .5f) {
                finishBrowse(session); return
            }
            val token = settleGeneration
            val from = session.offset
            settling = Motion.spring(from, target, velocity, { value ->
                if (browse === session && token == settleGeneration) {
                    val arrived = if (target > from) value >= target else value <= target
                    session.offset = if (arrived) target else value.coerceIn(-root.height.toFloat(), root.height.toFloat())
                    positionBrowse(session)
                    if (arrived) finishBrowse(session)
                }
            }) { if (browse === session && token == settleGeneration) finishBrowse(session) }
        }
        private fun finishBrowse(session: Browse) {
            if (browse !== session) return
            cancelSettling(); browse = null
            session.placeholder?.let(::removePlaceholder)
            val layer = session.layer
            val target = session.target
            if (session.commit && layer != null && target != null) {
                current?.let(::removeLayer)
                current = layer; layer.view.translationY = 0f
                select(target, layer); showCaption(true); startPan(layer)
            } else {
                current?.view?.translationY = 0f
                layer?.let(::removeLayer)
            }
            schedule(current)
            if (shown == null && running()) reconcile()
        }
        private fun discardBrowse(resetPosition: Boolean) {
            val session = browse ?: return
            cancelSettling(); cancelRequest(); browse = null
            session.placeholder?.let(::removePlaceholder)
            session.layer?.let(::removeLayer)
            if (resetPosition) current?.view?.translationY = 0f
        }
        private fun cancelBrowse(animate: Boolean) {
            val session = browse ?: return
            if (animate) settleBrowse(session, false, 0f) else discardBrowse(true)
        }
        private fun cleanRetired() {
            layers.toList().filter { it !== current && it !== incoming && it !== browse?.layer }.forEach(::removeLayer)
            placeholders.toList().filter { it !== browse?.placeholder }.forEach(::removePlaceholder)
        }
        init { if (carousel) root.setOnClickListener { tap() } }
        override fun tap(): Boolean {
            if (!carousel) return false
            if (running()) {
                showCaption(true)
                if (browse?.released != true && incoming == null) { cancelBrowse(false); failed.clear(); revealNext = true; showNext() }
            }
            return true
        }
        /** Losing selection pauses work; the on-screen frame belongs to the exit transition. */
        private fun pause() {
            if (shown != null) cancelRequest()
            cancelSettling(); stopPan(); handler.removeCallbacksAndMessages(null)
            layers.forEach { it.view.animate().cancel() }
            captionView.animate().cancel()
        }
        private fun stop() {
            cancelRequest(); cancelSettling(); stopPan(); handler.removeCallbacksAndMessages(null)
            browse = null
            layers.toList().forEach(::removeLayer); placeholders.toList().forEach(::removePlaceholder)
            current = null; incoming = null; shown = null
            revealNext = false
            captionView.animate().cancel(); captionView.visibility = View.GONE; creditView.visibility = View.GONE
        }
        private fun removeLayer(layer: Layer) {
            if (!layers.remove(layer)) return
            if (panLayer === layer) stopPan()
            layer.view.animate().cancel(); layer.image.animate().cancel(); layer.image.setImageDrawable(null)
            root.removeView(layer.view); layer.asset.close()
        }
        private fun removePlaceholder(view: FrameLayout) { placeholders.remove(view); root.removeView(view) }
    }
}
