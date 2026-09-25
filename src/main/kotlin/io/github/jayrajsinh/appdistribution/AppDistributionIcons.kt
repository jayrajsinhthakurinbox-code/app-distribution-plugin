package io.github.jayrajsinh.appdistribution

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object AppDistributionIcons {

    /** Tool window button (has a _dark variant). */
    @JvmField
    val ToolWindow: Icon = IconLoader.getIcon("/icons/toolWindow.svg", AppDistributionIcons::class.java)

    /** 32×32 logo shown in the tool window header. */
    @JvmField
    val Logo: Icon = IconLoader.getIcon("/icons/logo.svg", AppDistributionIcons::class.java)
}
