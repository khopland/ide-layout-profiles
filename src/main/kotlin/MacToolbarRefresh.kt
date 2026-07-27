@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package io.github.khopland

import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.mac.MacFullScreenControlsManager

internal object MacToolbarRefresh {
    fun update(visible: Boolean) {
        if (SystemInfo.isMac) {
            MacFullScreenControlsManager.updateForNewMainToolbar(visible)
        }
    }
}
