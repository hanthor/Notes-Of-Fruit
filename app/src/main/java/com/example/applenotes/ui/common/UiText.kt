package com.example.applenotes.ui.common

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalResources

/**
 * User-visible text built outside Compose (ViewModels, error mapping) without
 * holding a Context (issue #17). Resolved to a string in the UI, in the
 * current locale, with [asString] / [resolve].
 *
 * Format arguments may themselves be [UiText] (e.g. "Couldn't save: %1$s"
 * with the error's own message); they are resolved first.
 */
sealed interface UiText {
    /** Text that is not ours to translate: a server message, an exception's own text, a note title. */
    data class Plain(val value: String) : UiText

    data class Res(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    /** A quantity string; [args] default to just [count]. */
    data class Plural(
        @param:PluralsRes val id: Int,
        val count: Int,
        val args: List<Any> = listOf(count),
    ) : UiText

    fun resolve(resources: Resources): String = when (this) {
        is Plain -> value
        is Res -> if (args.isEmpty()) resources.getString(id) else resources.getString(id, *resolveArgs(args, resources))
        is Plural -> resources.getQuantityString(id, count, *resolveArgs(args, resources))
    }

    companion object {
        private fun resolveArgs(args: List<Any>, resources: Resources): Array<Any> =
            args.map { if (it is UiText) it.resolve(resources) else it }.toTypedArray()
    }
}

/** Shorthand for [UiText.Res] with format arguments. */
fun uiText(@StringRes id: Int, vararg args: Any): UiText = UiText.Res(id, args.toList())

/** Resolves [this] with the current configuration (so it follows a locale change). */
@Composable
@ReadOnlyComposable
fun UiText.asString(): String = resolve(LocalResources.current)
