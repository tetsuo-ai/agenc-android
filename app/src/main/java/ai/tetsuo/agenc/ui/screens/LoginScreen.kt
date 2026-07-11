package ai.tetsuo.agenc.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
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
import ai.tetsuo.agenc.ui.HapticCue
import ai.tetsuo.agenc.ui.Onboarding
import ai.tetsuo.agenc.ui.rememberAgenCHaptics

/**
 * Branded welcome screen for the CLI-owned pairing flow.
 *
 * Monochrome sign-in: the `agenc` wordmark carries a subtle light sweep over a deep black canvas,
 * with one high-contrast primary action. "Sign in" is local navigation only: the following pairing
 * code/QR authenticates the phone through the already-signed-in Core CLI.
 *
 * No network request, browser, OAuth flow, poll, or loading state originates from this screen.
 */
@Composable
fun WelcomeScreen(onContinue: () -> Unit) {
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

            WelcomeControls(onContinue = onContinue)

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun WelcomeControls(onContinue: () -> Unit) {
    val haptics = rememberAgenCHaptics()
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = {
                haptics.perform(HapticCue.Action)
                onContinue()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(27.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Onboarding.accent),
        ) {
            Text("Sign in", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Onboarding.bg)
        }

        Text(
            text = "pair securely with the code from AgenC Core",
            color = Onboarding.ink.copy(alpha = 0.45f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
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

@Preview(name = "Welcome", showBackground = true, backgroundColor = 0xFF101116)
@Composable
private fun WelcomeScreenPreview() {
    AgenCTheme {
        WelcomeScreen(onContinue = {})
    }
}
