package com.skywing.desktop

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.skywing.desktop.ui.adaptive.Adaptive
import com.skywing.desktop.ui.adaptive.AutoColumns
import com.google.gson.JsonObject
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** The shape of a card's picture: the aspect ratio (width over height) it is drawn at. */
internal object CardAspect {
    const val SCENE = 16f / 9f
    const val POSTER = 2f / 3f
    const val PORTRAIT = 3f / 4f
}

/** How one page shows its cards: the shape of actor and folder pictures, of video pictures, the size, and grid or list. */
internal data class PageView(
    val actorPortrait: Boolean = false,
    val videoPoster: Boolean = false,
    val size: Int = CardSizes.NORMAL,
    val list: Boolean = false,
) {
    val folderAspect: Float get() = if (actorPortrait) CardAspect.PORTRAIT else CardAspect.SCENE
    val videoAspect: Float get() = if (videoPoster) CardAspect.POSTER else CardAspect.SCENE
}

internal enum class ActorLayout { HEADER, STUDIO }

/** Every remembered view: the last choice (for a page with none yet), each page's own, and two app-wide choices. */
internal data class ViewsData(
    val last: PageView = PageView(),
    val pages: Map<String, PageView> = emptyMap(),
    val actorLayout: ActorLayout = ActorLayout.HEADER,
    val keepMenuOpen: Boolean = false,
)

/**
 * The views kept on this PC in views.json as
 * `{"version":1,"last":{...},"pages":{"<page>":{...}},"actorLayout":"HEADER","keepMenuOpen":false}`.
 * With no file yet, [load] starts from the old organizer settings. A file that can't be read, has another version or
 * has the wrong shape gives the defaults and is never written over for the rest of the session. A null [path] keeps
 * nothing on disk.
 */
internal class LibraryViewStore(path: Path? = libraryFile("views.json")) {
    private val file = path?.let(::JsonFile)
    private val lock: Any = file?.lock ?: Any()
    private var blocked = false

    fun load(seed: FolderPrefs): ViewsData = synchronized(lock) {
        when (val read = file?.read() ?: JsonFile.Read.Missing) {
            JsonFile.Read.Missing -> seeded(seed)
            JsonFile.Read.Locked -> block()
            is JsonFile.Read.Found -> parse(read.root) ?: block()
        }
    }

    fun save(data: ViewsData): Unit = synchronized(lock) {
        if (blocked) return
        file?.write(toJson(data))
    }

    private fun block(): ViewsData {
        blocked = true
        return ViewsData()
    }

    private fun seeded(seed: FolderPrefs): ViewsData {
        val last = PageView(size = CardSizes.of(seed.folderCardSize), list = seed.listMode)
        val pages = LinkedHashMap<String, PageView>()
        // The Videos page kept its own size (0 meant Medium), so it does not move to the folders' size.
        pages[VIDEOS_PAGE] = last.copy(size = CardSizes.of(seed.videoCardSize))
        seed.tallPages.forEach { page -> pages[page] = (pages[page] ?: last).copy(actorPortrait = true, videoPoster = true) }
        return ViewsData(last = last, pages = pages)
    }

    private fun parse(root: JsonObject): ViewsData? = runCatching {
        if (root.get("version")?.asInt != 1) return null
        val pages = root.get("pages")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        ViewsData(
            last = view(root.get("last")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null),
            pages = pages.entrySet().associate { (page, record) -> page to view(record.takeIf { it.isJsonObject }?.asJsonObject ?: return null) },
            actorLayout = root.get("actorLayout")?.asString?.let { name -> ActorLayout.entries.firstOrNull { it.name == name } } ?: ActorLayout.HEADER,
            keepMenuOpen = root.get("keepMenuOpen")?.asBoolean ?: false,
        )
    }.getOrNull()

    private fun view(o: JsonObject) = PageView(
        actorPortrait = o.get("actorPortrait")?.asBoolean ?: false,
        videoPoster = o.get("videoPoster")?.asBoolean ?: false,
        size = CardSizes.of(o.get("size")?.asInt ?: CardSizes.NORMAL),
        list = o.get("list")?.asBoolean ?: false,
    )

    private fun json(v: PageView) = JsonObject().apply {
        addProperty("actorPortrait", v.actorPortrait)
        addProperty("videoPoster", v.videoPoster)
        addProperty("size", v.size)
        addProperty("list", v.list)
    }

    private fun toJson(data: ViewsData) = JsonObject().apply {
        addProperty("version", 1)
        add("last", json(data.last))
        add("pages", JsonObject().apply { data.pages.forEach { (page, v) -> add(page, json(v)) } })
        addProperty("actorLayout", data.actorLayout.name)
        addProperty("keepMenuOpen", data.keepMenuOpen)
    }
}

/**
 * The views of every page, held in Compose state so a change shows at once. Each change is saved off the screen thread,
 * in order, so the last one wins; a save that fails leaves the choice in effect for this session.
 */
internal class LibraryViews(private val store: LibraryViewStore, seed: FolderPrefs) {
    var data by mutableStateOf(store.load(seed))
        private set

    /** The page's own view, or the last choice made anywhere when it has none yet. */
    fun of(page: String): PageView = data.pages[page] ?: data.last

    fun change(page: String, transform: (PageView) -> PageView) {
        val next = transform(of(page))
        commit(data.copy(last = next, pages = data.pages + (page to next)))
    }

    /** Called after every size change, so the app can let a fixed column count from Settings give way to the size. */
    var onSizeChange: () -> Unit = {}

