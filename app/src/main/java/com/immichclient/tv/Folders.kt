package com.immichclient.tv

import android.util.Log
import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Folders: browse photos by their directory layout on disk (upload dir or external libraries), via Immich's
 * folder view (`/view/folder/unique-paths` + `/view/folder?path=`, requires `folder.read`). Each folder pushes
 * its own screen onto the back stack so Back moves one folder up.
 */
class FolderNode(var name: String, val path: String, var parent: FolderNode?) {
    val children = ArrayList<FolderNode>()
    /** Immich reports direct photos/videos inside this directory. */
    var hasPhotos = false

    /** Relative breadcrumb path from the tree's starting root, used for the screen subtitle. */
    fun relative(start: FolderNode): String {
        val parts = ArrayList<String>()
        var k: FolderNode? = this
        while (k != null && k !== start) { parts += k.name; k = k.parent }
        return parts.asReversed().joinToString(" / ")
    }

    companion object {
        /** Build folder tree from unique paths; returns the deepest common branch where a choice exists. */
        fun build(paths: List<String>): FolderNode {
            val root = FolderNode("", "", null)
            val absolute = paths.any { it.startsWith("/") }
            for (p in paths) {
                var k = root
                var path = if (absolute) "" else null
                for (part in p.split('/').filter { it.isNotEmpty() }) {
                    path = if (path == null) part else "$path/$part"
                    k = k.children.firstOrNull { it.name == part } ?: FolderNode(part, path, k).also { k.children += it }
                }
                if (k !== root) k.hasPhotos = true
            }
            val collator = java.text.Collator.getInstance(Language.locale).apply { strength = java.text.Collator.SECONDARY }
            fun sort(k: FolderNode) { k.children.sortWith { a, b -> collator.compare(a.name, b.name) }; k.children.forEach(::sort) }
            sort(root)
            // Skip leading single-child wrapper directories (e.g. "/data/upload/<id>/..." or "/mnt/media/photos/...")
            var start = root
            while (start.children.size == 1 && !start.hasPhotos) start = start.children[0]
            // Collapse single-child chains ("2024 / Camera" instead of drilling through a 1-folder level).
            fun collapse(k: FolderNode) {
                for (i in k.children.indices) {
                    var c = k.children[i]
                    while (c.children.size == 1 && !c.hasPhotos) {
                        val g = c.children[0]
                        g.name = c.name + " / " + g.name; g.parent = k
                        c = g
                    }
                    k.children[i] = c
                    collapse(c)
                }
            }
            collapse(start)
            // Shorten Immich's default container upload path to "upload / ..."
            for (c in start.children) for (pre in listOf("usr / src / app / ", "data / ")) if (c.name.startsWith(pre + "upload")) c.name = c.name.removePrefix(pre)
            return start
        }
    }
}

/** Shared folder tree loaded once per session across all folder navigation screens. */
class FoldersTree(scope: CoroutineScope) {
    var start by mutableStateOf<FolderNode?>(null)
    var error by mutableStateOf<String?>(null)
    /** Reverse Z-A folder order (handy when date-prefixed folders have newest at the top); applies to all folder screens. */
    var reversed by mutableStateOf(false)

    init {
        scope.launch {
            runCatching { FolderNode.build(Immich.folders()) }
                .onSuccess { start = it }
                .onFailure { error = it.message ?: it.toString(); Log.w("Photos", "folders", it) }
        }
    }
}

class FolderState(val tree: FoldersTree, val node: FolderNode?, scope: CoroutineScope) {
    var photos by mutableStateOf<List<Asset>?>(null)
    var error by mutableStateOf<String?>(null)
    val grid = GridState()
    var folderFocusTarget by mutableIntStateOf(-1)
    var lastFolderPath by mutableStateOf<String?>(null)
    private var loadedNode: FolderNode? = null

    init { scope.launch { load() } }

    /** Root folder screen: tree arrives asynchronously, so resolve current node lazily. */
    val current: FolderNode? get() = node ?: tree.start

    suspend fun load() {
        val k = current ?: return
        if (loadedNode === k) return
        loadedNode = k
        if (!k.hasPhotos) { photos = emptyList(); return }
        runCatching { Immich.folderContents(k.path) }.onSuccess { photos = it }.onFailure { error = it.message; photos = emptyList(); Log.w("Photos", "folder ${k.path}", it) }
    }
}

