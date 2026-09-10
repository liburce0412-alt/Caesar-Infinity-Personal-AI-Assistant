package com.campusai

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import com.campusai.app.AuthScreen
import com.campusai.core.auth.AuthState
import com.campusai.core.designsystem.CampusTheme
import com.campusai.core.network.parseSignUpResponse
import com.campusai.core.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AuthFlowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `registration mode requires invitation alongside email and password confirmation`() {
        compose.setContent {
            CampusTheme(ThemeMode.LIGHT) {
                AuthScreen(
                    state = AuthState(),
                    onSignIn = { _, _ -> false },
                    onSignUp = { _, _, _ -> false },
                    onClearMessage = {},
                    onBack = {},
                )
            }
        }

        compose.onNodeWithText("注册").performClick()
        compose.onAllNodesWithText("邮箱").assertCountEquals(1)
        compose.onAllNodesWithText("密码").assertCountEquals(1)
        compose.onAllNodesWithText("确认密码").assertCountEquals(1)
        compose.onAllNodesWithText("邀请码").assertCountEquals(1)
        compose.onAllNodesWithText("直接注册并登录").assertCountEquals(1)
    }

    @Test fun `signup response creates a direct session when email confirmation is off`() {
        val result = parseSignUpResponse(
            """{"access_token":"access","refresh_token":"refresh","user":{"id":"user-1","email":"new@campus.test"}}""",
            "fallback@campus.test",
        )
        assertNotNull(result.session)
        assertEquals("user-1", result.userId)
        assertEquals("new@campus.test", result.email)
    }

    @Test fun `signup response exposes missing session when server requires confirmation`() {
        val result = parseSignUpResponse(
            """{"user":{"id":"user-2","email":"confirm@campus.test"}}""",
            "fallback@campus.test",
        )
        assertNull(result.session)
        assertEquals("user-2", result.userId)
    }
}
