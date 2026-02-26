package com.craneai.tinyaya

import android.app.Application
import android.os.Environment
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.craneai.tinyaya.llama.LlamaEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val isStreaming: Boolean = false,
)

enum class ModelState {
    NOT_FOUND,
    LOADING,
    READY,
    GENERATING,
    ERROR,
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "ChatViewModel"
    }

    private val _state = MutableStateFlow(ModelState.LOADING)
    val state: StateFlow<ModelState> = _state

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _statusText = MutableStateFlow("Initializing...")
    val statusText: StateFlow<String> = _statusText

    /** Emits when a new token is streamed so the UI can scroll. */
    private val _tokenEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val tokenEvent: SharedFlow<Unit> = _tokenEvent

    private var engine: LlamaEngine? = null
    private var generateJob: Job? = null

    private val modelFilename: String
        get() = getApplication<Application>().getString(R.string.model_filename)

    init {
        loadModel()
    }

    private fun findModelFile(): File? {
        val app = getApplication<Application>()
        val searchPaths = listOf(
            app.getExternalFilesDir(null),
            app.filesDir,
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            Environment.getExternalStorageDirectory(),
        )
        for (dir in searchPaths) {
            if (dir == null) continue
            val candidate = File(dir, modelFilename)
            Log.d(TAG, "Checking: ${candidate.absolutePath} -> exists=${candidate.exists()}")
            if (candidate.exists() && candidate.length() > 0) {
                Log.d(TAG, "Found model: ${candidate.absolutePath}")
                return candidate
            }
        }
        return null
    }

    private fun loadModel() {
        viewModelScope.launch {
            _state.value = ModelState.LOADING
            _statusText.value = "Looking for model file..."

            val modelFile = withContext(Dispatchers.IO) { findModelFile() }

            if (modelFile == null) {
                _state.value = ModelState.NOT_FOUND
                _statusText.value = getApplication<Application>().getString(R.string.no_model_message)
                return@launch
            }

            _statusText.value = "Loading model (%.1f GB)...".format(
                modelFile.length() / 1_000_000_000.0
            )

            try {
                val eng = LlamaEngine.getInstance(getApplication())
                eng.loadModel(modelFile.absolutePath)
                engine = eng
                _state.value = ModelState.READY
                _statusText.value = "Ready"
            } catch (e: Exception) {
                _state.value = ModelState.ERROR
                _statusText.value = "Error: ${e.message}"
            }
        }
    }

    fun sendMessage(userText: String) {
        if (userText.isBlank() || _state.value != ModelState.READY) return

        val eng = engine ?: return

        // Add user message
        _messages.value = _messages.value + ChatMessage(userText, isUser = true)

        // Add placeholder for bot response
        _messages.value = _messages.value + ChatMessage("", isUser = false, isStreaming = true)
        _state.value = ModelState.GENERATING
        _statusText.value = "Generating..."

        generateJob = viewModelScope.launch {
            try {
                val responseBuilder = StringBuilder()
                eng.sendMessage(userText).collect { token ->
                    responseBuilder.append(token)
                    val msgs = _messages.value.toMutableList()
                    msgs[msgs.lastIndex] = ChatMessage(
                        responseBuilder.toString(),
                        isUser = false,
                        isStreaming = true,
                    )
                    _messages.value = msgs
                    _tokenEvent.tryEmit(Unit)
                }
                // Mark streaming complete
                val msgs = _messages.value.toMutableList()
                msgs[msgs.lastIndex] = ChatMessage(
                    responseBuilder.toString(),
                    isUser = false,
                    isStreaming = false,
                )
                _messages.value = msgs
                _state.value = ModelState.READY
                _statusText.value = "Ready"
            } catch (e: Exception) {
                val msgs = _messages.value.toMutableList()
                if (msgs.isNotEmpty() && !msgs.last().isUser) {
                    val current = msgs.last().text
                    val errorText = if (current.isEmpty()) "[Error: ${e.message}]"
                    else "$current\n\n[Error: ${e.message}]"
                    msgs[msgs.lastIndex] = ChatMessage(errorText, isUser = false)
                }
                _messages.value = msgs
                _state.value = ModelState.READY
                _statusText.value = "Ready"
            }
        }
    }

    fun stopGenerating() {
        engine?.stopGeneration()
        generateJob?.cancel()
        generateJob = null
        val msgs = _messages.value.toMutableList()
        if (msgs.isNotEmpty() && !msgs.last().isUser && msgs.last().isStreaming) {
            msgs[msgs.lastIndex] = msgs.last().copy(isStreaming = false)
            _messages.value = msgs
        }
        _state.value = ModelState.READY
        _statusText.value = "Ready"
    }

    override fun onCleared() {
        super.onCleared()
        generateJob?.cancel()
        engine?.destroy()
        engine = null
    }
}
