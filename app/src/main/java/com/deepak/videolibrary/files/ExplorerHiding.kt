package com.deepak.videolibrary.files

/** Which drive entries File Explorer leaves out: system folders (optional) and items the user hid. */
object ExplorerHiding {
    /** Windows, NAS and media-server housekeeping folders and files. */
    private val systemNames = setOf(
        "\$recycle.bin",
        "system volume information",
        "recycler",
        "recycled",
        "#recycle",
        "#snapshot",
        "@eadir",
        "@recycle",
        "@recently-snapshot",
        "lost+found",
        "thumbs.db",
        "desktop.ini",
    )

    /** True for system or hidden names: the list above, and names starting with "." (such as .TPDLNA) or "$". */
    fun isSystemName(name: String): Boolean {
        val n = name.trim().lowercase()
        return n.isNotEmpty() && (n in systemNames || n.startsWith('.') || n.startsWith('$'))
    }

    /** True when [path] or one of its parent folders is in [hiddenPaths] (share-relative, forward slashes). */
    fun isHiddenPath(path: String, hiddenPaths: Set<String>): Boolean {
        if (hiddenPaths.isEmpty()) return false
        var current = path.trim('/')
        while (current.isNotEmpty()) {
            if (current in hiddenPaths) return true
            current = current.substringBeforeLast('/', "")
        }
        return false
    }

    fun shouldHide(path: String, name: String, hideSystem: Boolean, hiddenPaths: Set<String>): Boolean =
        (hideSystem && isSystemName(name)) || isHiddenPath(path, hiddenPaths)
}
