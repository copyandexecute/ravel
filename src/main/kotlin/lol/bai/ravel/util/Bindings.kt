@file:Suppress("FunctionName")

package lol.bai.ravel.util

import com.intellij.DynamicBundle
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

fun A(clazz: Class<out AnAction>): AnAction =
    ActionManager.getInstance().getAction(clazz.name)

inline fun <reified T : AnAction> A() = A(T::class.java)

@NonNls
private const val BUNDLE = "messages.RavelBundle"

object B : DynamicBundle(BUNDLE) {
    val error by lazy { B("dialog.error.title") }
}

fun B(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any) = B.getMessage(key, *params)
