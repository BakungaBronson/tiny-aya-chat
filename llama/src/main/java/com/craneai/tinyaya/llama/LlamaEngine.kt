package com.craneai.tinyaya.llama

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Kotlin wrapper around the llama.cpp JNI bridge for on-device LLM inference.
 *
 * Usage:
 * ```
 * val engine = LlamaEngine.getInstance(context)
 * engine.loadModel("/path/to/model.gguf")
 * engine.sendMessage("Hello").collect { token -> print(token) }
 * engine.destroy()
 * ```
 */
class LlamaEngine private constructor() {

    @Volatile
    private var loaded = false

    @Volatile
    private var cancelled = false

    companion object {
        private var instance: LlamaEngine? = null

        fun getInstance(context: Context): LlamaEngine {
            return instance ?: synchronized(this) {
                instance ?: LlamaEngine().also { engine ->
                    instance = engine
                    // Load native libraries
                    System.loadLibrary("ai-chat")
                    // Initialize ggml backends from the native lib directory
                    val nativeLibDir = context.applicationInfo.nativeLibraryDir
                    engine.init(nativeLibDir)
                }
            }
        }
    }

    /**
     * Load a GGUF model file and prepare for inference.
     * This is a suspend function — call from a coroutine.
     */
    suspend fun loadModel(modelPath: String) {
        withContext(Dispatchers.IO) {
            if (loaded) {
                unload()
                loaded = false
            }

            val result = load(modelPath)
            if (result != 0) {
                throw RuntimeException("Failed to load model: error code $result")
            }

            val prepResult = prepare()
            if (prepResult != 0) {
                throw RuntimeException("Failed to prepare context: error code $prepResult")
            }

            loaded = true
        }
    }

    /**
     * Send a message and receive streaming tokens via Flow<String>.
     * Each emission is a token (or partial UTF-8 character as empty string).
     */
    fun sendMessage(text: String, maxTokens: Int = 2048): Flow<String> = flow {
        cancelled = false

        val result = processUserPrompt(text, maxTokens)
        if (result != 0) {
            throw RuntimeException("Failed to process prompt: error code $result")
        }

        while (!cancelled) {
            val token = generateNextToken() ?: break
            if (token.isNotEmpty()) {
                emit(token)
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Signal the engine to stop generating tokens.
     */
    fun stopGeneration() {
        cancelled = true
    }

    /**
     * Release model resources. Can call loadModel() again after this.
     */
    fun release() {
        if (loaded) {
            unload()
            loaded = false
        }
    }

    /**
     * Fully shut down the engine. Cannot be reused after this.
     */
    fun destroy() {
        release()
        shutdown()
        instance = null
    }

    fun isLoaded(): Boolean = loaded

    // -- JNI native methods --

    private external fun init(nativeLibDir: String)
    private external fun load(modelPath: String): Int
    private external fun prepare(): Int
    external fun systemInfo(): String
    private external fun processSystemPrompt(prompt: String): Int
    private external fun processUserPrompt(prompt: String, predictLength: Int): Int
    private external fun generateNextToken(): String?
    private external fun unload()
    private external fun shutdown()
}
