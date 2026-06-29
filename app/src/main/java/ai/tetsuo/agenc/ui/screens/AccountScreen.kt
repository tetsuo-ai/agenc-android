package ai.tetsuo.agenc.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.tetsuo.agenc.model.Identity
import ai.tetsuo.agenc.model.Tier
import ai.tetsuo.agenc.ui.AgenCTheme
import ai.tetsuo.agenc.ui.Brand

/**
 * Account screen — the Android port of the identity + logout slice of iOS `AccountView`.
 *
 * Follows the SYSTEM appearance (light or dark): a big bold "Account" title, an identity card, and a
 * Log out action. The floating bottom tab bar lives in the Main shell, so this screen leaves room at
 * the bottom and renders no bar of its own.
 *
 * Stateless: identity is passed in (null while it loads), logout is a callback.
 *
 * @param identity the signed-in identity, or null while loading.
 * @param onLogout fires when the user taps Log out.
 */
@Composable
fun AccountScreen(
    identity: Identity?,
    onLogout: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Account",
            color = colors.onBackground,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 16.dp),
        )

        ProfileCard(identity)
        Spacer(Modifier.weight(1f))
        LogoutButton(onLogout)
        // Leave room for the floating tab bar.
        Spacer(Modifier.height(110.dp))
    }
}

@Composable
private fun ProfileCard(identity: Identity?) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceVariant, RoundedCornerShape(18.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(Brand.accent.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                tint = Brand.accent,
                modifier = Modifier.size(38.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = identity?.displayName ?: "Loading…",
            color = colors.onSurface,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (identity != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = identity.handle,
                color = colors.onSurfaceVariant,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(12.dp))
            TierBadge(identity.tier)
        }
    }
}

@Composable
private fun TierBadge(tier: Tier) {
    Box(
        modifier = Modifier
            .background(Brand.accent.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            text = tier.label,
            color = Brand.accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun LogoutButton(onLogout: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brand.danger.copy(alpha = 0.12f), RoundedCornerShape(14.dp))
            .clickable(onClick = onLogout)
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Logout,
            contentDescription = null,
            tint = Brand.danger,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text("Log out", color = Brand.danger, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Preview(name = "Account — signed in", showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun AccountScreenPreview() {
    AgenCTheme {
        AccountScreen(
            identity = Identity(displayName = "Pavel C.", handle = "@pavel", tier = Tier.pro),
            onLogout = {},
        )
    }
}

@Preview(name = "Account — loading", showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun AccountScreenLoadingPreview() {
    AgenCTheme {
        AccountScreen(identity = null, onLogout = {})
    }
}
