package com.skywing.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.TreeMap
import java.util.UUID

/*
 * The folder system of the Android app, for Windows: Custom folders (folders you name and fill, drive folders you
 * add, filename collections), Virtual folders (folders you make whose subfolders come from the names in the file
 * names) and the choices that go with them. Nothing here touches files on disk; paths are the library's paths.
 */

/**
 * The three on/off rules that turn a file name into folder names. All on is the Virtual folder rule:
 * [stopAtHyphen] reads only the text before the first "-", [splitAtCommas] makes one folder per comma-separated name
 * (the video is listed in each), and [firstTwoWords] uses the first two words when no hyphen or comma was used.
 */
internal data class GroupingSwitches(
    val stopAtHyphen: Boolean = true,
    val splitAtCommas: Boolean = true,
    val firstTwoWords: Boolean = true,
)

/** A Custom folder you named and filled by hand: chosen folders (each with everything inside it) and single videos. */
internal data class OwnFolder(
    val id: String,
    val name: String,
    val folders: List<String> = emptyList(),
    val files: List<String> = emptyList(),
    /** The Custom folder this one sits inside, or null for one on the Custom folders page. */
    val parentId: String? = null,
    /** Single videos are grouped into folders by name (on) or listed one by one (off). */
    val grouped: Boolean = true,
) {
    val isEmpty: Boolean get() = folders.isEmpty() && files.isEmpty()
}

/**
 * A Custom folder that fills itself: a drive folder you added ([sourcePath], with or without its sub folders) or a
 * filename collection ([term] matched anywhere in the file name, ignoring case).
 */
internal data class MatchFolder(
    val id: String,
    val name: String,
    val term: String,
    val sourcePath: String? = null,
    val includeSubfolders: Boolean = true,
    val hidden: Boolean = false,
)

/**
 * A Virtual folder you made and named ("Hollywood Movies"). It reads the folders and videos you chose; its subfolders
 * (one per name found in the file names) are generated. [renames] and [hidden] are your changes to generated
 * subfolders, keyed by the folder's identity; they survive every rescan.
 */
internal data class VirtualSet(
    val id: String,
    val name: String,
    val folders: List<String> = emptyList(),
    val files: List<String> = emptyList(),
    val renames: Map<String, String> = emptyMap(),
    val hidden: Set<String> = emptySet(),
    val parentId: String? = null,
) {
    val isEmpty: Boolean get() = folders.isEmpty() && files.isEmpty()
}

/** How the folder pages look and which tabs they show, the same choices as Settings on Android. */
internal data class FolderPrefs(
    /** NAME, COUNT, DATE or SIZE. */
    val sort: String = "NAME",
    val descending: Boolean = false,
    val listMode: Boolean = false,
    /** 0 fits as many cards as the window allows; 2 to 5 is a fixed number of columns. */
    val columns: Int = 0,
    /** Visible Folders tabs in order: 0 Custom, 1 Virtual, 2 Original. */
    val tabs: List<Int> = FolderTabs.ALL,
    val tabNames: Map<Int, String> = emptyMap(),
    val virtualRules: GroupingSwitches = GroupingSwitches(),
    val customRules: GroupingSwitches = GroupingSwitches(),
)

internal data class OrganizerData(
    val own: List<OwnFolder> = emptyList(),
    val match: List<MatchFolder> = emptyList(),
    val sets: List<VirtualSet> = emptyList(),
    /** Videos filed into a named folder by hand (library path in lower case -> folder name). They win over names. */
    val assignments: Map<String, String> = emptyMap(),
    val prefs: FolderPrefs = FolderPrefs(),
)

internal object FolderTabs {
    val ALL = listOf(0, 1, 2)
    val DEFAULTS = listOf("Custom folders", "Virtual folders", "Original folders")

    fun clean(tabs: List<Int>): List<Int> = tabs.filter { it in ALL }.distinct().ifEmpty { ALL }

    /** All three in display order: shown ones first in their order, then hidden ones. */
    fun arranged(shown: List<Int>): List<Int> = shown + ALL.filterNot { it in shown }
}

