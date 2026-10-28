# Qurban AI — Whole-Phone Autonomous Voice Assistant

**Qurban AI** is a voice-first, whole-phone autonomous AI assistant built natively for Android (optimized for MediaTek Dimensity 9000 & ColorOS / OnePlus Ace 2V) powered by **Google Gemini Live**, on-device **Kaldi / Vosk** wake-word detection, speaker verification, and accessibility-based whole-phone automation.

---

## Key Features

1. **Local Hands-Free Wake-Word Detection (Vosk)**:
   - Ultra low-power on-device acoustic keyword spotting.
   - Activates on:
     - `"Qurban"`
     - `"Haa Qurban"`
     - `"Hey Qurban"`
     - `"A Qurban"`
   - Strict grammar filtering eliminates ambient false-positive triggers.

2. **Strict 4-Language Intelligence**:
   - Strictly restricted to operate in **English**, **Pashto (پښتو)**, **Urdu (اردو)**, and **Hindi (हिन्दी)** (including conversational Hinglish, Roman Urdu, and Roman Pashto).
   - Accurately matches the language, script, and tone of inbound messages.

3. **Autonomous Whole-Phone Automation**:
   - Single-command WhatsApp messaging (`send_whatsapp_message`) without home screen bouncing.
   - In-chat conversational bot mode: reads incoming chat messages (`read_chat_messages`) and generates contextual replies (`reply_in_chat`) in the sender's language.

4. **Floating Assistant Overlay**:
   - Sleek system overlay capsule that floats over any application (YouTube, Browser, Settings).
   - Real-time subtitle streaming, pulsating avatar, and non-blocking phone interaction.

5. **Iconic Voice Packs & Neural Speech**:
   - **JARVIS** (Charon / deep British intellect)
   - **FRIDAY** (Aoede / warm Irish efficiency)
   - **EDITH** (Sulafat / crisp tactical clarity)
   - **ULTRON** (Fenrir / imposing authoritative bass)
   - **ALEXA** (Aoede / polite household assistant)
   - **SIRI** (Kore / clear responsive helper)

6. **Ultra-Low Latency Speech Pipeline**:
   - Multi-layer intent caching & TTS phrase audio caching (<10ms instant hits).
   - Streamed chunk-based TTS pipelining (~40ms first-chunk playback).

---

## Architecture Overview

* **AI Reasoning Engine**: Gemini Live Realtime Audio WebSocket protocol (`AiReasoningEngine`, `GeminiLiveClient`).
* **On-Device Wake-Word Detection**: `VoskWakeWordDetector` with constrained phonetic grammar.
* **Biometric Speaker Verification**: `SpeakerVerificationEngine` with Kaldi deep x-vectors.
* **Automation Engine**: `ChinarAccessibilityService`, `UiActionExecutor`, `ScreenObserver`, `AppIntegrations`.
* **Audio & Speech Pipeline**: `AndroidTtsEngine` with clause chunking, `TtsAudioCache`, `RealtimeAudioPipeline`.
* **State Coordination**: `AssistantSessionCoordinator`, `ConversationStateMachine`, `MicrophoneOwnershipManager`.

---

## Getting Started

### Prerequisites
* Android Studio Ladybug / Meerkat or later
* Android SDK (API 34+)
* Kotlin 2.0+
* Gradle 8.14+
* Connected Android Device (Android 14–16)

### Building and Installing
```bash
# Clone the repository
git clone https://github.com/Sahilo5657/qurban_ai.git
cd qurban_ai

# Navigate to android module
cd android

# Run tests
./gradlew testDebugUnitTest

# Install on connected device
./gradlew installDebug
```

---

## License
MIT License. See [LICENSE](chinar/LICENSE) for details.

