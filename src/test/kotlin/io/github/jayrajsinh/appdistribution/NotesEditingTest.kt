package io.github.jayrajsinh.appdistribution

import junit.framework.TestCase

class NotesEditingTest : TestCase() {

    private fun press(textWithCaret: String): String {
        val caret = textWithCaret.indexOf('|')
        val (text, newCaret) = NotesEditing.continueList(textWithCaret.replace("|", ""), caret)
        return text.substring(0, newCaret) + "|" + text.substring(newCaret)
    }

    fun testStartsABulletFromPlainText() {
        assertEquals("Known issues\n- |", press("Known issues|"))
    }

    fun testContinuesABullet() {
        assertEquals("- Dark mode\n- |", press("- Dark mode|"))
        assertEquals("  * Nested\n  * |", press("  * Nested|"))
    }

    fun testContinuesNumbering() {
        assertEquals("1. First\n2. |", press("1. First|"))
        assertEquals("9) Ninth\n10) |", press("9) Ninth|"))
    }

    fun testEmptyBulletEndsTheList() {
        assertEquals("- Dark mode\n|", press("- Dark mode\n- |"))
        assertEquals("1. First\n|", press("1. First\n2. |"))
    }

    fun testWorksInTheMiddleOfText() {
        assertEquals("- One\n- |\n- Two", press("- One|\n- Two"))
    }
}