internal fun FolderPrefs.tabName(tab: Int): String = tabNames[tab]?.takeIf { it.isNotBlank() } ?: FolderTabs.DEFAULTS[tab]

/* ---------- Grouping and membership ---------- */

/** One generated subfolder: every video whose file name gives this name. */
internal data class VirtualGroup(val identity: String, val title: String, val videos: List<WindowsMediaEntry>) {
    val totalSize: Long get() = videos.sumOf { it.size }
    val lastModified: Long get() = videos.maxOfOrNull { it.modified } ?: 0L
}

internal object FolderLogic {
    private fun key(path: String) = path.trim().trimEnd('\\', '/').lowercase(Locale.ROOT)

    /** True when [path] is [folder] itself or anywhere inside it. */
    fun isWithin(path: String, folder: String): Boolean {
        val p = key(path)
        val f = key(folder)
        return p == f || p.startsWith("$f\\")
    }

    private fun parentKey(path: String) = key(path).substringBeforeLast('\\', "")

    /** Everything indexed inside [folders] (with their sub folders) plus the single [files], in library order. */
    fun members(folders: List<String>, files: List<String>, all: List<WindowsMediaEntry>): List<WindowsMediaEntry> {
        if (folders.isEmpty() && files.isEmpty()) return emptyList()
        val prefixes = folders.map { key(it) + "\\" }
        val singles = files.map(::key).toHashSet()
        return all.filter { entry ->
            val lower = entry.path.lowercase(Locale.ROOT)
            lower in singles || prefixes.any { lower.startsWith(it) }
        }
    }

    /** The videos a self-filling Custom folder shows. */
    fun matchVideos(folder: MatchFolder, all: List<WindowsMediaEntry>): List<WindowsMediaEntry> {
        val source = folder.sourcePath
        return when {
            source == null -> all.filter { it.file.name.contains(folder.term.trim(), ignoreCase = true) }
            folder.includeSubfolders -> members(listOf(source), emptyList(), all)
            else -> key(source).let { parent -> all.filter { parentKey(it.path) == parent } }
        }
    }

    /** The names a video is filed under: the folder it was given by hand, otherwise the names in its file name. */
    fun namesOf(entry: WindowsMediaEntry, rules: GroupingSwitches, assignments: Map<String, String>): List<String> =
        assignments[entry.path.lowercase(Locale.ROOT)]?.takeIf { it.isNotBlank() }?.let { listOf(WindowsVirtualNames.display(it)) }
            ?: WindowsVirtualNames.of(entry.file.name, rules)

    /**
     * Files [videos] under their names. A video filed by hand stays only in the folder it was given. The set's renames
     * and hidden folders are applied, so a rescan never undoes them; renaming a folder to the name of another joins them.
     */
    fun buildGroups(
        set: VirtualSet,
        videos: List<WindowsMediaEntry>,
        rules: GroupingSwitches,
        assignments: Map<String, String>,
    ): List<VirtualGroup> {
        val titles = LinkedHashMap<String, String>()
        val members = LinkedHashMap<String, LinkedHashMap<String, WindowsMediaEntry>>()
        for (video in videos) {
            for (name in namesOf(video, rules, assignments)) {
                val generated = WindowsVirtualNames.identity(name)
                if (generated in set.hidden) continue
                val title = set.renames[generated] ?: name
                val identity = WindowsVirtualNames.identity(title)
                if (identity in set.hidden) continue
                titles.putIfAbsent(identity, title)
                members.getOrPut(identity) { LinkedHashMap() }[video.path.lowercase(Locale.ROOT)] = video
            }
        }
        return members.map { (identity, byPath) ->
            VirtualGroup(identity, titles.getValue(identity), byPath.values.sortedBy { it.file.name.lowercase(Locale.ROOT) })
        }
    }

    /** [sort] is one of NAME, COUNT, DATE, SIZE. */
    fun sorted(groups: List<VirtualGroup>, sort: String, descending: Boolean): List<VirtualGroup> {
        val base = compareBy<VirtualGroup> { it.title.lowercase(Locale.ROOT) }
        val order: Comparator<VirtualGroup> = when (sort) {
            "COUNT" -> compareBy<VirtualGroup> { it.videos.size }.then(base)
            "DATE" -> compareBy<VirtualGroup> { it.lastModified }.then(base)
            "SIZE" -> compareBy<VirtualGroup> { it.totalSize }.then(base)
            else -> base
        }
        return groups.sortedWith(if (descending) order.reversed() else order)
    }

