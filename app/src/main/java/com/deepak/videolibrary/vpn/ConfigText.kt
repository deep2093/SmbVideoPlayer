package com.deepak.videolibrary.vpn

/** Pure text helpers for WireGuard configurations. No Android or WireGuard classes, so they are unit-testable. */
object ConfigText {
    /** Upper bound for an imported file. Real configurations are well under 4 KiB. */
    const val MAX_IMPORT_BYTES = 64 * 1024

    private val APP_KEYS = Regex("""^\s*(includedapplications|excludedapplications)\s*=""", RegexOption.IGNORE_CASE)
    private val INTERFACE_HEADER = Regex("""^\s*\[interface]\s*(#.*)?$""", RegexOption.IGNORE_CASE)

    /**
     * Restricts the tunnel to [packageName] by writing `IncludedApplications = <package>` into the
     * `[Interface]` section. The official GoBackend turns that into
     * `VpnService.Builder.addAllowedApplication()`. Any Included/ExcludedApplications lines already in
     * the file are removed, because Android does not allow allowed and disallowed lists together.
     * The text is returned unchanged when there is no `[Interface]` section.
     */
    fun restrictToApp(text: String, packageName: String): String {
        val lines = text.lines()
        if (lines.none { INTERFACE_HEADER.matches(it) }) return text
        val out = ArrayList<String>(lines.size + 1)
        var inserted = false
        for (line in lines) {
            if (APP_KEYS.containsMatchIn(line)) continue
            out.add(line)
            if (!inserted && INTERFACE_HEADER.matches(line)) {
                out.add("IncludedApplications = $packageName")
                inserted = true
            }
        }
        return out.joinToString("\n")
    }
}
