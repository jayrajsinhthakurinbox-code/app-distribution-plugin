package io.github.jayrajsinh.appdistribution

import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.JTextArea
import javax.swing.KeyStroke

/**
 * Shift+Return in the release notes box continues a list: it starts a new
 * "- " bullet (or the next number after "3. "), and on an empty bullet it
 * removes the bullet instead, ending the list. Plain Return is unchanged.
 */
object NotesEditing {

    fun install(area: JTextArea) {
        val key = "appdist.continueList"

        area.inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), key)
        area.actionMap.put(key, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) {
                val (text, caret) = continueList(area.text, area.caretPosition)
                area.text = text
                area.caretPosition = caret
            }
        })
    }

    /** New text and caret after Shift+Return at [caret]. */
    fun continueList(text: String, caret: Int): Pair<String, Int> {
        val lineStart = text.lastIndexOf('\n', caret - 1) + 1
        val line = text.substring(lineStart, caret)

        val number = NUMBERED.find(line)
        val bullet = BULLET.find(line)

        // Empty bullet or number: remove it to end the list
        if ((bullet != null && line.length == bullet.value.length) ||
            (number != null && line.length == number.value.length)
        ) {
            val after = text.substring(caret)
            return text.substring(0, lineStart) + after to lineStart
        }

        val prefix = when {
            number != null -> {
                val (indent, n, sep) = number.destructured
                "$indent${n.toInt() + 1}$sep "
            }
            bullet != null -> bullet.value
            else -> "- "
        }

        val insert = "\n" + prefix
        return text.substring(0, caret) + insert + text.substring(caret) to caret + insert.length
    }

    // "  - ", "* ", "• "
    private val BULLET = Regex("""^\s*[-*•]\s+""")

    // "  3. ", "4) "
    private val NUMBERED = Regex("""^(\s*)(\d{1,3})([.)])\s+""")
}