    /** Single videos of a Custom folder grouped by name: folder identity -> its videos, sorted by name. */
    fun groupByNames(
        videos: List<WindowsMediaEntry>,
        rules: GroupingSwitches,
        assignments: Map<String, String>,
    ): TreeMap<String, List<WindowsMediaEntry>> {
        val out = TreeMap<String, LinkedHashMap<String, WindowsMediaEntry>>()
        for (video in videos) {
            namesOf(video, rules, assignments).forEach { name ->
                out.getOrPut(WindowsVirtualNames.identity(name)) { LinkedHashMap() }[video.path.lowercase(Locale.ROOT)] = video
            }
        }
        return TreeMap(out.mapValues { it.value.values.toList() })
    }

    fun groupTitle(identity: String): String =
        identity.split(' ').joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(Locale.ROOT) } }

    /** The index letter for a name: its first letter, or "#" for names that start with a digit or symbol. */
    fun sectionLetter(name: String): String {
        val first = name.trim().firstOrNull { it.isLetterOrDigit() } ?: return "#"
        return if (first.isLetter()) first.uppercaseChar().toString() else "#"
    }

    /** An Original folder's card label: its name up to the first hyphen or comma. The full path stays in Details. */
    fun originalLabel(name: String): String {
        val end = name.indexOfFirst { it == '-' || it == ',' }
        return (if (end >= 0) name.substring(0, end) else name).trim().ifEmpty { name }
    }

    /** A folder name you typed, or null when it is empty, too long or has slashes. */
    fun cleanName(name: String): String? =
        name.trim().takeIf { it.isNotEmpty() && it.length <= 100 && '/' !in it && '\\' !in it }
}

/* ---------- Saving ---------- */

/** Keeps [OrganizerData] in one small file next to the library index. */
internal class FolderOrganizerStore(private val testPath: Path? = null) {
    private val path: Path by lazy {
        testPath ?: run {
            val base = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home")
            Paths.get(base, "SkyWing", "library", "folders.bin")
        }
    }

    /** True once the file exists, so older custom folders are brought over only the first time. */
    fun exists(): Boolean = Files.isRegularFile(path)

    @Synchronized
    fun load(): OrganizerData = try {
        if (!Files.isRegularFile(path)) OrganizerData()
        else DataInputStream(BufferedInputStream(Files.newInputStream(path))).use { read(it) }
    } catch (_: Exception) {
        OrganizerData()
    }

