package ai.tetsuo.agenc.notif

/** Pure lifecycle/retry policy used by [BackgroundLinkService]. */
internal object BackgroundLinkPolicy {
    const val WATCHDOG_MILLIS = 30_000L

    fun shouldRun(
        appInForeground: Boolean,
        signedIn: Boolean,
        pairedMachine: Boolean,
    ): Boolean = !appInForeground && signedIn && pairedMachine

}
