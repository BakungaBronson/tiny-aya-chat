package com.craneai.tinyaya

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
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

    private val _state = MutableStateFlow(ModelState.LOADING)
    val state: StateFlow<ModelState> = _state

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages

    private val _statusText = MutableStateFlow("Initializing...")
    val statusText: StateFlow<String> = _statusText

    /** Emits when a new token is streamed so the UI can scroll. */
    private val _tokenEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val tokenEvent: SharedFlow<Unit> = _tokenEvent

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var generateJob: Job? = null

    // Model filename
    private val modelFilename = "tiny-aya-global_q8_ekv2048.litertlm"

    init {
        loadModel()
    }

    private fun findModelFile(): File? {
        val candidates = listOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), modelFilename),
            File(getApplication<Application>().filesDir, modelFilename),
            File(getApplication<Application>().getExternalFilesDir(null), modelFilename),
            File("/sdcard/Download/$modelFilename"),
            File("/storage/emulated/0/Download/$modelFilename"),
        )
        return candidates.firstOrNull { it.exists() && it.length() > 0 }
    }

    private fun loadModel() {
        viewModelScope.launch {
            _state.value = ModelState.LOADING
            _statusText.value = "Looking for model file..."

            val modelFile = withContext(Dispatchers.IO) { findModelFile() }

            if (modelFile == null) {
                _state.value = ModelState.NOT_FOUND
                _statusText.value = "Model not found. Copy $modelFilename to Downloads."
                return@launch
            }

            _statusText.value = "Loading model (%.1f GB)...".format(
                modelFile.length() / 1_000_000_000.0
            )

            try {
                withContext(Dispatchers.IO) {
                    val config = EngineConfig(
                        modelFile.absolutePath,
                        Backend.CPU,
                    )
                    val eng = Engine(config)
                    eng.initialize()
                    engine = eng
                    conversation = eng.createConversation()
                }
                _state.value = ModelState.READY
                _statusText.value = "Ready"
            } catch (e: Exception) {
                _state.value = ModelState.ERROR
                _statusText.value = "Error: ${e.message}"
            }
        }
    }

    /** Extract text from a Message's contents. */
    private fun messageToText(msg: Message): String {
        val parts = msg.contents.contents
        return parts.filterIsInstance<Content.Text>()
            .joinToString("") { it.text }
    }

    fun sendMessage(userText: String) {
        if (userText.isBlank() || _state.value != ModelState.READY) return

        val conv = conversation ?: return

        // Add user message
        _messages.value = _messages.value + ChatMessage(userText, isUser = true)

        // Add placeholder for bot response
        _messages.value = _messages.value + ChatMessage("", isUser = false, isStreaming = true)
        _state.value = ModelState.GENERATING
        _statusText.value = "Generating..."

        generateJob = viewModelScope.launch {
            try {
                val responseBuilder = StringBuilder()
                withContext(Dispatchers.IO) {
                    conv.sendMessageAsync(userText).collect { message ->
                        val chunk = messageToText(message)
                        responseBuilder.append(chunk)
                        withContext(Dispatchers.Main) {
                            val msgs = _messages.value.toMutableList()
                            msgs[msgs.lastIndex] = ChatMessage(
                                responseBuilder.toString(),
                                isUser = false,
                                isStreaming = true,
                            )
                            _messages.value = msgs
                            _tokenEvent.tryEmit(Unit)
                        }
                    }
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
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
    }
}