    @Synchronized
    fun save(data: OrganizerData) {
        Files.createDirectories(path.parent)
        val temporary = path.resolveSibling(path.fileName.toString() + ".tmp")
        DataOutputStream(BufferedOutputStream(Files.newOutputStream(temporary))).use { write(it, data) }
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun write(out: DataOutputStream, data: OrganizerData) {
        out.writeInt(MAGIC)
        out.writeInt(VERSION)
        out.writeInt(data.own.size)
        data.own.forEach { f ->
            out.writeUTF(f.id); out.writeUTF(f.name); out.strings(f.folders); out.strings(f.files)
            out.writeUTF(f.parentId.orEmpty()); out.writeBoolean(f.grouped)
        }
        out.writeInt(data.match.size)
        data.match.forEach { m ->
            out.writeUTF(m.id); out.writeUTF(m.name); out.writeUTF(m.term); out.writeUTF(m.sourcePath.orEmpty())
            out.writeBoolean(m.includeSubfolders); out.writeBoolean(m.hidden)
        }
        out.writeInt(data.sets.size)
        data.sets.forEach { s ->
            out.writeUTF(s.id); out.writeUTF(s.name); out.strings(s.folders); out.strings(s.files)
            out.writeInt(s.renames.size); s.renames.forEach { (k, v) -> out.writeUTF(k); out.writeUTF(v) }
            out.strings(s.hidden.toList()); out.writeUTF(s.parentId.orEmpty())
        }
        out.writeInt(data.assignments.size)
        data.assignments.forEach { (k, v) -> out.writeUTF(k); out.writeUTF(v) }
        val p = data.prefs
        out.writeUTF(p.sort); out.writeBoolean(p.descending); out.writeBoolean(p.listMode); out.writeInt(p.columns)
        out.writeInt(p.tabs.size); p.tabs.forEach(out::writeInt)
        out.writeInt(p.tabNames.size); p.tabNames.forEach { (k, v) -> out.writeInt(k); out.writeUTF(v) }
        out.rules(p.virtualRules); out.rules(p.customRules)
    }

    private fun read(input: DataInputStream): OrganizerData {
        if (input.readInt() != MAGIC || input.readInt() != VERSION) return OrganizerData()
        val own = List(input.count()) {
            OwnFolder(input.readUTF(), input.readUTF(), input.strings(), input.strings(), input.readUTF().ifEmpty { null }, input.readBoolean())
        }
        val match = List(input.count()) {
            MatchFolder(input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF().ifEmpty { null }, input.readBoolean(), input.readBoolean())
        }
        val sets = List(input.count()) {
            val id = input.readUTF()
            val name = input.readUTF()
            val folders = input.strings()
            val files = input.strings()
            val renames = LinkedHashMap<String, String>().apply { repeat(input.count()) { put(input.readUTF(), input.readUTF()) } }
            VirtualSet(id, name, folders, files, renames, input.strings().toSet(), input.readUTF().ifEmpty { null })
        }
        val assignments = HashMap<String, String>().apply { repeat(input.count()) { put(input.readUTF(), input.readUTF()) } }
        val prefs = FolderPrefs(
            sort = input.readUTF(),
            descending = input.readBoolean(),
            listMode = input.readBoolean(),
            columns = input.readInt(),
            tabs = FolderTabs.clean(List(input.count()) { input.readInt() }),
            tabNames = HashMap<Int, String>().apply { repeat(input.count()) { put(input.readInt(), input.readUTF()) } },
            virtualRules = input.rules(),
            customRules = input.rules(),
        )
        return OrganizerData(own, match, sets, assignments, prefs)
    }

    private fun DataInputStream.count(): Int = readInt().also { require(it in 0..MAX_ITEMS) { "Damaged folders file" } }
    private fun DataInputStream.strings(): List<String> = List(count()) { readUTF() }
    private fun DataInputStream.rules() = GroupingSwitches(readBoolean(), readBoolean(), readBoolean())
    private fun DataOutputStream.strings(list: List<String>) { writeInt(list.size); list.forEach(::writeUTF) }
    private fun DataOutputStream.rules(r: GroupingSwitches) { writeBoolean(r.stopAtHyphen); writeBoolean(r.splitAtCommas); writeBoolean(r.firstTwoWords) }

    companion object {
        private const val MAGIC = 0x53574644
        private const val VERSION = 1
        private const val MAX_ITEMS = 200_000
    }
}

/* ---------- The state every screen shares ---------- */

/**
 * The folders and folder choices, shared by every screen so a change shows up everywhere at once. Each change is
 * saved straight away. A change that cannot be made leaves a message in [problem].
 */
internal class Organizer(private val store: FolderOrganizerStore, legacy: () -> List<WindowsCustomFolder> = { emptyList() }) {
    var data by mutableStateOf(loadWithLegacy(legacy))
        private set
    var problem by mutableStateOf<String?>(null)

    private fun loadWithLegacy(legacy: () -> List<WindowsCustomFolder>): OrganizerData {
        if (store.exists()) return store.load()
        // Custom folders made before this version become Custom folders holding the same videos.
        val older = runCatching(legacy).getOrDefault(emptyList())
        val data = OrganizerData(own = older.map { OwnFolder(newId(), it.name, files = it.paths.distinct()) })
        if (older.isNotEmpty()) runCatching { store.save(data) }
        return data
    }

    private fun newId() = UUID.randomUUID().toString()

