package ai.tetsuo.agenc

/**
 * Scripted demo mode for the recorded walkthrough: sending a voice message in the chat triggers a
 * REAL Ledger transfer (signed on the Flex, broadcast on-chain) to the agent wallet, then posts a
 * fixed success reply. The trigger + amount + copy are deterministic so the on-camera flow is fast
 * and reliable — it does NOT depend on the LLM transcribing/interpreting the audio.
 */
object Demo {
    /** Turn the scripted voice → transfer flow on/off. */
    const val ENABLED = true

    /** The agent wallet the demo transfer funds (same account used in the live test). */
    const val AGENT_WALLET = "7duZYJ9DzLnWFbo7NRCkGV4npMYWydRdN9S4T7TSd4NK"

    /** 0.05 SOL in lamports. */
    const val AMOUNT_LAMPORTS = 50_000_000L
    const val AMOUNT_SOL = 0.05

    /** What the recorded voice message "says" (shown as its transcript caption). */
    const val VOICE_TRANSCRIPT = "Send 0.05 SOL to my agent wallet"

    /** The agent's reply after the transfer confirms on the Flex + broadcasts. */
    const val SUCCESS_REPLY =
        "The transfer for your agent was sent successfully to your agent. Want something else?"

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
