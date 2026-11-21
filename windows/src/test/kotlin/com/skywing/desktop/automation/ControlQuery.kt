package com.skywing.desktop.automation

/** A control as an accessibility client sees it, in-process (javax.accessibility) or through Java Access Bridge. */
internal interface ControlView {
    val name: String
    val description: String
    /** The US-English role string, as JAB sends it ("push button", "check box", "page tab", "label", ...). */
    val roleName: String
    val enabled: Boolean
}

/**
 * What a person hears as the control's label. Switches and check boxes are labelled by description (Compose Desktop
 * 1.8 can only fill the JAB name from text semantics, which makes NVDA call them read only), so the label is the
 * name, or the description when the name is empty.
 */
internal val ControlView.label: String get() = name.ifEmpty { description }

/**
 * The one matching rule for finding SkyWing controls: label (name, else description) and role together, optionally
 * narrowed by [where], and the match must be unique. A missing or ambiguous match throws with the candidates listed,
 * so automation never acts on a guess.
 */
/** More than one control matched. Never resolved by waiting or guessing: the caller must narrow the query. */
internal class AmbiguousMatchError(message: String) : AssertionError(message)

internal object ControlQuery {
    fun matches(control: ControlView, label: String, role: String): Boolean =
        control.roleName == role && (control.name == label || (control.name.isEmpty() && control.description == label))

    fun <T : ControlView> single(controls: List<T>, label: String, role: String, where: (T) -> Boolean = { true }): T {
        val found = controls.filter { matches(it, label, role) && where(it) }
        if (found.size > 1) throw AmbiguousMatchError("Ambiguous: ${found.size} $role controls labelled '$label': " + found.joinToString())
        return found.singleOrNull() ?: throw AssertionError(
            "No $role labelled '$label'. Same label: " + controls.filter { it.label == label }.ifEmpty { listOf("none") }.joinToString(),
        )
    }
}
