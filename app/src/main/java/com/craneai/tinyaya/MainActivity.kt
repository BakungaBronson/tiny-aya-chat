package com.craneai.tinyaya

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.craneai.tinyaya.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: ChatViewModel by viewModels()

    private val pickModelFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.loadModelFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupInput()
        observeViewModel()
    }

    private fun setupInput() {
        binding.btnSend.setOnClickListener {
            if (viewModel.state.value == ModelState.GENERATING) {
                viewModel.stopGenerating()
            } else {
                sendCurrentMessage()
            }
        }

        binding.btnBrowse.setOnClickListener {
            pickModelFile.launch(arrayOf("*/*"))
        }

        binding.etMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND && viewModel.state.value == ModelState.READY) {
                sendCurrentMessage()
                true
            } else false
        }
    }

    private fun sendCurrentMessage() {
        val text = binding.etMessage.text?.toString()?.trim() ?: return
        if (text.isEmpty()) return
        binding.etMessage.text?.clear()
        viewModel.sendMessage(text)
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect { updateUiForState(it) } }
                launch { viewModel.statusText.collect { binding.tvStatus.text = it } }
                launch { viewModel.messages.collect { renderMessages(it) } }
                launch { viewModel.tokenEvent.collect { scrollToBottom() } }
            }
        }
    }

    private fun updateUiForState(state: ModelState) {
        when (state) {
            ModelState.NOT_FOUND -> {
                binding.btnSend.isEnabled = false
                binding.etMessage.isEnabled = false
                binding.btnBrowse.visibility = View.VISIBLE
            }
            ModelState.LOADING -> {
                binding.btnSend.isEnabled = false
                binding.etMessage.isEnabled = false
                binding.btnBrowse.visibility = View.GONE
            }
            ModelState.READY -> {
                binding.btnSend.isEnabled = true
                binding.btnSend.text = getString(R.string.btn_send)
                binding.etMessage.isEnabled = true
                binding.btnBrowse.visibility = View.GONE
            }
            ModelState.GENERATING -> {
                binding.btnSend.isEnabled = true
                binding.btnSend.text = getString(R.string.btn_stop)
                binding.etMessage.isEnabled = false
                binding.btnBrowse.visibility = View.GONE
            }
            ModelState.ERROR -> {
                binding.btnSend.isEnabled = false
                binding.etMessage.isEnabled = false
                binding.btnBrowse.visibility = View.GONE
            }
        }
    }

    private fun renderMessages(messages: List<ChatMessage>) {
        binding.chatContainer.removeAllViews()
        for (msg in messages) {
            val bubble = createBubble(msg)
            binding.chatContainer.addView(bubble)
        }
        scrollToBottom()
    }

    private fun createBubble(msg: ChatMessage): View {
        val tv = TextView(this).apply {
            text = if (msg.isStreaming && msg.text.isEmpty()) "..." else msg.text
            textSize = 16f
            setTextColor(getColor(R.color.text_primary))
            setPadding(dp(16), dp(12), dp(16), dp(12))

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(
                    if (msg.isUser) dp(48) else dp(4),
                    dp(4),
                    if (msg.isUser) dp(4) else dp(48),
                    dp(4),
                )
                gravity = if (msg.isUser) Gravity.END else Gravity.START
            }
            layoutParams = params

            setBackgroundColor(
                getColor(if (msg.isUser) R.color.user_bubble else R.color.bot_bubble)
            )

            if (msg.isStreaming) {
                setTypeface(null, Typeface.ITALIC)
            }
        }
        return tv
    }

    private fun scrollToBottom() {
        binding.scrollView.post {
            binding.scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
