package com.skywing.desktop

import com.skywing.desktop.automation.ControlQuery
import com.skywing.desktop.automation.ControlView
import com.skywing.desktop.automation.label
import java.util.Locale
import javax.accessibility.Accessible
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleRole
import javax.accessibility.AccessibleState

/**
 * One control as Java Access Bridge sees it. JAB serialises exactly these javax.accessibility fields, so tests and
 * automation that read them see what NVDA, JAWS and JAB automation tools receive.
 */
internal class AccessibleControl(val context: AccessibleContext, val path: String) : ControlView {
    override val name: String get() = context.accessibleName.orEmpty()
    override val description: String get() = context.accessibleDescription.orEmpty()
    val role: AccessibleRole get() = context.accessibleRole
    override val roleName: String get() = role.toDisplayString(Locale.US)
    val checked: Boolean get() = context.accessibleStateSet.contains(AccessibleState.CHECKED)
    val selected: Boolean get() = context.accessibleStateSet.contains(AccessibleState.SELECTED)
    override val enabled: Boolean get() = context.accessibleStateSet.contains(AccessibleState.ENABLED)
    val value: Number? get() = context.accessibleValue?.currentAccessibleValue

    /** NVDA reports any JAB object that has a text interface but no editable state as "read only". */
    val hasTextInterface: Boolean get() = context.accessibleText != null

    override fun toString() = "'$label' $roleName (name='$name', description='$description') at $path"
}

/**
 * Finds controls in a Java accessibility tree for tests and automation. Matching is by visible label (name, else
 * description) and role together, and must be unique: an ambiguous or missing match is an error, never a guess.
 */
internal object AccessibleControls {
    fun all(root: Accessible): List<AccessibleControl> {
        val found = mutableListOf<AccessibleControl>()
        fun visit(context: AccessibleContext, path: String, depth: Int) {
            found += AccessibleControl(context, path)
            if (depth > 60) return
            for (i in 0 until context.accessibleChildrenCount) {
                context.getAccessibleChild(i)?.accessibleContext?.let { visit(it, "$path/$i", depth + 1) }
            }
        }
        root.accessibleContext?.let { visit(it, "0", 0) }
        return found
    }

    /**
     * The single control with this label and role, optionally narrowed by [where] (for example `{ it.value != null }`
     * to pick a slider from the visible caption that shares its label and label role). Throws, listing candidates,
     * when there is none or more than one.
     */
    fun find(root: Accessible, label: String, role: AccessibleRole, where: (AccessibleControl) -> Boolean = { true }): AccessibleControl =
        ControlQuery.single(all(root), label, role.toDisplayString(Locale.US), where)

    /** Performs the control's first accessible action (JAB "click") after checking it is enabled and actionable. */
    fun activate(control: AccessibleControl) {
        check(control.enabled) { "Refusing to activate disabled control $control" }
        val action = checkNotNull(control.context.accessibleAction) { "Control has no accessible action: $control" }
        check(action.accessibleActionCount > 0) { "Control has no accessible action: $control" }
        check(action.doAccessibleAction(0)) { "Accessible action failed: $control" }
    }
}
