package com.chinar.assistant.coordinator

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Declares the mutually exclusive owners of the Android AudioRecord resource.
 *
 * At any moment, the microphone MUST belong to only the component permitted by the current state:
 * - STANDBY: Owned by WAKE_WORD_DETECTOR.
 * - VERIFYING_OWNER: Owned by SPEAKER_VERIFIER.
 * - CONVERSATION: Owned by LIVE_SESSION (GeminiLiveClient / RealtimeAudioPipeline).
 * - TEARDOWN / IDLE: NONE.
 *
 * Prevents competing AudioRecord instances, audio resource starvation, and microphone lockups.
 */
enum class MicrophoneOwner {
    NONE,
    WAKE_WORD_DETECTOR,
    SPEAKER_VERIFIER,
    LIVE_SESSION,
    COMMAND_RECOGNITION
}

class MicrophoneOwnershipManager private constructor() {

    companion object {
        private const val TAG = "MicrophoneOwnership"

        @Volatile
        private var instance: MicrophoneOwnershipManager? = null

        fun getInstance(): MicrophoneOwnershipManager {
            return instance ?: synchronized(this) {
                instance ?: MicrophoneOwnershipManager().also { instance = it }
            }
        }
    }

    private val mutex = Mutex()
    private val _currentOwner = MutableStateFlow(MicrophoneOwner.NONE)
    val currentOwner: StateFlow<MicrophoneOwner> = _currentOwner.asStateFlow()

    /**
     * Attempts to acquire microphone ownership for [newOwner].
     * If the microphone is already held by [newOwner], returns true.
     * If held by another owner, notifies [onRevokePrevious] and transfers ownership.
     */
    suspend fun acquire(
        newOwner: MicrophoneOwner,
        correlationId: String? = null,
        onRevokePrevious: (() -> Unit)? = null
    ): Boolean {
        return mutex.withLock {
            val prev = _currentOwner.value
            val tag = if (correlationId != null) "[$correlationId] " else ""
            if (prev == newOwner) {
                return@withLock true
            }

            if (prev != MicrophoneOwner.NONE) {
                Log.w(TAG, "${tag}[MIC_CONTENTION] $newOwner requested mic while held by $prev. Requesting release...")
                onRevokePrevious?.invoke()
            }

            _currentOwner.value = newOwner
            Log.d(TAG, "${tag}[MIC_ACQUIRED] Microphone ownership transferred: $prev -> $newOwner")
            true
        }
    }

    /**
     * Releases ownership if held by [owner].
     */
    suspend fun release(owner: MicrophoneOwner, correlationId: String? = null) {
        mutex.withLock {
            val tag = if (correlationId != null) "[$correlationId] " else ""
            if (_currentOwner.value == owner) {
                _currentOwner.value = MicrophoneOwner.NONE
                Log.d(TAG, "${tag}[MIC_RELEASED] Microphone released by $owner")
            } else {
                Log.d(TAG, "${tag}[MIC_RELEASE_IGNORED] Release requested by $owner, but current owner is ${_currentOwner.value}")
            }
        }
    }

    /**
     * Forcefully releases any active microphone owner (e.g. on emergency stop, service kill).
     */
    suspend fun forceRelease(correlationId: String? = null) {
        mutex.withLock {
            val tag = if (correlationId != null) "[$correlationId] " else ""
            val prev = _currentOwner.value
            _currentOwner.value = MicrophoneOwner.NONE
            Log.d(TAG, "${tag}[MIC_FORCE_RELEASE] Forcibly cleared microphone ownership (was $prev)")
        }
    }

    fun isOwner(owner: MicrophoneOwner): Boolean = _currentOwner.value == owner
}
