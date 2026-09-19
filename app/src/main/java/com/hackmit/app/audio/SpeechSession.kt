package com.hackmit.app.audio

/**
 * One speech recording session: mic -> Deepgram -> [SpeechAnalyzer].
 *
 * When [apiKey] is blank the session still runs but produces no transcript, so the
 * UI can fall back to demo data. The caller controls start/stop; there is no timer.
 */
class SpeechSession(
    private val apiKey: String,
    private val endpoint: String = DeepgramClient.DEFAULT_ENDPOINT,
    private val onTranscript: (String) -> Unit = {},
    private val onStatus: (String) -> Unit = {},
) {

    private val analyzer = SpeechAnalyzer()
    private var capture: AudioCapture? = null
    private var deepgram: DeepgramClient? = null

    val isLive: Boolean get() = apiKey.isNotBlank()

    /** Returns true if the microphone started successfully. */
    fun start(): Boolean {
        analyzer.start()
        onTranscript("")
        if (apiKey.isBlank()) {
            onStatus("Demo mode: no Deepgram key set (Settings)")
            return false
        }
        val client = DeepgramClient(apiKey, endpoint)
        deepgram = client
        client.connect(
            onTranscript = { update ->
                onTranscript(update.transcript)
                analyzer.onTranscript(update)
            },
            onStatus = onStatus,
            onError = onStatus,
        )
        val recorder = AudioCapture { bytes -> client.send(bytes) }
        capture = recorder
        val started = recorder.start()
        if (!started) onStatus("Microphone unavailable")
        return started
    }

    fun stop(): SpeechMetrics {
        capture?.stop()
        capture = null
        deepgram?.finish()
        deepgram?.close()
        deepgram = null
        return analyzer.finish()
    }

    /** Non-destructive metrics snapshot for live UI updates while recording. */
    fun peek(): SpeechMetrics = analyzer.finish()
}
