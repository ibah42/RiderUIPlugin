package com.hitapps.allmanview

import com.intellij.ui.ColorUtil
import java.awt.Color

/**
 * Every colour this plugin draws is the same shape: a base colour moved some percent towards
 * another one. One place for that formula, so the mechanics cannot drift apart on what a
 * percentage means or on how an out-of-range value is treated.
 */
object ColorBalance {

    /** No percentage setting in the plugin goes outside this range. */
    const val MAX_PERCENT = 100

    /** 0 keeps [base] untouched, 100 returns [target]. Anything outside is clamped. */
    fun mix(base: Color, target: Color, percent: Int): Color {
        return ColorUtil.mix(base, target, balance(percent))
    }

    /**
     * Towards plain grey rather than towards black or white: grey is darker than a light
     * background and lighter than a dark one, so a single percentage works in both themes.
     */
    fun towardsGrey(base: Color, percent: Int): Color {
        return mix(base, Color.GRAY, percent)
    }

    private fun balance(percent: Int): Double {
        return percent.coerceIn(0, MAX_PERCENT) / MAX_PERCENT.toDouble()
    }
}
