package build.terrynamic.opendisplay.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import build.terrynamic.opendisplay.CursorUi
import build.terrynamic.opendisplay.ReceiverController
import kotlin.math.ceil
import kotlin.math.floor

class VideoSurface(context: Context) : FrameLayout(context), SurfaceHolder.Callback {
    private val surfaceView = SurfaceView(context).also {
        it.holder.addCallback(this)
        it.holder.setFormat(PixelFormat.OPAQUE)
        addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
    private val cursorView = CursorOverlay(context).also {
        addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    var controller: ReceiverController? = null

    var videoWidth: Int = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    var videoHeight: Int = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    private var twoFinger = false
    private var lastPanX = 0f
    private var lastPanY = 0f
    private var lastX = 0.5
    private var lastY = 0.5
    private var primaryId = MotionEvent.INVALID_POINTER_ID

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
    }

    fun updateCursor(cursor: CursorUi) {
        cursorView.setCursor(cursor)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        val rect = VideoGeometry.aspectFit(width, height, videoWidth, videoHeight)
        surfaceView.layout(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
        cursorView.layout(0, 0, width, height)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            holder.surface.setFrameRate(60f, android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
        }
        controller?.attachSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        controller?.attachSurface(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        controller?.attachSurface(null)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val receiver = controller ?: return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                twoFinger = false
                primaryId = event.getPointerId(0)
                normalize(event.x, event.y)?.let { (x, y) ->
                    lastX = x
                    lastY = y
                    receiver.sendTouch("began", x, y)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    if (!twoFinger) receiver.sendTouch("cancelled", lastX, lastY)
                    twoFinger = true
                    lastPanX = midX(event)
                    lastPanY = midY(event)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (twoFinger || event.pointerCount >= 2) {
                    twoFinger = true
                    if (event.pointerCount >= 2) {
                        val mx = midX(event)
                        val my = midY(event)
                        val scale = videoScale()
                        if (scale > 0f) {
                            val dx = (mx - lastPanX) / scale
                            val dy = (my - lastPanY) / scale
                            if (dx != 0f || dy != 0f) {
                                receiver.sendScroll(dx.toDouble(), dy.toDouble())
                            }
                        }
                        lastPanX = mx
                        lastPanY = my
                    }
                } else {
                    val index = event.findPointerIndex(primaryId).takeIf { it >= 0 } ?: 0
                    normalize(event.getX(index), event.getY(index))?.let { (x, y) ->
                        lastX = x
                        lastY = y
                        receiver.sendTouch("moved", x, y)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!twoFinger) {
                    val index = event.findPointerIndex(primaryId).takeIf { it >= 0 } ?: 0
                    normalize(event.getX(index), event.getY(index))?.let { (x, y) ->
                        receiver.sendTouch("ended", x, y)
                    } ?: receiver.sendTouch("ended", lastX, lastY)
                }
                twoFinger = false
                primaryId = MotionEvent.INVALID_POINTER_ID
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!twoFinger) receiver.sendTouch("cancelled", lastX, lastY)
                twoFinger = false
                primaryId = MotionEvent.INVALID_POINTER_ID
            }
        }
        return true
    }

    private fun midX(event: MotionEvent) =
        if (event.pointerCount >= 2) (event.getX(0) + event.getX(1)) / 2f else event.x

    private fun midY(event: MotionEvent) =
        if (event.pointerCount >= 2) (event.getY(0) + event.getY(1)) / 2f else event.y

    private fun videoScale(): Float {
        if (videoWidth <= 0 || width <= 0) return 0f
        return VideoGeometry.aspectFit(width, height, videoWidth, videoHeight).width / videoWidth
    }

    private fun normalize(px: Float, py: Float): Pair<Double, Double>? {
        return VideoGeometry.normalizeTouch(px, py, width, height, videoWidth, videoHeight)
    }
}

private class CursorOverlay(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var cursor = CursorUi()

    fun setCursor(value: CursorUi) {
        val old = bounds()
        cursor = value
        invalidateTransition(old, bounds())
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = cursor.bitmap ?: return
        if (bitmap.isRecycled || !cursor.visible) return
        val rect = bounds() ?: return
        canvas.drawBitmap(bitmap, null, rect, paint)
    }

    private fun bounds(): RectF? {
        if (!cursor.visible || cursor.bitmap == null || videoParentSize() == null) return null
        val (vw, vh) = videoParentSize()!!
        val videoRect = VideoGeometry.aspectFit(width, height, vw, vh)
        val rect = VideoGeometry.cursorRect(
            videoRect = videoRect,
            x = cursor.x,
            y = cursor.y,
            normalizedWidth = cursor.normalizedWidth,
            normalizedHeight = cursor.normalizedHeight,
            anchorX = cursor.anchorX,
            anchorY = cursor.anchorY,
        )
        return RectF(rect.left, rect.top, rect.right, rect.bottom)
    }

    private fun videoParentSize(): Pair<Int, Int>? {
        val parent = parent as? VideoSurface ?: return null
        if (parent.videoWidth <= 0 || parent.videoHeight <= 0) return null
        return parent.videoWidth to parent.videoHeight
    }

    private fun invalidateTransition(oldBounds: RectF?, newBounds: RectF?) {
        val dirty = when {
            oldBounds == null -> newBounds
            newBounds == null -> oldBounds
            else -> RectF(
                minOf(oldBounds.left, newBounds.left),
                minOf(oldBounds.top, newBounds.top),
                maxOf(oldBounds.right, newBounds.right),
                maxOf(oldBounds.bottom, newBounds.bottom),
            )
        } ?: return
        postInvalidateOnAnimation(
            (floor(dirty.left) - 2).toInt().coerceAtLeast(0),
            (floor(dirty.top) - 2).toInt().coerceAtLeast(0),
            (ceil(dirty.right) + 2).toInt().coerceAtMost(width),
            (ceil(dirty.bottom) + 2).toInt().coerceAtMost(height),
        )
    }
}
