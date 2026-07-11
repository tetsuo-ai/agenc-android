package ai.tetsuo.agenc

import ai.tetsuo.agenc.ui.screens.AppRoute
import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingRouteTest {
    @Test
    fun `cold signed-out launch shows branded welcome`() {
        assertEquals(
            AppRoute.Welcome,
            resolveAppRoute(
                signedIn = false,
                hasPairedMachine = false,
                pairingStarted = false,
            ),
        )
    }

    @Test
    fun `local sign-in tap advances to pairing without authenticating`() {
        assertEquals(
            AppRoute.Pairing,
            resolveAppRoute(
                signedIn = false,
                hasPairedMachine = false,
                pairingStarted = true,
            ),
        )
    }

    @Test
    fun `legacy signed-in account without host still starts at welcome`() {
        assertEquals(
            AppRoute.Welcome,
            resolveAppRoute(
                signedIn = true,
                hasPairedMachine = false,
                pairingStarted = false,
            ),
        )
    }

    @Test
    fun `only complete auth plus host pairing enters main`() {
        assertEquals(
            AppRoute.Main,
            resolveAppRoute(
                signedIn = true,
                hasPairedMachine = true,
                pairingStarted = false,
            ),
        )
    }

    @Test
    fun `signout reset returns to welcome even if stale host metadata exists`() {
        assertEquals(
            AppRoute.Welcome,
            resolveAppRoute(
                signedIn = false,
                hasPairedMachine = true,
                pairingStarted = false,
            ),
        )
    }
}
