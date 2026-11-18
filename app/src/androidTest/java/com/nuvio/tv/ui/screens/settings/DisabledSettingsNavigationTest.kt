package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DisabledSettingsNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun disabledOptionsCanBeReadAboveAndBelowAccountsWithoutActivation() {
        var activations = 0
        compose.setContent {
            NuvioTheme {
                Column {
                    SettingsToggleRow(
                        title = "Cloud library", subtitle = null, checked = false,
                        onToggle = { activations++ }, enabled = false,
                        focusableWhenDisabled = true
                    )
                    SettingsActionRow(title = "Account", subtitle = null, onClick = {})
                    SettingsActionRow(
                        title = "Formatting", subtitle = null,
                        onClick = { activations++ }, enabled = false,
                        focusableWhenDisabled = true
                    )
                }
            }
        }
        val account = compose.onNodeWithText("Account")
        account.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        account.performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Formatting").assertIsFocused().assertIsNotEnabled().performClick()
        compose.onNodeWithText("Formatting").performKeyInput { pressKey(Key.DirectionUp) }
        account.assertIsFocused().performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithText("Cloud library").assertIsFocused().assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, activations) }
    }
}
