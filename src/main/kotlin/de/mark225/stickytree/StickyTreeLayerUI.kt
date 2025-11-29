package de.mark225.stickytree

import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.event.ChangeListener
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.event.TreeModelEvent
import javax.swing.event.TreeModelListener
import javax.swing.plaf.LayerUI
import javax.swing.tree.TreePath
import javax.swing.JLayer

/**
 * LayerUI that paints sticky ancestor rows for a JTree as a non-layout-affecting overlay.
 *
 * Unlike using JScrollPane columnHeaderView, this overlay does not change the viewport size,
 * preventing scroll jumps and erratic scrollbar behavior.
 */
class StickyTreeLayerUI : LayerUI<JComponent>() {
    private var stickyPaths: List<TreePath> = emptyList()
    private var tree: JTree? = null
    @Volatile private var updateScheduled: Boolean = false
    @Volatile private var adjustingViewport: Boolean = false
    @Volatile private var viewportHooked: Boolean = false

    private val viewportListener = ChangeListener {
        if (!adjustingViewport) {
            scheduleUpdate()
        }
    }
    private val expansionListener = object : TreeExpansionListener {
        override fun treeExpanded(event: TreeExpansionEvent) = scheduleUpdate()
        override fun treeCollapsed(event: TreeExpansionEvent) = scheduleUpdate()
    }
    private val modelListener = object : TreeModelListener {
        override fun treeNodesChanged(e: TreeModelEvent) = scheduleUpdate()
        override fun treeNodesInserted(e: TreeModelEvent) = scheduleUpdate()
        override fun treeNodesRemoved(e: TreeModelEvent) = scheduleUpdate()
        override fun treeStructureChanged(e: TreeModelEvent) = scheduleUpdate()
    }

    // Reference to current layer to trigger repaints from listeners
    private var layer: JLayer<out JComponent>? = null

    override fun installUI(c: JComponent) {
        super.installUI(c)
        @Suppress("UNCHECKED_CAST")
        val l = c as JLayer<JComponent>
        layer = l
        // Find the JTree inside the layer's view hierarchy
        val foundTree = UIUtil.findComponentOfType(l.view, JTree::class.java)
        tree = foundTree
        if (foundTree != null) {
            // attach listeners
            foundTree.addTreeExpansionListener(expansionListener)
            foundTree.model.addTreeModelListener(modelListener)
        } else {
        }
        attachViewportListener()

        scheduleUpdate()
    }

    override fun uninstallUI(c: JComponent) {
        val l = layer
        val t = tree
        try {
            t?.removeTreeExpansionListener(expansionListener)
            t?.model?.removeTreeModelListener(modelListener)
        } catch (_: Throwable) { }
        detachViewportListener()
        tree = null
        layer = null
        super.uninstallUI(c)
    }

