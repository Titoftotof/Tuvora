package com.nuvio.tv.ui.components.player

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.nuvio.tv.core.picture.AspectMode
import com.nuvio.tv.ui.theme.NuvioTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w960dp-h540dp-land")
@ConscryptMode(ConscryptMode.Mode.OFF)
class AspectModePickerDialogTest {
    @get:Rule val rule = createComposeRule()

    @Test fun `remote selects a mode without closing the panel then applies it to all`() {
        var mode by mutableStateOf(AspectMode.ORIGINAL)
        var applied: AspectMode? = null
        var dismissed = false
        rule.setContent {
            NuvioTheme {
                AspectModePickerDialog(mode, { mode = it }, { applied = mode }, { dismissed = true })
            }
        }
        rule.onNode(isSelected()).assertIsFocused()
        rule.onNode(isSelected()).performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionCenter)
        }
        rule.runOnIdle {
            assertEquals(AspectMode.FULL_SCREEN, mode)
            assertFalse(dismissed)
            assertNull(applied)
        }
        rule.onNode(isSelected()).assertIsFocused()
        rule.onNode(isSelected()).performKeyInput {
            repeat(6) { pressKey(Key.DirectionDown) }
        }
        rule.onNodeWithText("Apply to all movies and series").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        rule.runOnIdle { assertEquals(AspectMode.FULL_SCREEN, applied) }
    }

    @Test fun `tunneling offers only the two modes the surface supports`() {
        rule.setContent {
            NuvioTheme {
                AspectModePickerDialog(AspectMode.STRETCH, {}, {}, {}, tunnelingEnabled = true)
            }
        }
        rule.onAllNodes(hasClickAction()).assertCountEquals(3)
        rule.onNode(isSelected()).assertIsFocused()
    }
}