    /** Smaller (-1) or larger (+1) cards on [page]. */
    fun step(page: String, direction: Int) = setSize(page, CardSizes.step(of(page).size, direction))

    fun medium(page: String) = setSize(page, CardSizes.NORMAL)

    /** Sets the size; [onSizeChange] is told only when the size really changed, and a failing hook is ignored. */
    fun setSize(page: String, size: Int) {
        val before = of(page).size
        change(page) { it.copy(size = size) }
        if (of(page).size != before) {
            try {
                onSizeChange()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * The size pages start at until their own View menu changes it (Settings › App layout › Picture size). Pages with
     * their own view keep theirs. [onSizeChange] is told only when the size really changed.
     */
    fun setSizeForNewPages(size: Int) {
        val before = data.last.size
        commit(data.copy(last = data.last.copy(size = size)))
        if (data.last.size != before) {
            try {
                onSizeChange()
            } catch (_: Exception) {
            }
        }
    }

    fun setListEverywhere(list: Boolean) =
        commit(data.copy(last = data.last.copy(list = list), pages = data.pages.mapValues { it.value.copy(list = list) }))

    fun setLayout(layout: ActorLayout) = commit(data.copy(actorLayout = layout))

    fun setKeepMenuOpen(keep: Boolean) = commit(data.copy(keepMenuOpen = keep))

    fun reset() = commit(ViewsData())

    /** Waits until every change so far is saved (for tests). */
    fun flush() = flushAll()

    private fun commit(next: ViewsData) {
        data = next
        try {
            writer.execute {
                try {
                    store.save(next)
                } catch (_: Exception) {
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    companion object {
        /** Waits until every save so far, of any instance, is written: on close, and in tests. */
        fun flushAll() {
            runCatching { writer.submit {}.get(10, TimeUnit.SECONDS) }
        }

        private val writer = Executors.newSingleThreadExecutor { task -> Thread(task, "skywing-views-writer").apply { isDaemon = true } }
    }
}

/** These prefs with the column count back on Auto, so a chosen card size shows. */
internal fun FolderPrefs.autoColumns(): FolderPrefs = if (columns == 0) this else copy(columns = 0)

/** The views of the app. With none provided (a bare test) they are kept in memory only. */
internal val LocalLibraryViews = staticCompositionLocalOf { LibraryViews(LibraryViewStore(null), FolderPrefs()) }

/** The grid columns for [view]: one row per item in a list, a fixed number chosen in Settings, otherwise by size. */
internal fun cells(view: PageView, columns: Int, adaptive: Adaptive): GridCells = when {
    view.list -> GridCells.Fixed(1)
    columns in 2..5 -> GridCells.Fixed(columns)
    CardSizes.of(view.size) == CardSizes.NORMAL -> AutoColumns(adaptive)
    else -> GridCells.Adaptive(CardSizes.of(view.size).dp)
}

/** What Ctrl with a key does to the card size: +1 larger, -1 smaller, 0 back to Medium, null for any other key. */
internal fun viewKeyAction(key: Key, ctrl: Boolean, inTextField: Boolean): Int? = when {
    !ctrl || inTextField -> null
    key == Key.Equals || key == Key.Plus || key == Key.NumPadAdd -> 1
    key == Key.Minus || key == Key.NumPadSubtract -> -1
    key == Key.Zero || key == Key.NumPad0 -> 0
    else -> null
}

/** Ctrl with + - or 0 resizes the cards of [page]. Keys only reach a grid when focus is in it, never a text field's. */
@Composable
internal fun Modifier.viewKeys(page: String): Modifier {
    val views = LocalLibraryViews.current
    return onPreviewKeyEvent { event ->
        val action = if (event.type == KeyEventType.KeyDown) viewKeyAction(event.key, event.isCtrlPressed, inTextField = false) else null
        when (action) {
            null -> false
            0 -> { views.medium(page); true }
            else -> { views.step(page, action); true }
        }
    }
}

/** The one View menu of a page: picture shapes for [actors] and [videos], the size, and grid or list. */
@Composable
internal fun ViewButton(page: String, actors: Boolean, videos: Boolean) {
    val views = LocalLibraryViews.current
    val view = views.of(page)
    fun item(label: String, on: Boolean, change: (PageView) -> PageView) =
        MenuAction(label, if (on) SkyIcon.Check else null) { views.change(page, change) }
    val size = CardSizes.named(view.size)
    val actions = buildList {
        if (actors) {
            add(item("Portrait", view.actorPortrait) { it.copy(actorPortrait = true) })
            add(item("Landscape", !view.actorPortrait) { it.copy(actorPortrait = false) })
        }
        if (videos) {
            add(item("Scene (16:9)", !view.videoPoster) { it.copy(videoPoster = false) })
            add(item("Poster (2:3)", view.videoPoster) { it.copy(videoPoster = true) })
        }
        add(MenuAction("Small", if (size == "Small") SkyIcon.Check else null) { views.setSize(page, CardSizes.SMALL) })
        add(MenuAction("Medium", if (size == "Medium") SkyIcon.Check else null) { views.setSize(page, CardSizes.NORMAL) })
        add(MenuAction("Large", if (size == "Large") SkyIcon.Check else null) { views.setSize(page, CardSizes.LARGE) })
        add(item("Grid", !view.list) { it.copy(list = false) })
        add(item("List", view.list) { it.copy(list = true) })
    }
    SkyMenuButton(SkyIcon.Grid, "View", actions)
}
