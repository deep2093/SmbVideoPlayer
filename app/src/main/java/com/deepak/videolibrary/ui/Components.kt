package com.deepak.videolibrary.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The app's text field: a compact, fully rounded pill that matches the floating navigation bar
 * (theme surface fill with a thin outline). The label is shown as a hint while the field is empty.
 */
@Composable
fun PillTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    focusRequester: FocusRequester? = null,
) {
    val colors = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .background(colors.surface, CircleShape)
            .themedOutline(CircleShape, emphasised = focused)
            .semantics { contentDescription = label },
        enabled = enabled,
        singleLine = singleLine,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = if (enabled) colors.onSurface else colors.onSurfaceVariant),
        cursorBrush = SolidColor(colors.onSurface),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leadingIcon != null) {
                    leadingIcon()
                    Spacer(Modifier.width(8.dp))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            label,
                            color = colors.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
                if (trailingIcon != null) {
                    Spacer(Modifier.width(8.dp))
                    trailingIcon()
                }
            }
        },
    )
}

/**
 * Top bar whose search icon opens the search field in place, like VLC: the title and actions give way
 * to a back arrow and the field, with a short slide/fade. Closing clears the query.
 */
@Composable
fun SearchableTopBar(
    title: String,
    searching: Boolean,
    onSearchingChange: (Boolean) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    AnimatedContent(
        targetState = searching,
        modifier = modifier.fillMaxWidth(),
        transitionSpec = {
            val direction = if (targetState) 1 else -1
            (fadeIn(tween(200)) + slideInHorizontally(tween(240)) { direction * it / 8 }) togetherWith
                (fadeOut(tween(120)) + slideOutHorizontally(tween(240)) { -direction * it / 8 })
        },
        label = "search_top_bar",
    ) { open ->
        if (open) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    onQueryChange("")
                    onSearchingChange(false)
                }) { Icon(Icons.Filled.ArrowBack, contentDescription = "Close search") }
                PillTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    label = placeholder,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    focusRequester = focus,
                    trailingIcon = if (query.isNotEmpty()) {
                        {
                            IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear search", modifier = Modifier.size(18.dp))
                            }
                        }
                    } else {
                        null
                    },
                )
                Spacer(Modifier.width(8.dp))
            }
        } else {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                navigation?.invoke()
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onSearchingChange(true) }) { Icon(Icons.Filled.Search, contentDescription = "Search") }
                actions()
            }
        }
    }
}

/** One step of a folder path: what to show and the path to open when it is tapped. */
data class Crumb(val label: String, val path: String)

/**
 * Splits [path] (forward slashes) into tappable steps. When [root] is set and contains [path], the root
 * itself is represented by the home button and only the folders below it become crumbs.
 */
fun breadcrumbs(path: String, root: String = ""): List<Crumb> {
    val cleanPath = path.trim('/')
    val cleanRoot = root.trim('/')
    val underRoot = cleanRoot.isNotEmpty() && (cleanPath == cleanRoot || cleanPath.startsWith("$cleanRoot/"))
    val relative = if (underRoot) cleanPath.removePrefix(cleanRoot).trim('/') else cleanPath
    var accumulated = if (underRoot) cleanRoot else ""
    return relative.split('/').filter { it.isNotEmpty() }.map { segment ->
        accumulated = if (accumulated.isEmpty()) segment else "$accumulated/$segment"
        Crumb(segment, accumulated)
    }
}

/**
 * File-manager style path bar: a rounded pill with a home button and one tappable segment per folder.
 * The current (last) folder is highlighted in the theme accent. Long paths scroll sideways and keep the
 * current folder in view.
 */
