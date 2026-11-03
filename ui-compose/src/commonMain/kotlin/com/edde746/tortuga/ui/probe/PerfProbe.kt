package com.edde746.tortuga.ui.probe

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.TraversableNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.findNearestAncestor
import androidx.compose.ui.node.invalidateDraw

/**
 * Test hook: when a screen first draws its content and when a list of shelves comes to rest (`shelves.settled`,
 * rememberShelfNavigation), so a harness driving the real app (the Android perf runs) times what
 * the user waits for. Provided through [LocalPerfProbe] only in those runs; otherwise [perfMark] and [perfImage] cost
 * one null check per draw.
 */
fun interface PerfProbe {
    /** [name] drew with [detail]: `k=v` pairs separated by spaces, values without spaces; empty for none. */
    fun mark(name: String, detail: String)
}

val LocalPerfProbe = staticCompositionLocalOf<PerfProbe?> { null }

/** Home's mark: the Board with its first shelf's cards drawn. Android's perf build reports the launch fully drawn at the first. */
const val HOME_CONTENT_MARK = "home.content"

/** Discover's mark: the grid drawn with its items. */
const val DISCOVER_CONTENT_MARK = "discover.content"

/** Detail's marks: the title's header (a catalog item's preview, else the title's), the whole title, a series' season's episodes. */
const val DETAIL_HEADER_MARK = "detail.header"
const val DETAIL_CONTENT_MARK = "detail.content"
const val DETAIL_EPISODES_MARK = "detail.episodes"

/**
 * Marks [name] with [detail] to the [LocalPerfProbe], if any, at this element's first draw while [ready], after its
 * content has drawn: once per [detail] for as long as the element lives (a lazy list reusing it starts over). A screen
 * puts it on its root; the screen's images take the screen from its mark ([perfImage]).
 */
fun Modifier.perfMark(name: String, detail: String, ready: Boolean): Modifier = this then PerfMarkElement(name, detail, ready)

/**
 * Marks `image` to the [LocalPerfProbe], if any, at this image's first draw after it [loaded], once for as long as the
 * element lives (a lazy list reusing it starts over), with the screen it is on, by the nearest [perfMark] around it:
 * `screen=home` ([HOME_CONTENT_MARK]), `discover` ([DISCOVER_CONTENT_MARK]) or `detail` (Detail's marks), else `screen=other`.
 */
fun Modifier.perfImage(loaded: Boolean): Modifier = this then PerfImageElement(loaded)

private data class PerfMarkElement(val name: String, val detail: String, val ready: Boolean) : ModifierNodeElement<PerfMarkNode>() {
    override fun create() = PerfMarkNode(name, detail, ready)

    override fun update(node: PerfMarkNode) = node.update(name, detail, ready)
}

private class PerfMarkNode(var name: String, private var detail: String, private var ready: Boolean) :
    Modifier.Node(),
    DrawModifierNode,
    CompositionLocalConsumerModifierNode,
    TraversableNode {
    private var probe: PerfProbe? = null

    /** The detail marked last under [name]; null before the first mark. */
    private var marked: String? = null

    override val traverseKey: Any get() = PerfMarkKey

    // Without a probe an update redraws nothing; with one, [update] asks for the draw that marks.
    override val shouldAutoInvalidate: Boolean get() = false

    fun update(name: String, detail: String, ready: Boolean) {
        if (name != this.name) {
            this.name = name
            marked = null
        }
        this.detail = detail
        this.ready = ready
        if (probe != null && ready && detail != marked) invalidateDraw()
    }

    override fun onAttach() {
        probe = currentValueOf(LocalPerfProbe)
    }

    override fun onDetach() {
        probe = null
    }

    override fun onReset() {
        marked = null
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val probe = probe ?: return
        if (ready && detail != marked) {
            marked = detail
            probe.mark(name, detail)
        }
    }
}

/** The [TraversableNode.traverseKey] of [PerfMarkNode], for [PerfImageNode] to find the screen it is on. */
private object PerfMarkKey

private data class PerfImageElement(val loaded: Boolean) : ModifierNodeElement<PerfImageNode>() {
    override fun create() = PerfImageNode(loaded)

    override fun update(node: PerfImageNode) = node.update(loaded)
}

private class PerfImageNode(private var loaded: Boolean) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private var probe: PerfProbe? = null
    private var marked = false

    // As PerfMarkNode's.
    override val shouldAutoInvalidate: Boolean get() = false

    fun update(loaded: Boolean) {
        this.loaded = loaded
        if (probe != null && loaded && !marked) invalidateDraw()
    }

    override fun onAttach() {
        probe = currentValueOf(LocalPerfProbe)
    }

    override fun onDetach() {
        probe = null
    }

    override fun onReset() {
        marked = false
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val probe = probe ?: return
        if (loaded && !marked) {
            marked = true
            val screen = when ((findNearestAncestor(PerfMarkKey) as PerfMarkNode?)?.name) {
                HOME_CONTENT_MARK -> "home"
                DISCOVER_CONTENT_MARK -> "discover"
                DETAIL_HEADER_MARK, DETAIL_CONTENT_MARK, DETAIL_EPISODES_MARK -> "detail"
                else -> "other"
            }
            probe.mark("image", "screen=$screen")
        }
    }
}
