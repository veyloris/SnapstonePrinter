package com.example.snapstoneprinter.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.snapstoneprinter.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppModeActivityRecreationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun activityRecreationRetainsMomirModeAndCmcRouting() {
        compose.onNodeWithContentDescription("Switch game mode").performClick()
        compose.onNodeWithText("MomirVig").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("MomirVig").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pick a CMC").assertIsDisplayed().performClick()
        compose.onNodeWithText("Pick a converted mana cost").assertIsDisplayed()
    }
}
