package com.looker.droidify.compose.appList

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Where the home's top sheet comes out from: the bottom edge of the header bar, which can slide away
 * as the page scrolls. Hand [root] to the box holding both the screen and the sheet, and [bar] to the
 * header bar.
 */
@Stable
internal class TopSheetAnchor {
    private var rootCoordinates: LayoutCoordinates? = null

    /** The bar's bottom edge in the root box, read only while the sheet lays itself out. */
    var bottom by mutableIntStateOf(0)
        private set

    val root: Modifier = Modifier.onPlaced { rootCoordinates = it }

    val bar: Modifier = Modifier.onGloballyPositioned { bar ->
        val root = rootCoordinates ?: return@onGloballyPositioned
        if (root.isAttached && bar.isAttached) {
            bottom = root.localBoundingBoxOf(bar, clipBounds = false).bottom.roundToInt()
        }
    }
}

/**
 * A sheet that unrolls from under the header bar, over a dimmed page, and rolls back up on a tap
 * outside it, on Back or when pushed back up.
 *
 * Composed only while it shows. The motion is one value read in layer and draw blocks, so it moves
 * and dims the page without recomposing or measuring anything along the way.
 */
@Composable
internal fun HomeTopSheet(
    open: Boolean,
    anchor: TopSheetAnchor,
    onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(open) {
        progress.animateTo(
            targetValue = if (open) 1f else 0f,
            animationSpec = if (open) tween(OPEN_MS, easing = EmphasizedDecelerate) else tween(CLOSE_MS, easing = EmphasizedAccelerate),
        )
    }
    val shown by remember { derivedStateOf { open || progress.value > 0f } }
    BackHandler(enabled = open, onBack = onClose)
    if (!shown) return

    val scope = rememberCoroutineScope()
    // How far a finger has pushed the sheet up, in pixels: 0 or less.
    var pushed by remember { mutableFloatStateOf(0f) }
    var sheetHeight by remember { mutableIntStateOf(1) }
    val scrimColor = MaterialTheme.colorScheme.scrim
    Box(
        modifier = Modifier
            .fillMaxSize()
            .layout { measurable, constraints ->
                val top = anchor.bottom.coerceIn(0, constraints.maxHeight)
                val placeable = measurable.measure(
                    constraints.copy(minHeight = 0, maxHeight = constraints.maxHeight - top),
                )
                layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, top) }
            }
            .clipToBounds(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind { drawRect(scrimColor, alpha = SCRIM_ALPHA * progress.value) }
                .pointerInput(onClose) { detectTapGestures { onClose() } },
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = SHEET_MAX_WIDTH)
                .fillMaxWidth()
                .onPlaced { sheetHeight = it.size.height.coerceAtLeast(1) }
                .graphicsLayer {
                    translationY = -(1f - progress.value) * size.height + pushed
                    shape = SheetShape
                    clip = true
                    shadowElevation = SHEET_ELEVATION.toPx()
                }
                .background(MaterialTheme.colorScheme.surface)
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta ->
                        pushed = (pushed + delta).coerceIn(-sheetHeight.toFloat(), 0f)
                    },
                    onDragStopped = { velocity ->
                        // Folds the push into the sheet's own position, then lets it settle from there.
                        val at = progress.value + pushed / sheetHeight
                        pushed = 0f
                        progress.snapTo(at)
                        if (velocity < -FLING_VELOCITY || at < CLOSE_BELOW) {
                            onClose()
                        } else {
                            scope.launch { progress.animateTo(1f, tween(OPEN_MS, easing = EmphasizedDecelerate)) }
                        }
                    },
                )
                .padding(top = 8.dp, bottom = 12.dp),
        ) {
            content()
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(width = 32.dp, height = 4.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(2.dp)),
            )
        }
    }
}

private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
private val SheetShape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)
private val SHEET_MAX_WIDTH = 640.dp
private val SHEET_ELEVATION = 8.dp
private const val OPEN_MS = 320
private const val CLOSE_MS = 220
private const val SCRIM_ALPHA = 0.32f
private const val FLING_VELOCITY = 800f
private const val CLOSE_BELOW = 0.6f
