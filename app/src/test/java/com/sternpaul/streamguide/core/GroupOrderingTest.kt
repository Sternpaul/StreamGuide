package com.sternpaul.streamguide.core

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupOrderingTest {
    @Test
    fun customOrderPlacesKnownGroupsFirstAndAppendsNewGroups() {
        assertEquals(
            listOf("Sports", "News", "Kids"),
            GroupOrdering.apply(listOf("News", "Kids", "Sports"), listOf("Sports", "News"))
        )
    }

    @Test
    fun moveCategoryOneStepPreservesTheRestOfTheOrder() {
        assertEquals(
            listOf("News", "Kids", "Sports"),
            GroupOrdering.move(listOf("News", "Sports", "Kids"), "Kids", -1)
        )
    }

    @Test
    fun moveToTopKeepsEveryGroupExactlyOnce() {
        assertEquals(
            listOf("Kids", "News", "Sports"),
            GroupOrdering.moveToTop(listOf("News", "Sports", "Kids"), "Kids")
        )
    }
}
