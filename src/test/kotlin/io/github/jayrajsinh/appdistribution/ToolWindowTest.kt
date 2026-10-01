package io.github.jayrajsinh.appdistribution

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import javax.swing.JRadioButton

/**
 * Builds the tool window in a headless IDE. The UI DSL validates layouts at
 * runtime (e.g. how radio buttons may be added), which compiling can't catch.
 */
class ToolWindowTest : BasePlatformTestCase() {

    fun testToolWindowContentIsCreated() {
        val content = AppDistributionToolWindowFactory.ReleasePanel(project)

        val radios = UIUtil.findComponentsOfType(content, JRadioButton::class.java)
        assertEquals(listOf("Release", "Debug"), radios.map { it.text })
    }
}
