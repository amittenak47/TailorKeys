package app.tailorkeys

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Visual state only: never feeds position back into the cursor joystick. Units are px (or dp in tests). */
internal class CursorPointer(
    private val zone: CursorZone, private val pressX: Float, pressY: Float, radius: Float, private val pressedAt: Long,
) {
    var radius = min(radius, (zone.bottom - zone.top) / 2).coerceAtLeast(0f)
        private set
    var y = pressY
        private set
    var minX = 0f
        private set
    var maxX = 0f
        private set
    private var origin = pressX
    private var fingerY = pressY
    private var velocityY = 0f
    var x: Float
        private set
    var velocity = 0f
        private set
    private var releasedAt: Long? = null
    private var releaseAlpha = 0f
    val released get() = releasedAt != null

    init {
        // Reserve a square around the disk, checking all row vertices within its height. This also keeps the
        // ring inside tilted edges. Extremely narrow zones scale the disk down rather than covering a key.
        if (!fit(this.radius, pressY)) {
            var low = 0f
            var high = this.radius
            repeat(16) {
                val mid = (low + high) / 2
                if (fit(mid, pressY)) low = mid else high = mid
            }
            this.radius = low
            fit(low, pressY)
        }
        origin = pressX.coerceIn(minX, maxX)
        x = origin
    }

    private fun fit(r: Float, atY: Float): Boolean {
            y = atY.coerceIn(zone.top + r, zone.bottom - r)
            var left = max(CursorZone.edgeAt(zone.left, y - r), CursorZone.edgeAt(zone.left, y + r))
            var right = min(CursorZone.edgeAt(zone.right, y - r), CursorZone.edgeAt(zone.right, y + r))
            for (p in zone.left) if (p.y > y - r && p.y < y + r) left = max(left, p.x)
            for (p in zone.right) if (p.y > y - r && p.y < y + r) right = min(right, p.x)
            zone.excluded?.let {
                if (y + r > it.top && y - r < it.bottom) {
                    if (pressX < (it.left + it.right) / 2) right = min(right, it.left)
                    else left = max(left, it.right)
                }
            }
            minX = left + r
            maxX = right - r
            return minX <= maxX
    }

    fun follow(y: Float) { if (!released) fingerY = y }

    fun target(push: Float): Float {
        val p = push.coerceIn(-1f, 1f)
        return origin + p * if (p < 0) origin - minX else maxX - origin
    }

    /** Exact critically damped spring, capped on target changes so it never coasts past the target. */
    fun step(push: Float, seconds: Float, reducedMotion: Boolean): Boolean {
        val targetY = fingerY.coerceIn(zone.top + radius, zone.bottom - radius)
        val dt = seconds.coerceIn(0f, 0.04f)
        val dy = y - targetY
        if (velocityY * dy > 0) velocityY = 0f
        val cy = velocityY + 16f * dy
        val decayY = exp(-16f * dt)
        val nextY = if (reducedMotion) targetY else (targetY + (dy + cy * dt) * decayY).coerceIn(min(y, targetY), max(y, targetY))
        velocityY = if (reducedMotion) 0f else (velocityY - 16f * cy * dt) * decayY
        // Refit at the animated height as tilted edges and the switch can change the available width.
        if (!fit(radius, nextY)) {
            var low = 0f; var high = radius
            repeat(16) { val mid = (low + high) / 2; if (fit(mid, nextY)) low = mid else high = mid }
            radius = low; fit(radius, nextY)
        }
        origin = pressX.coerceIn(minX, maxX)
        x = x.coerceIn(minX, maxX)
        val verticalMoving = !reducedMotion && (abs(targetY - y) >= 0.01f || abs(velocityY) >= 0.1f)
        if (!verticalMoving) { velocityY = 0f; fit(radius, targetY) }
        val target = target(push)
        if (reducedMotion) { x = target; velocity = 0f; return false }
        val delta = x - target
        if (velocity * delta > 0) velocity = 0f
        val c = velocity + 16f * delta
        val decay = exp(-16f * dt)
        val next = target + (delta + c * dt) * decay
        velocity = (velocity - 16f * c * dt) * decay
        x = next.coerceIn(min(x, target), max(x, target)).coerceIn(minX, maxX)
        if (abs(target - x) < 0.01f && abs(velocity) < 0.1f) {
            x = target; velocity = 0f
            return verticalMoving
        }
        return true
    }

    fun alpha(now: Long, reducedMotion: Boolean): Float {
        if (reducedMotion) return if (released) 0f else 1f
        val lift = releasedAt
        return if (lift == null) ((now - pressedAt) / 120f).coerceIn(0f, 1f)
        else releaseAlpha * (1f - (now - lift) / 150f).coerceIn(0f, 1f)
    }

    fun release(now: Long, reducedMotion: Boolean) {
        releaseAlpha = alpha(now, reducedMotion)
        releasedAt = now
    }

    fun fading(now: Long, reducedMotion: Boolean) = !reducedMotion &&
        if (released) alpha(now, false) > 0f else now - pressedAt < 120
}