private const val COLUMNS = 6

@Composable
fun FoldersScreen(screen: Screen.Folders, nav: Nav) {
    val state = screen.state
    val tree = state.tree
    val k = state.current
    LaunchedEffect(k) { state.load() }

    if (k == null) {
        val err = tree.error
        if (err == null) EmptyMessage(stringResource(R.string.loading), null, Icons.Filled.Folder)
        else if (err.contains("folder.read")) androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
            EmptyMessage(stringResource(R.string.folders_no_permission), stringResource(R.string.folders_no_permission_desc), Icons.Filled.Folder)
            val fr = remember { FocusRequester() }
            ActionButton(stringResource(R.string.perms_title), stringResource(R.string.perms_inspect), Modifier.align(Alignment.Center).offset(y = 140.dp).width(320.dp).focusRequester(fr)) { Permissions.open = true }
            LaunchedEffect(Unit) { delay(50); runCatching { fr.requestFocus() } }
        }
        else EmptyMessage(stringResource(R.string.folders_load_failed), err, Icons.Filled.Folder)
        return
    }
    val folders = remember(k, tree.reversed) { if (tree.reversed) k.children.asReversed().toList() else k.children.toList() }
    val photos = state.photos.orEmpty()
    val title = if (k === tree.start) stringResource(R.string.section_folders) else k.name
    fun view(i: Int, slideshow: Boolean) {
        if (photos.isEmpty()) return
        nav.view(ListSource(photos), i.coerceIn(0, photos.lastIndex), title, slideshow, state.grid)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = widthFor(maxWidth.value)
        val rowHeight = Settings.rowSize.height
        val rows = remember(photos, width, rowHeight) { rowsFor(photos, width, byDay = true, height = rowHeight) }
        val firstPhoto = remember(rows) { firstPhotoPerRow(rows) }
        val folderRows = remember(folders) { folders.chunked(COLUMNS) }
        val headerCount = 1 + folderRows.size

        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxSize().onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key.nativeKeyCode) {
                    // Play/Pause = slideshow of this folder starting at the focused photo (or first)
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        val a = state.grid.lastFocused ?: photos.firstOrNull()
                        val i = a?.let { photos.indexOf(it) }?.takeIf { it >= 0 } ?: 0
                        view(i, true)
                        photos.isNotEmpty()
                    }
                    else -> false
                }
            },
        ) {
            PhotoGrid(
                rows = rows,
                state = state.grid,
                onClick = { a -> val i = photos.indexOf(a); if (i >= 0) view(i, false) },
                headerCount = headerCount,
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "header") {
                    val parts = listOfNotNull(
                        folders.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.folders_count, it, it) },
                        photos.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.items, it, it) },
                    )
                    val path = tree.start?.let { k.relative(it) }.orEmpty()
                    ScreenHeader(title, listOfNotNull(path.takeIf { it.isNotEmpty() && it != title }, parts.joinToString("  ·  ").ifEmpty { null }).joinToString("   —   ").ifEmpty { null }) {
                        if (folders.size > 1) ChoiceChip(
                            selected = false, onClick = { tree.reversed = !tree.reversed; state.folderFocusTarget = 0 },
                            text = if (tree.reversed) "Z–A" else "A–Z",
                        )
                        if (photos.isNotEmpty()) ChoiceChip(
                            selected = false, onClick = { view(0, true) },
                            leadingIcon = { Icon(Icons.Filled.PlayArrow, null, Modifier.width(18.dp)) },
                            text = stringResource(R.string.slideshow),
                        )
                    }
                }
                items(folderRows.size, key = { "fr:" + folderRows[it].first().path }) { ri ->
                    val rowFolders = folderRows[ri]
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        for ((ci, f) in rowFolders.withIndex()) {
                            val idx = ri * COLUMNS + ci
                            FolderTile(
                                f,
                                modifier = Modifier.weight(1f),
                                focusTarget = state.folderFocusTarget == idx,
                                onFocusTargetDone = { state.folderFocusTarget = -1 },
                                onFocus = { state.lastFolderPath = f.path },
                            ) {
                                state.lastFolderPath = f.path
                                nav.open(Screen.Folders(state.tree, f, nav.scope))
                            }
                        }
                        repeat(COLUMNS - rowFolders.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                if (state.photos == null && k.hasPhotos) item(key = "loading") {
                    Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodyLarge, color = AppColors.muted, modifier = Modifier.padding(top = 24.dp))
                }
                if (state.photos != null && folders.isEmpty() && photos.isEmpty()) item(key = "empty") {
                    EmptyMessage(stringResource(if (state.error != null) R.string.folders_load_failed else R.string.folder_empty), state.error, Icons.Filled.Folder)
                }
            }
            if (rows.isNotEmpty() || folders.isNotEmpty()) ScrollBar(
                fraction = { fractionOf(state.grid.list, 1) },
                movement = { state.grid.list.firstVisibleItemIndex * 7919 + state.grid.list.firstVisibleItemScrollOffset / 200 },
                scrollable = { state.grid.list.canScrollForward || state.grid.list.canScrollBackward },
                label = {
                    val i = state.grid.list.firstVisibleItemIndex
                    when {
                        i <= folderRows.size -> folderRows.getOrNull((i - 1).coerceAtLeast(0))?.firstOrNull()?.name
                        else -> (rows.getOrNull((i - headerCount).coerceIn(0, (rows.size - 1).coerceAtLeast(0))) as? PhotoGridRow)
                            ?.row?.chunks?.firstOrNull()?.header
                    }
                },
                sub = {
                    if (photos.isNotEmpty() && state.grid.list.firstVisibleItemIndex >= headerCount) {
                        val rIdx = (state.grid.list.firstVisibleItemIndex - headerCount).coerceIn(0, (rows.size - 1).coerceAtLeast(0))
                        val nr = (firstPhoto.getOrElse(rIdx) { 0 } + 1).coerceIn(1, photos.size)
                        "${Language.number(nr)} / ${Language.number(photos.size)}"
                    } else if (folders.isNotEmpty()) {
                        val rIdx = (state.grid.list.firstVisibleItemIndex - 1).coerceIn(0, (folderRows.size - 1).coerceAtLeast(0))
                        val nr = (rIdx * COLUMNS + 1).coerceIn(1, folders.size)
                        "${Language.number(nr)} / ${Language.number(folders.size)}"
                    } else null
                },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
    // Restore focus to the last selected subfolder or photo
    LaunchedEffect(k, folders.size, photos.size) {
        delay(30)
        if (state.grid.returnTo != null || state.grid.focusTarget != null) return@LaunchedEffect
        val folderIdx = state.lastFolderPath?.let { path -> folders.indexOfFirst { it.path == path } }?.takeIf { it >= 0 }
        when {
            folderIdx != null -> { state.folderFocusTarget = folderIdx; state.lastFolderPath = null }
            state.grid.lastFocused != null -> state.grid.focusTarget = state.grid.lastFocused!!.id
            folders.isNotEmpty() -> state.folderFocusTarget = 0
            photos.isNotEmpty() -> state.grid.focusTarget = photos.first().id
        }
    }
}

