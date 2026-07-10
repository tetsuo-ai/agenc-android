package ai.tetsuo.agenc.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.tetsuo.agenc.R
import ai.tetsuo.agenc.ui.AgenCTheme
import ai.tetsuo.agenc.ui.Brand
import ai.tetsuo.agenc.ui.HapticCue
import ai.tetsuo.agenc.ui.Onboarding
import ai.tetsuo.agenc.ui.rememberAgenCHaptics

/**
 * What the device-flow login surfaces to the UI once the backend hands back a user code.
 * Both fields are nullable so the screen can render its "starting…" state before either arrives.
 */
data class DeviceCodeUi(
    val userCode: String?,
    val verificationUri: String?,
)

/**
 * Branded, dark passwordless login — the Android port of iOS `SignInView`.
 *
 * Monochrome sign-in: the `agenc` wordmark carries a subtle light sweep over a deep black canvas,
 * with one high-contrast primary action. When a login is
 * in flight (`isLoading`/`deviceCode != null`) it swaps to a spinner + "signing in" + Cancel.
 *
 * Stateless: every piece of state and every callback is passed in.
 *
 * @param deviceCode non-null once a device code is issued; treated as "in flight" (spinner state).
 * @param isLoading true while a login is in flight (shows the spinner state).
 * @param error optional error string rendered under the action.
 * @param onStart fires when the user taps Sign in; always passes null (no identifier on this screen).
 * @param onCancel cancels the active device-flow poll.
 */
@Composable
fun LoginScreen(
    deviceCode: DeviceCodeUi?,
    isLoading: Boolean,
    error: String?,
    onStart: (identifier: String?) -> Unit,
    onCancel: () -> Unit = {},
) {
    val inFlight = isLoading || deviceCode != null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Onboarding.bg)
            .onboardingBackground(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.weight(1f))

            ShimmeringWordmark()
            Spacer(Modifier.height(18.dp))
            Text(
                text = "sign in, then pair your computer to control it from anywhere",
                color = Onboarding.ink.copy(alpha = 0.6f),
                fontSize = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.weight(1f))

            if (inFlight) {
                SigningInState(onCancel = onCancel)
            } else {
                SignInControls(error = error, onStart = { onStart(null) })
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SignInControls(
    error: String?,
    onStart: () -> Unit,
) {
    val haptics = rememberAgenCHaptics()
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = {
                haptics.perform(HapticCue.Action)
                onStart()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(27.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Onboarding.accent),
        ) {
            Text("Sign in", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Onboarding.bg)
        }

        if (error != null) {
            ErrorLabel(error)
        } else {
            Text(
                text = "passwordless sign in",
                color = Onboarding.ink.copy(alpha = 0.45f),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The polling state from iOS: a spinner, "signing in", and a Cancel text button. */
@Composable
private fun SigningInState(onCancel: () -> Unit) {
    val haptics = rememberAgenCHaptics()
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            color = Onboarding.accent,
            strokeWidth = 2.5.dp,
        )
        Text(
            text = "signing in",
            color = Onboarding.ink.copy(alpha = 0.6f),
            fontSize = 15.sp,
        )
        TextButton(
            onClick = {
                haptics.perform(HapticCue.Action)
                onCancel()
            },
        ) {
            Text("Cancel", color = Onboarding.accent, fontSize = 15.sp)
        }
    }
}

/** The real `agenc` wordmark, kept crisp on the monochrome onboarding canvas. */
@Composable
private fun ShimmeringWordmark() {
    Image(
        painter = painterResource(R.drawable.agenc_wordmark),
        contentDescription = "agenc",
        contentScale = ContentScale.Fit,
        modifier = Modifier.height(52.dp),
    )
}

@Composable
private fun ErrorLabel(error: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.WarningAmber,
            contentDescription = null,
            tint = Brand.danger,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = error,
            color = Onboarding.ink,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview(name = "Login — start", showBackground = true, backgroundColor = 0xFF101116)
@Composable
private fun LoginScreenStartPreview() {
    AgenCTheme {
        LoginScreen(deviceCode = null, isLoading = false, error = null, onStart = {})
    }
}

@Preview(name = "Login — loading", showBackground = true, backgroundColor = 0xFF101116)
@Composable
private fun LoginScreenLoadingPreview() {
    AgenCTheme {
        LoginScreen(deviceCode = null, isLoading = true, error = null, onStart = {})
    }
}

@Preview(name = "Login — error", showBackground = true, backgroundColor = 0xFF101116)
@Composable
private fun LoginScreenErrorPreview() {
    AgenCTheme {
        LoginScreen(
            deviceCode = null,
            isLoading = false,
            error = "Couldn't reach the sign-in service. Try again.",
            onStart = {},
        )
    }
}