@Composable
fun BreadcrumbBar(
    crumbs: List<Crumb>,
    onHome: () -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    rootLabel: String = "Share root",
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    LaunchedEffect(crumbs) { scroll.animateScrollTo(scroll.maxValue) }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(colors.surface, CircleShape)
            .themedOutline(CircleShape)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onHome, enabled = enabled, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Filled.Home,
                contentDescription = "Go to $rootLabel",
                tint = if (crumbs.isEmpty()) colors.tertiary else colors.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        Row(
            Modifier.weight(1f).horizontalScroll(scroll),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (crumbs.isEmpty()) {
                Text(
                    rootLabel,
                    color = colors.tertiary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
            crumbs.forEachIndexed { index, crumb ->
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                val last = index == crumbs.lastIndex
                Text(
                    crumb.label,
                    color = if (last) colors.tertiary else colors.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    modifier = Modifier
                        .then(if (!last && enabled) Modifier.clickable { onSelect(crumb.path) } else Modifier)
                        .padding(horizontal = 6.dp, vertical = 10.dp),
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * Animated hand-over between two folder listings. When [path] changes, the previous listing slides out
 * and fades while the new one slides in and fades in (from the right going deeper, from the left going up),
 * like VLC. The previous listing is a picture of the last drawn frame, so it needs no old data.
 * The first path shown is not animated.
 */
@Composable
fun FolderTransition(path: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val layers = arrayOf(androidx.compose.ui.graphics.rememberGraphicsLayer(), androidx.compose.ui.graphics.rememberGraphicsLayer())
    val first = remember { booleanArrayOf(true) }
    val lastDepth = remember { intArrayOf(pathDepth(path)) }
    var active by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var ghost by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(1) }
    var progress by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    var direction by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    // Runs in the same composition that first shows the new path, before anything is drawn with it.
    remember(path) {
        if (first[0]) {
            first[0] = false
        } else {
            val depth = pathDepth(path)
            direction = if (depth >= lastDepth[0]) 1f else -1f
            lastDepth[0] = depth
            ghost = active
            active = 1 - active
            progress = 0f
        }
        path
    }
    LaunchedEffect(path) {
        if (progress < 1f) {
            androidx.compose.animation.core.animate(
                initialValue = progress,
                targetValue = 1f,
                animationSpec = androidx.compose.animation.core.tween(
                    320,
                    easing = androidx.compose.animation.core.FastOutSlowInEasing,
                ),
            ) { value, _ -> progress = value }
        }
    }
    val showGhost by remember { androidx.compose.runtime.derivedStateOf { progress < 1f } }
    val distance = with(androidx.compose.ui.platform.LocalDensity.current) { 56.dp.toPx() }
    Box(modifier.clipToBounds()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = progress
                    translationX = (1f - progress) * direction * distance
                }
                .drawWithContent {
                    val layer = layers[active]
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                },
        ) { content() }
        if (showGhost) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = 1f - progress
                        translationX = -progress * direction * distance
                    }
                    .drawWithContent { drawLayer(layers[ghost]) },
            )
        }
    }
}

private fun pathDepth(path: String): Int = if (path.isEmpty()) 0 else path.count { it == '/' } + 1

/**
 * Stops "bring into view" requests (sent by a focused text field, for example when the keyboard opens)
 * from travelling further up the layout. Scroll containers inside still handle them first.
 */
fun Modifier.stopBringIntoViewPropagation(): Modifier = this then StopBringIntoViewElement

private object StopBringIntoViewElement : androidx.compose.ui.node.ModifierNodeElement<StopBringIntoViewNode>() {
    override fun create() = StopBringIntoViewNode()
    override fun update(node: StopBringIntoViewNode) = Unit
    override fun hashCode(): Int = "StopBringIntoView".hashCode()
    override fun equals(other: Any?): Boolean = other === this
}

private class StopBringIntoViewNode :
    Modifier.Node(),
    androidx.compose.ui.relocation.BringIntoViewModifierNode {
    override suspend fun bringIntoView(
        childCoordinates: androidx.compose.ui.layout.LayoutCoordinates,
        boundsProvider: () -> androidx.compose.ui.geometry.Rect?,
    ) = Unit
}