/** Folder tile: large folder icon with name underneath (focus: scaled slightly, white border, accent fill). */
@Composable
private fun FolderTile(f: FolderNode, modifier: Modifier = Modifier, focusTarget: Boolean, onFocusTargetDone: () -> Unit, onFocus: () -> Unit, onClick: () -> Unit) {
    val fr = remember { FocusRequester() }
    if (focusTarget) LaunchedEffect(f.path) {
        repeat(5) { if (runCatching { fr.requestFocus() }.isSuccess) { onFocusTargetDone(); return@LaunchedEffect }; delay(16) }
        onFocusTargetDone()
    }
    var focus by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focus) 1.06f else 1f, tween(140), label = "scale")
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier.aspectRatio(1f)
            .graphicsLayer {
                scaleX = scale; scaleY = scale; this.shape = shape; clip = true
                shadowElevation = if (focus) 16.dp.toPx() else 0f
                spotShadowColor = AppColors.primary; ambientShadowColor = AppColors.primary
            }
            .background(if (focus) AppColors.primary else AppColors.surface)
            .then(if (focus) Modifier.border(3.dp, Color.White, shape) else Modifier)
            .focusRequester(fr)
            .onFocusChanged { focus = it.isFocused; if (it.isFocused) onFocus() }
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Folder, null, Modifier.size(40.dp), tint = if (focus) AppColors.onPrimary else AppColors.primary)
        Text(
            f.name, style = MaterialTheme.typography.titleSmall, color = if (focus) AppColors.onPrimary else AppColors.text,
            maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
}