    private fun attachViewportListener() {
        if (viewportHooked) return
        val t = tree ?: return
        val sp = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, t) as? JScrollPane
        val vp = sp?.viewport ?: return
        vp.addChangeListener(viewportListener)
        viewportHooked = true
    }

    private fun detachViewportListener() {
        if (!viewportHooked) return
        val t = tree ?: return
        val sp = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, t) as? JScrollPane
        val vp = sp?.viewport ?: return
        try {
            vp.removeChangeListener(viewportListener)
        } catch (_: Throwable) { }
        viewportHooked = false
    }

    private fun computeStickyPaths(tree: JTree, yTop: Int): List<TreePath> {
        if (tree.rowCount == 0 || yTop <= 0) return emptyList()

        // Find the path at/near the viewport top (no overlay considered yet)
        val firstRow = rowAtOrBelowY(tree, yTop)
        val firstPath = tree.getPathForRow(firstRow) ?: return emptyList()

        // Build ordered list of ancestors from root down to parent of firstPath
        val allAncestors = mutableListOf<TreePath>()
        run {
            var p: TreePath? = firstPath.parentPath
            while (p?.parentPath != null) {
                allAncestors.add(0, p)
                p = p.parentPath
            }
            if(tree.getPathForRow(firstRow + 1).parentPath == firstPath){
                allAncestors.addLast(firstPath)
            }
            else if(tree.getPathForRow(firstRow + 1).parentPath != firstPath.parentPath && !stickyPaths.contains(firstPath.parentPath)){
                allAncestors.removeLast();
            }
            val delta = allAncestors.size - stickyPaths.size;
            if(delta > 0){
                val testPath = tree.getPathForRow(firstRow + delta);
                if(testPath != null && testPath.parentPath != allAncestors.last()){
                    allAncestors.clear();
                    allAncestors.addAll(stickyPaths);
                }
            }
        }
        return allAncestors;
    }

    private fun scheduleUpdate() {
        if (updateScheduled) return
        updateScheduled = true
        SwingUtilities.invokeLater {
            updateScheduled = false
            val t = tree ?: return@invokeLater
            val yTop = currentYTop(t)
            val computed = computeStickyPaths(t, yTop)
            if (computed != stickyPaths) {
                stickyPaths = computed
            }
            layer?.repaint()
        }
    }

    /**
     * Returns the index of the tree row that either contains the given y coordinate,
     * or, if y falls in a gap between rows, the first row whose top is at or below y.
     * Falls back to the last row if y is beyond the end.
     */
    private fun rowAtOrBelowY(tree: JTree, yInTree: Int): Int {
        val rowCount = tree.rowCount
        if (rowCount <= 0) return -1
        var y = if (yInTree < 0) 0 else yInTree
        var r = tree.getClosestRowForLocation(0, y).coerceIn(0, rowCount - 1)
        while (true) {
            val b = tree.getRowBounds(r) ?: return r
            // If y is above this row's top, this row is the first at or below y
            if (y < b.y) return r
            // If y is inside this row, return it
            if (y < b.y + b.height) return r
            // y is below this row; advance if possible
            if (r + 1 >= rowCount) return r
            r++
        }
    }

     private fun currentYTop(t: JTree): Int {
        // Convert the layer's visible rect to tree coordinates. This is the most reliable
        // because the viewport's view is the JLayer, not the tree itself. Any insets/offsets
        // between the layer and the tree are accounted for by this conversion.
        val l = layer
        if (l != null) {
            val visInTree = SwingUtilities.convertRectangle(l, l.visibleRect, t)
            if(visInTree.y <= 0) return 0;
            val rowHeight = if(t.rowHeight > 0) t.rowHeight else 24;
            return visInTree.y + stickyPaths.size * rowHeight -1;
        }

        // Fallbacks in case the layer is momentarily null
        val sp = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, t) as? JScrollPane
        val yVp = sp?.viewport?.viewPosition?.y
        if (yVp != null) {
            return yVp
        }
        val yTree = t.visibleRect.y
        return yTree
    }

    override fun paint(g: Graphics, c: JComponent) {
        // Let the default pipeline paint the wrapped view first
        super.paint(g, c)

        val l = layer ?: return
        val tree = this.tree ?: return
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        // Ensure viewport listener is attached even if component hierarchy changed after install
        attachViewportListener()

        // Bootstrap state if empty (e.g., first paint before listeners fire)
        if (stickyPaths.isEmpty()) {
            val yTop = currentYTop(tree)
            val computed = computeStickyPaths(tree, yTop)
            if (computed.isNotEmpty()) {
                stickyPaths = computed
            }
        }

        if (stickyPaths.isEmpty()) return

        // paint sticky overlay at the top of the viewport (fixed position)
        val fallbackRowHeight = if (tree.rowHeight > 0) tree.rowHeight else 24
        // Convert to viewport coordinates: the Graphics here may already be translated by -viewPosition;
        // to be robust across LAFs, explicitly offset by current viewPosition.
        val vp = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, l) as? JScrollPane
        val viewPosY = vp?.viewport?.viewPosition?.y ?: 0
        val gOverlay = g2.create() as Graphics2D
        gOverlay.translate(0, viewPosY)
        val headerStartY = 0
        var y = 0
        val bg = tree.background

        // Background for the sticky area (full width at the header position)
        gOverlay.color = bg
        // Use the actual per-row heights to match computeStickyPaths math
        val totalOverlayHeight = stickyPaths.sumOf { (tree.getPathBounds(it)?.height ?: fallbackRowHeight).coerceAtLeast(1) }
        gOverlay.fillRect(0, headerStartY, c.width, totalOverlayHeight)

        // No debug fill; paint only the normal sticky area content

        val renderer = tree.cellRenderer
        for (path in stickyPaths) {
            val value = path.lastPathComponent
            val isSelected = false
            val isExpanded = tree.isExpanded(path)
            val isLeaf = tree.model.isLeaf(value)
            val row = tree.getRowForPath(path)
            val hasFocus = false

            val comp = renderer.getTreeCellRendererComponent(tree, value, isSelected, isExpanded, isLeaf, row, hasFocus)
            val bounds = tree.getPathBounds(path)
            val bx = (bounds?.x ?: 0)
            // Convert the tree's indentation X to layer coordinates to align renderers
            val x = SwingUtilities.convertPoint(tree, bx, 0, l).x
            var h = (bounds?.height ?: fallbackRowHeight).coerceAtLeast(1)

            if (y > headerStartY) {
                gOverlay.color = MIXED_SEPARATOR
                gOverlay.drawLine(0, y, c.width, y)
            }

            val w = c.width
            comp.setBounds(x, y, (w - x).coerceAtLeast(0), h)
            if (comp is JComponent) {
                comp.isOpaque = true
                comp.background = bg
            }
            // background left gutter
            gOverlay.color = bg
            gOverlay.fillRect(0, y, x.coerceAtLeast(0), h)
            val rg = gOverlay.create(x.coerceAtLeast(0), y, (w - x).coerceAtLeast(0), h)
            comp.paint(rg)
            rg.dispose()

            y += h
        }

        // bottom separator
        if (y > 0) {
            gOverlay.color = MIXED_SEPARATOR
            gOverlay.drawLine(0, y - 1, c.width, y - 1)
        }
        gOverlay.dispose()
    }

    companion object {
        private val MIXED_SEPARATOR = Color(0, 0, 0, 30)
    }
}
