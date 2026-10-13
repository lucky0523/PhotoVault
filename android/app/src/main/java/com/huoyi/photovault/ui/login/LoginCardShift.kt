package com.huoyi.photovault.ui.login

import androidx.compose.ui.unit.dp

/** Gap kept between the login card's bottom edge and the keyboard's top edge. */
internal val LoginCardKeyboardMargin = 16.dp

/**
 * How far (px, >= 0) the login card is moved up while the keyboard is shown.
 *
 * All inputs are window pixels measured in the card's UN-shifted position, so the
 * result never feeds back into its own inputs.
 *
 * - Keyboard hidden ([imeBottomPx] <= 0) or card not yet measured ([rootHeightPx] <= 0):
 *   0, the card stays where layout put it.
 * - Otherwise the card moves up just far enough that its bottom edge sits [marginPx]
 *   above the keyboard top (`rootHeightPx - imeBottomPx`); 0 if it already does.
 *   The rule is the same whichever field is focused, and there is no upper clamp
 *   (user decision): the card top, including the top fields, may go under the
 *   status bar or off-screen.
 */
internal fun loginCardShiftPx(
    cardBottomPx: Int,
    rootHeightPx: Int,
    imeBottomPx: Int,
    marginPx: Int
): Int {
    if (imeBottomPx <= 0 || rootHeightPx <= 0) return 0
    val imeTop = rootHeightPx - imeBottomPx
    return maxOf(0, cardBottomPx + marginPx - imeTop)
}
