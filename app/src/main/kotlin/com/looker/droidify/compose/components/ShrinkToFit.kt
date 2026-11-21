package com.looker.droidify.compose.components

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/** The smallest a label is shrunk to: still readable, and below it the label is cut instead. */
private val MIN_FIT_TEXT_SIZE = 10.sp

/** Shrinks a one-line label from [size] just enough to show it whole, where large text would cut it
 *  ("Expl…" for Explore in the home's tabs). */
fun shrinkToFit(size: TextUnit): TextAutoSize = TextAutoSize.StepBased(minFontSize = MIN_FIT_TEXT_SIZE, maxFontSize = size)
