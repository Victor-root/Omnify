package com.looker.droidify.compose.externalApps

import androidx.compose.ui.unit.Constraints
import org.junit.Assert.assertThrows
import org.junit.Test

class ReadmeHeightTest {

    // What a README of a given height asks of the layout, full width: the constraints its height gives.
    private fun readmeConstraints(height: Int) =
        Constraints(minWidth = 0, maxWidth = Constraints.Infinity, minHeight = height, maxHeight = height)

    @Test
    fun theTallestReadmeCanStillBeLaidOut() {
        readmeConstraints(MAX_README_HEIGHT_PX)
    }

    @Test
    fun aTallerOneCouldNot() {
        assertThrows(IllegalArgumentException::class.java) { readmeConstraints(MAX_README_HEIGHT_PX + 1) }
    }
}
