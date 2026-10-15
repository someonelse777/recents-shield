package com.recentsshield

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** An app the user wants hidden, with every name its Recents card might show. */
data class HiddenApp(val pkg: String, val labels: List<String>)

/**
 * One card on the Recents screen.
 *  - [node] is the whole card (Samsung: id/taskView, desc = app name). Its live bounds and
 *    name are re-read every frame while Recents is open, because the list recycles card
 *    views as you scroll (a view can switch to a different app).
 *  - The preview starts [sceneTopRatio] of the card's height below the card's top
 *    (the strip above it is where the badge sits).
 *  - [icon] is the app badge on the card's top edge (Samsung: id/task_icon).
 */
class Card(
    val node: AccessibilityNodeInfo,
    val sceneTopRatio: Float,
    val icon: AccessibilityNodeInfo?,
    val label: String,
    val drawingOrder: Int,
    /** Where the card and badge were when scanned, used if a live re-read fails. */
    val scanBounds: Rect,
    val scanIconBounds: Rect?,
    val scannedAt: Long = android.os.SystemClock.uptimeMillis(),
)

/**
 * Reads the home-screen app's accessibility tree, decides whether the Recents screen is
 * showing, and lists its cards.
 *
 * Samsung One UI (from a real dump):
 *   RecyclerView
 *     ViewGroup id=taskView desc="AP News"         <- whole card incl. badge
 *       View id=task_scene                          <- the preview
 *       View id=task_icon desc="Advanced options, AP News, Button"
 */
object RecentsScanner {

    data class Result(val isRecents: Boolean, val cards: List<Card>)

    private val MARKER_IDS = setOf("clear_all", "close_all", "clear_all_button", "overview_panel", "recents_view", "taskView")
    private val MARKER_TEXTS = listOf("close all", "clear all")
    private val CARD_IDS = setOf("taskView", "task_view", "task")
    private val SCENE_IDS = setOf("task_scene", "snapshot", "task_thumbnail", "thumbnail")
    private val ICON_IDS = setOf("task_icon", "icon")

    private const val MAX_DEPTH = 40

    fun scan(root: AccessibilityNodeInfo, hidden: List<HiddenApp>): Result {
        val screen = Rect().also { root.getBoundsInScreen(it) }
        val nodes = ArrayList<AccessibilityNodeInfo>(128)
        collect(root, nodes, 0)

        if (nodes.none { isMarker(it) }) return Result(false, emptyList())
        if (hidden.isEmpty()) return Result(true, emptyList())

        val cardNodes = nodes.filter { shortId(it) in CARD_IDS }
        val cards = if (cardNodes.isNotEmpty()) {
            cardNodes.map { cardFrom(it) }
        } else {
            genericCards(nodes, hidden, screen)
        }
        return Result(true, cards)
    }

    private fun cardFrom(card: AccessibilityNodeInfo): Card {
        var scene: AccessibilityNodeInfo? = null
        var icon: AccessibilityNodeInfo? = null
        for (i in 0 until card.childCount) {
            val c = try { card.getChild(i) } catch (_: Exception) { null } ?: continue
            when (shortId(c)) {
                in SCENE_IDS -> if (scene == null) scene = c
                in ICON_IDS -> if (icon == null) icon = c
            }
        }
        var ratio = 0f
        val cb = Rect().also { card.getBoundsInScreen(it) }
        if (scene != null) {
            val sb = Rect().also { scene.getBoundsInScreen(it) }
            if (cb.height() > 0) ratio = ((sb.top - cb.top).toFloat() / cb.height()).coerceIn(0f, 0.3f)
        }
        val ib = icon?.let { i -> Rect().also { i.getBoundsInScreen(it) }.takeIf { it.width() > 0 && it.height() > 0 } }
        return Card(card, ratio, icon, labelOf(card), card.drawingOrder, cb, ib)
    }

    fun labelOf(n: AccessibilityNodeInfo): String = (n.contentDescription ?: n.text)?.toString()?.trim() ?: ""

    fun isHidden(label: String, hidden: List<HiddenApp>): Boolean =
        label.isNotEmpty() && hidden.any { app -> app.labels.any { labelMatches(label, it) } }

    /** Fallback for layouts without known ids: walk up from a matching label to a card-sized view. */
    private fun genericCards(nodes: List<AccessibilityNodeInfo>, hidden: List<HiddenApp>, screen: Rect): List<Card> {
        val out = LinkedHashMap<AccessibilityNodeInfo, Card>()
        val b = Rect()
        for (n in nodes) {
            val s = (n.contentDescription ?: n.text)?.toString()?.trim() ?: continue
            val app = hidden.firstOrNull { a -> a.labels.any { labelMatches(s, it) } } ?: continue
            var cur: AccessibilityNodeInfo? = n
            var depth = 0
            while (cur != null && depth < 8) {
                val found: AccessibilityNodeInfo = cur
                found.getBoundsInScreen(b)
                if (b.height() >= screen.height() * 0.2f && b.width() <= screen.width() * 0.9f) {
                    out.getOrPut(found) { Card(found, 0f, null, app.labels.first(), found.drawingOrder, Rect(b), null) }
                    break
                }
                cur = try { cur.parent } catch (_: Exception) { null }
                depth++
            }
        }
        return out.values.toList()
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

    private fun shortId(n: AccessibilityNodeInfo): String? = n.viewIdResourceName?.substringAfter(":id/")

    private fun isMarker(n: AccessibilityNodeInfo): Boolean {
        if (shortId(n) in MARKER_IDS) return true
        val t = n.text?.toString()?.trim()?.lowercase()
        val d = n.contentDescription?.toString()?.trim()?.lowercase()
        return MARKER_TEXTS.any { it == t || it == d }
    }

    /** "Claude", "Claude, 2 of 5", "Claude. Locked" match "Claude"; "Chrome Beta" doesn't match "Chrome". */
    fun labelMatches(value: String, label: String): Boolean {
        if (label.isBlank() || value.isBlank()) return false
        if (value.equals(label, ignoreCase = true)) return true
        if (!value.startsWith(label, ignoreCase = true)) return false
        val rest = value.substring(label.length)
        val first = rest.first()
        return first in ",.:;|-–—(" || (first == ' ' && rest.trimStart().firstOrNull()?.let { it in ",.:;|-–—(" } == true)
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
            sb.append(" z=").append(n.drawingOrder)
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