    private fun edit(block: (OrganizerData) -> OrganizerData): Boolean = try {
        val next = block(data)
        store.save(next)
        data = next
        problem = null
        true
    } catch (error: Exception) {
        problem = error.message ?: "Could not change the folder"
        false
    }

    private fun named(name: String): String? = FolderLogic.cleanName(name).also {
        if (it == null) problem = "Enter a folder name without slashes (up to 100 characters)."
    }

    // Folders include everything inside them, so a file inside a chosen folder is not kept twice.
    private fun cleanMembers(folders: Collection<String>, files: Collection<String>): Pair<List<String>, List<String>> {
        val dirs = folders.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.sortedBy { it.lowercase() }.take(MAX_FOLDERS)
        val singles = files.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
            .filterNot { file -> dirs.any { FolderLogic.isWithin(file, it) } }.take(MAX_FILES)
        return dirs to singles
    }

    /* Custom folders you fill yourself */

    fun createOwn(name: String, parentId: String? = null, files: Collection<String> = emptyList()): String? {
        val clean = named(name) ?: return null
        val id = newId()
        val (_, singles) = cleanMembers(emptyList(), files)
        return if (edit { it.copy(own = it.own + OwnFolder(id, clean, files = singles, parentId = parentId)) }) id else null
    }

    fun renameOwn(id: String, name: String) {
        val clean = named(name) ?: return
        edit { d -> d.copy(own = d.own.map { if (it.id == id) it.copy(name = clean) else it }) }
    }

    /** Removes the folder and the folders inside it from the app. Nothing on disk is touched. */
    fun deleteOwn(id: String) {
        fun withChildren(target: String, all: List<OwnFolder>): Set<String> =
            setOf(target) + all.filter { it.parentId == target }.flatMap { withChildren(it.id, all) }
        edit { d -> val gone = withChildren(id, d.own); d.copy(own = d.own.filterNot { it.id in gone }) }
    }

    fun setOwnMembers(id: String, folders: Collection<String>, files: Collection<String>) {
        val (dirs, singles) = cleanMembers(folders, files)
        edit { d -> d.copy(own = d.own.map { if (it.id == id) it.copy(folders = dirs, files = singles) else it }) }
    }

    fun addToOwn(id: String, files: Collection<String>) {
        val folder = data.own.firstOrNull { it.id == id } ?: return
        setOwnMembers(id, folder.folders, folder.files + files)
    }

    fun takeOutOfOwn(id: String, folders: Collection<String>, files: Collection<String>) {
        val folder = data.own.firstOrNull { it.id == id } ?: return
        val dirs = folders.map { it.lowercase() }.toSet()
        val singles = files.map { it.lowercase() }.toSet()
        setOwnMembers(id, folder.folders.filterNot { it.lowercase() in dirs }, folder.files.filterNot { it.lowercase() in singles })
    }

    fun setOwnGrouped(id: String, grouped: Boolean) {
        edit { d -> d.copy(own = d.own.map { if (it.id == id) it.copy(grouped = grouped) else it }) }
    }

    /* Custom folders that fill themselves */

    fun createCollection(name: String) {
        val clean = named(name) ?: return
        edit { it.copy(match = it.match + MatchFolder(newId(), clean, term = clean)) }
    }

    /** Adds drive folders to Custom folders, each named after the folder (with a number when the name is taken). */
    fun addDriveFolders(paths: Collection<String>, includeSubfolders: Boolean) {
        edit { d ->
            val added = ArrayList(d.match)
            paths.sortedBy { it.lowercase() }.forEach { path ->
                val already = added.any { it.sourcePath.equals(path, ignoreCase = true) && it.includeSubfolders == includeSubfolders }
                if (!already) {
                    val leaf = path.trimEnd('\\').substringAfterLast('\\').ifBlank { path }
                    var name = leaf
                    var suffix = 2
                    while (added.any { it.name.equals(name, ignoreCase = true) }) name = "$leaf (${suffix++})"
                    added += MatchFolder(newId(), name, term = "", sourcePath = path, includeSubfolders = includeSubfolders)
                }
            }
            d.copy(match = added)
        }
    }

