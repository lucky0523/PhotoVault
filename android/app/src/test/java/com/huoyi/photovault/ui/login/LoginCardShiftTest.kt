package com.huoyi.photovault.ui.login

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginCardShiftTest {

    private fun shift(
        cardBottom: Int = 1900,
        root: Int = 2376,
        ime: Int,
        margin: Int = 48
    ) = loginCardShiftPx(
        cardBottomPx = cardBottom,
        rootHeightPx = root,
        imeBottomPx = ime,
        marginPx = margin
    )

    @Test
    fun `keyboard hidden never shifts, even when the card overflows`() {
        assertEquals(0, shift(ime = 0))
        assertEquals(0, shift(cardBottom = 3000, ime = 0))
    }

    @Test
    fun `no shift before the card has been measured`() {
        assertEquals(0, shift(cardBottom = 0, root = 0, ime = 1000))
    }

    @Test
    fun `keyboard still below the card bottom margin does not shift`() {
        // imeTop = 2076 >= cardBottom + margin = 1948
        assertEquals(0, shift(ime = 300))
        // Exactly touching the margin: still 0.
        assertEquals(0, shift(ime = 2376 - 1948))
    }

    @Test
    fun `card bottom lands margin above the keyboard top`() {
        val s = shift(ime = 1000)
        assertEquals(572, s)
        val imeTop = 2376 - 1000
        assertEquals(imeTop, 1900 - s + 48)
    }

    @Test
    fun `shift follows the keyboard one to one once it reaches the card`() {
        val a = shift(ime = 800)
        val b = shift(ime = 801)
        val c = shift(ime = 1000)
        assertEquals(1, b - a)
        assertEquals(200, c - a)
    }

    @Test
    fun `large keyboard pushes the card top above the window with no upper clamp`() {
        // Card 1100..1900 (height 800); imeTop = 600, so the card must rise by 1348.
        val s = shift(ime = 1776)
        assertEquals(1900 + 48 - 600, s)
        val shiftedCardTop = 1100 - s
        assertTrue(shiftedCardTop < 0)
    }
}
