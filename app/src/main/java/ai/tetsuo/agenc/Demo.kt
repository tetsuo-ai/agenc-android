package ai.tetsuo.agenc

/**
 * Scripted demo mode for local walkthroughs. Public builds keep this disabled and intentionally do
 * not contain a live destination or a non-zero transfer amount.
 */
object Demo {
    /** Turn the scripted voice → transfer flow on/off. */
    const val ENABLED = false

    /** Intentionally unset. A developer must supply a destination in a private local build. */
    const val AGENT_WALLET = ""

    const val AMOUNT_LAMPORTS = 0L
    const val AMOUNT_SOL = 0.0

    /** What the recorded voice message "says" (shown as its transcript caption). */
    const val VOICE_TRANSCRIPT = "Review a transfer to my agent wallet"

    /** The scripted reply after physical approval and RPC submission (not chain confirmation). */
    const val SUCCESS_REPLY =
        "The transfer was submitted to Solana and is awaiting confirmation. Want something else?"

    /** Beat (ms) between the voice message and the Ledger flow opening — reads as the agent thinking. */
    const val THINK_DELAY_MS = 1400L

    /** Stable id for the pre-seeded demo chat (so it isn't duplicated on relaunch). */
    const val SESSION_ID = "demo-agent-wallet"
    const val SESSION_TITLE = "AgenC agent"
    const val SESSION_CWD = "~/agenc-agent"

    /** A short prior conversation seeded into the demo chat so it looks lived-in on camera. */
    val SEED: List<Pair<String, String>> = listOf(   // role (user|assistant) to text
        "user" to "hey, are you my agent?",
        "assistant" to "Yes — I'm your AgenC agent. I can run tasks, manage your wallet, and take voice commands. What do you need?",
        "user" to "what's my agent wallet for?",
        "assistant" to "It funds the work I do on-chain — paying for tasks and services. You can top it up any time from your Ledger. Just say the word.",
    )
}
