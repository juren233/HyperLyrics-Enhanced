package com.lidesheng.hyperlyric.ui.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation3.runtime.NavKey

class Navigator(val backStack: MutableList<NavKey>) {
    fun navigate(route: NavKey) {
        if (backStack.lastOrNull() != route) {
            backStack.add(route)
        }
    }

    fun pop() {
        if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
        }
    }
    
    fun popUpTo(route: NavKey, inclusive: Boolean = false) {
        val index = backStack.indexOf(route)
        if (index != -1) {
             while (backStack.size > if (inclusive) index else index + 1) {
                backStack.removeAt(backStack.lastIndex)
            }
        }
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No navigator found!") }