    /** Renaming a filename collection also changes what it matches, like on Android. */
    fun renameMatch(id: String, name: String) {
        val clean = named(name) ?: return
        edit { d -> d.copy(match = d.match.map { if (it.id == id) it.copy(name = clean, term = if (it.sourcePath == null) clean else it.term) else it }) }
    }

    fun deleteMatch(id: String) {
        edit { d -> d.copy(match = d.match.filterNot { it.id == id }) }
    }

    fun hideMatch(ids: Collection<String>, hidden: Boolean = true) {
        edit { d -> d.copy(match = d.match.map { if (it.id in ids) it.copy(hidden = hidden) else it }) }
    }

    fun showAllMatch() {
        edit { d -> d.copy(match = d.match.map { it.copy(hidden = false) }) }
    }

    /** Files [paths] into the named folder by hand (Merge into); the name wins over the names in the file names. */
    fun assign(paths: Collection<String>, name: String) {
        val clean = named(name) ?: return
        edit { d -> d.copy(assignments = d.assignments + paths.associate { it.lowercase() to clean }) }
    }

    /* Virtual folders */

    fun createSet(name: String, parentId: String? = null): String? {
        val clean = named(name) ?: return null
        val id = newId()
        return if (edit { it.copy(sets = it.sets + VirtualSet(id, clean, parentId = parentId)) }) id else null
    }

    fun renameSet(id: String, name: String) {
        val clean = named(name) ?: return
        edit { d -> d.copy(sets = d.sets.map { if (it.id == id) it.copy(name = clean) else it }) }
    }

    fun deleteSet(id: String) {
        fun withChildren(target: String, all: List<VirtualSet>): Set<String> =
            setOf(target) + all.filter { it.parentId == target }.flatMap { withChildren(it.id, all) }
        edit { d -> val gone = withChildren(id, d.sets); d.copy(sets = d.sets.filterNot { it.id in gone }) }
    }

    fun setSetMembers(id: String, folders: Collection<String>, files: Collection<String>) {
        val (dirs, singles) = cleanMembers(folders, files)
        edit { d -> d.copy(sets = d.sets.map { if (it.id == id) it.copy(folders = dirs, files = singles) else it }) }
    }

    fun addToSet(id: String, files: Collection<String>) {
        val set = data.sets.firstOrNull { it.id == id } ?: return
        setSetMembers(id, set.folders, set.files + files)
    }

    /** Only the name in the app changes. Giving it the name of another subfolder joins the two. */
    fun renameSubfolder(setId: String, identity: String, name: String) {
        val clean = named(name)?.let(WindowsVirtualNames::display) ?: return
        edit { d ->
            d.copy(sets = d.sets.map { set ->
                if (set.id != setId) set else {
                    // A folder already renamed keeps its original name as the key, so rename every entry that points at it.
                    val had = set.renames.any { WindowsVirtualNames.identity(it.value) == identity }
                    val next = if (had) set.renames.mapValues { (_, v) -> if (WindowsVirtualNames.identity(v) == identity) clean else v }
                    else set.renames + (identity to clean)
                    set.copy(renames = next.filterNot { (k, v) -> k == WindowsVirtualNames.identity(v) })
                }
            })
        }
    }

    fun hideSubfolders(setId: String, identities: Collection<String>) {
        edit { d -> d.copy(sets = d.sets.map { if (it.id == setId) it.copy(hidden = it.hidden + identities) else it }) }
    }

    fun restoreSubfolders(setId: String) {
        edit { d -> d.copy(sets = d.sets.map { if (it.id == setId) it.copy(hidden = emptySet()) else it }) }
    }

    /* Choices */

    fun updatePrefs(change: (FolderPrefs) -> FolderPrefs) {
        edit { d -> d.copy(prefs = change(d.prefs).let { it.copy(tabs = FolderTabs.clean(it.tabs)) }) }
    }

    /** Picking the same sort again reverses it, like on Android. */
    fun selectSort(sort: String) = updatePrefs { p ->
        if (p.sort == sort) p.copy(descending = !p.descending) else p.copy(sort = sort, descending = false)
    }

    companion object {
        const val MAX_FOLDERS = 100
        const val MAX_FILES = 5000
    }
}
