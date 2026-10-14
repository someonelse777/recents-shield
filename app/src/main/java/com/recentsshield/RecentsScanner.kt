package com.recentsshield

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** An app the user wants hidden, with every name its Recents card might show. */
data class HiddenApp(val pkg: String, val labels: List<String>)

/**
 * Reads the home-screen app's accessibility tree, decides whether the Recents
 * screen is showing, and finds the on-screen bounds of the cards for hidden apps.
 */
object RecentsScanner {

    data class Result(
        val isRecents: Boolean,
        val rects: List<Rect>,
        val matched: List<String>,
    )

    // View ids / texts that only exist on the Recents screen (Samsung One UI and AOSP).
    private val MARKER_IDS = listOf("clear_all", "close_all", "clear_all_button", "overview_panel", "recents_view")
    private val MARKER_TEXTS = listOf("close all", "clear all")

    private const val MAX_DEPTH = 40
    private const val PAD_PX = 12

    fun scan(root: AccessibilityNodeInfo, hidden: List<HiddenApp>): Result {
        val screen = Rect().also { root.getBoundsInScreen(it) }
        val nodes = ArrayList<AccessibilityNodeInfo>(256)
        collect(root, nodes, 0)

        if (nodes.none { isMarker(it) }) return Result(false, emptyList(), emptyList())
        if (hidden.isEmpty()) return Result(true, emptyList(), emptyList())

        val rects = ArrayList<Rect>()
        val matched = ArrayList<String>()
        for (n in nodes) {
            val app = matchApp(n, hidden) ?: continue
            val card = cardBounds(n, nodes, screen) ?: continue
            if (rects.none { it == card }) {
                rects.add(card)
                matched.add(app.labels.first())
            }
        }
        return Result(true, rects, matched)
    }

    private fun collect(n: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (depth > MAX_DEPTH) return
        if (!n.isVisibleToUser) return
        out.add(n)
        for (i in 0 until n.childCount) {
            val c = try { n.getChild(i) } catch (_: Exception) { null } ?: continue
            collect(c, out, depth + 1)
        }
    }

    private fun isMarker(n: AccessibilityNodeInfo): Boolean {
        val id = n.viewIdResourceName
        if (id != null) {
            val shortId = id.substringAfter(":id/")
            if (MARKER_IDS.any { it == shortId }) return true
        }
        val t = n.text?.toString()?.trim()?.lowercase()
        val d = n.contentDescription?.toString()?.trim()?.lowercase()
        return MARKER_TEXTS.any { it == t || it == d }
    }

    private fun matchApp(n: AccessibilityNodeInfo, hidden: List<HiddenApp>): HiddenApp? {
        val strings = listOfNotNull(n.contentDescription?.toString(), n.text?.toString())
        if (strings.isEmpty()) return null
        for (s in strings) {
            val v = s.trim()
            if (v.isEmpty()) continue
            for (app in hidden) {
                if (app.labels.any { labelMatches(v, it) }) return app
            }
        }
        return null
    }

    /** "Claude", "Claude, 2 of 5", "Claude. Locked" match "Claude"; "Chrome Beta" doesn't match "Chrome". */
    private fun labelMatches(value: String, label: String): Boolean {
        if (label.isBlank()) return false
        if (value.equals(label, ignoreCase = true)) return true
        if (!value.startsWith(label, ignoreCase = true)) return false
        val rest = value.substring(label.length)
        val first = rest.first()
        return first in ",.:;|-–—(" || (first == ' ' && rest.trimStart().firstOrNull()?.let { it in ",.:;|-–—(" } == true)
    }

    private fun isCardSized(b: Rect, screen: Rect): Boolean {
        val sw = screen.width().toFloat()
        val sh = screen.height().toFloat()
        if (sw <= 0 || sh <= 0) return false
        val area = b.width().toFloat() * b.height()
        return b.width() >= sw * 0.2f && b.height() >= sh * 0.2f && area <= sw * sh * 0.8f
    }

    private fun isTooBig(b: Rect, screen: Rect): Boolean {
        val sw = screen.width().toFloat()
        val sh = screen.height().toFloat()
        return b.width().toFloat() * b.height() > sw * sh * 0.8f
    }

    private fun cardBounds(n: AccessibilityNodeInfo, all: List<AccessibilityNodeInfo>, screen: Rect): Rect? {
        // 1) The label is usually inside the card: walk up to the first card-sized parent.
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        val b = Rect()
        while (cur != null && depth < 8) {
            cur.getBoundsInScreen(b)
            if (isCardSized(b, screen)) return padded(b, screen)
            if (isTooBig(b, screen)) break
            cur = try { cur.parent } catch (_: Exception) { null }
            depth++
        }

        // 2) Otherwise the label sits just above the card (One UI puts the icon + name
        //    over each card). Pick the card-sized node right under it that overlaps it most.
        val label = Rect().also { n.getBoundsInScreen(it) }
        val maxGap = (screen.height() * 0.15f).toInt()
        var best: Rect? = null
        var bestOverlap = 0
        val c = Rect()
        for (node in all) {
            node.getBoundsInScreen(c)
            if (!isCardSized(c, screen)) continue
            val below = c.top >= label.top - 4 && c.top - label.bottom <= maxGap
            if (!below) continue
            val overlap = minOf(c.right, label.right) - maxOf(c.left, label.left)
            if (overlap > bestOverlap) {
                bestOverlap = overlap
                best = Rect(c)
            }
        }
        if (best != null) {
            best.union(label) // cover the name/icon too
            return padded(best, screen)
        }
        return null
    }

    private fun padded(r: Rect, screen: Rect): Rect {
        val p = Rect(r)
        p.inset(-PAD_PX, -PAD_PX)
        p.intersect(screen)
        return p
    }

    /** Text dump of the tree, for the "Copy diagnostics" button. */
    fun dump(root: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        var lines = 0
        val b = Rect()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_DEPTH || lines > 1500) return
            n.getBoundsInScreen(b)
            sb.append("  ".repeat(depth))
                .append(n.className?.toString()?.substringAfterLast('.') ?: "?")
            n.viewIdResourceName?.let { sb.append(" id=").append(it.substringAfter(":id/")) }
            n.text?.let { sb.append(" text=\"").append(it).append('"') }
            n.contentDescription?.let { sb.append(" desc=\"").append(it).append('"') }
            sb.append(' ').append(b.toShortString())
            if (!n.isVisibleToUser) sb.append(" (offscreen)")
            sb.append('\n')
            lines++
            for (i in 0 until n.childCount) {
                val c = try { n.getChild(i) } catch (_: Exception) { null } ?: continue
                walk(c, depth + 1)
            }
        }
        walk(root, 0)
        return sb.toString()
    }
}
