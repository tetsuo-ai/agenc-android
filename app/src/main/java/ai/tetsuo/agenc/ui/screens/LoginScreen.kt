package ai.tetsuo.agenc.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.tetsuo.agenc.R
import ai.tetsuo.agenc.ui.AgenCTheme
import ai.tetsuo.agenc.ui.Onboarding

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
 * Pixel-faithful to the iOS sign-in: the `agenc` wordmark filled with an animated purple shimmer
 * over the marketplace gradient background, a centered subtitle, and a full-width purple "Sign in"
 * pill (no identifier field — mock identity approves instantly, exactly like iOS). When a login is
 * in flight (`isLoading`/`deviceCode != null`) it swaps to a spinner + "signing in" + Cancel.
 *
 * Stateless: every piece of state and every callback is passed in.
 *
 * @param deviceCode non-null once a device code is issued; treated as "in flight" (spinner state).
 * @param isLoading true while a login is in flight (shows the spinner state).
 * @param error optional error string rendered under the action.
 * @param onStart fires when the user taps Sign in; always passes null (no identifier on this screen).
 */
@Composable
fun LoginScreen(
    deviceCode: DeviceCodeUi?,
    isLoading: Boolean,
    error: String?,
    onStart: (identifier: String?) -> Unit,
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
                SigningInState(onCancel = { onStart(null) })
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
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(27.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Onboarding.accent),
        ) {
            Text("Sign in", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Color.White)
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
        TextButton(onClick = onCancel) {
            Text("Cancel", color = Onboarding.accent, fontSize = 15.sp)
        }
    }
}

/**
 * The real `agenc` wordmark vector, masked with the iOS shimmer: a purple
 * accentLight→accent→accentDeep gradient that sweeps left↔right forever (~2.6s), plus a soft glow.
 */
@Composable
private fun ShimmeringWordmark() {
    val transition = rememberInfiniteTransition(label = "wordmark-shimmer")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shimmer-phase",
    )

    // A wide sweep so the gradient travels fully across the wordmark.
    val travel = 900f
    val gradient = Brush.linearGradient(
        colors = listOf(
            Onboarding.accentLight,
            Onboarding.accent,
            Onboarding.accentDeep,
            Onboarding.accent,
            Onboarding.accentLight,
        ),
        start = Offset(-travel + travel * 2f * phase, 0f),
        end = Offset(travel * 2f * phase, 0f),
    )

    Box(contentAlignment = Alignment.Center) {
        // Soft purple glow behind the wordmark.
        Box(
            modifier = Modifier
                .height(52.dp)
                .width(200.dp)
                .graphicsLayer { alpha = 0.55f }
                .background(
                    Brush.radialGradient(
                        colors = listOf(Onboarding.accent.copy(alpha = 0.45f), Color.Transparent),
                        radius = 220f,
                    ),
                ),
        )
        Image(
            painter = painterResource(R.drawable.agenc_wordmark),
            contentDescription = "agenc",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .height(52.dp)
                // Offscreen compositing is REQUIRED for BlendMode.SrcIn to clip the gradient to the
                // logotype's pixels — without it the mask has no destination and nothing renders.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                // Paint the white logotype, then keep the shimmer gradient only over its pixels.
                .drawWithContent {
                    drawContent()
                    drawRect(brush = gradient, blendMode = BlendMode.SrcIn)
                },
        )
    }
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
            tint = Onboarding.accent,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = error,
            color = Color(0xFFEE9430),
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
