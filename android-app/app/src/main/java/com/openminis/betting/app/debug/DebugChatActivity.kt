package com.openminis.betting.app.debug

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.openminis.betting.app.CrashReporter
import com.openminis.betting.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DebugChatActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var inputEditText: TextInputEditText
    private lateinit var sendButton: ImageButton
    private lateinit var connectionStatus: TextView
    private lateinit var clearButton: MaterialButton
    private lateinit var exportLogsButton: MaterialButton
    private lateinit var getLogcatButton: MaterialButton

    private val scope = CoroutineScope(Dispatchers.Main)
    private var debugService: RemoteDebugService? = null
    private var isBound = false

    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder) {
            val binder = service as RemoteDebugService.LocalBinder
            debugService = binder.getService()
            isBound = true
            updateConnectionStatus()
            
            debugService?.sendChatMessage("Debug chat connected from device")
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            isBound = false
            debugService = null
            updateConnectionStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug_chat)

        CrashReporter.init(this)

        recyclerView = findViewById(R.id.messagesRecyclerView)
        inputEditText = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)
        connectionStatus = findViewById(R.id.connectionStatus)
        clearButton = findViewById(R.id.clearButton)
        exportLogsButton = findViewById(R.id.exportLogsButton)
        getLogcatButton = findViewById(R.id.getLogcatButton)

        messageAdapter = MessageAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = messageAdapter

        sendButton.setOnClickListener { sendMessage() }
        clearButton.setOnClickListener { messageAdapter.clear() }
        exportLogsButton.setOnClickListener { requestLogs() }
        getLogcatButton.setOnClickListener { requestLogcat() }

        val intent = Intent(this, RemoteDebugService::class.java)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
        startService(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun sendMessage() {
        val text = inputEditText.text.toString().trim()
        if (TextUtils.isEmpty(text)) return

        messageAdapter.addMessage(Message(text, true, System.currentTimeMillis()))
        inputEditText.text?.clear()
        scrollToBottom()

        debugService?.sendChatMessage(text)

        scope.launch {
            kotlinx.coroutines.delay(500)
            messageAdapter.addMessage(Message("AI: Processing '$text'... (connect to real AI backend)", false, System.currentTimeMillis()))
            scrollToBottom()
        }
    }

    private fun requestLogs() {
        debugService?.let { service ->
            val id = java.util.UUID.randomUUID().toString()
            service.sendMessage(org.json.JSONObject()
                .put("type", "GET_LOGS")
                .put("id", id)
                .put("lines", 200)
                .toString())
            messageAdapter.addMessage(Message("📋 Requesting logs... (check crash logs viewer)", false, System.currentTimeMillis()))
            scrollToBottom()
        }
    }

    private fun requestLogcat() {
        debugService?.let { service ->
            val id = java.util.UUID.randomUUID().toString()
            service.sendMessage(org.json.JSONObject()
                .put("type", "GET_LOGCAT")
                .put("id", id)
                .put("lines", 500)
                .toString())
            messageAdapter.addMessage(Message("📟 Requesting logcat...", false, System.currentTimeMillis()))
            scrollToBottom()
        }
    }

    private fun updateConnectionStatus() {
        val connected = debugService?.isConnected() == true
        runOnUiThread {
            connectionStatus.text = if (connected) "🟢 Connected to Debug Server" else "🔴 Disconnected"
            connectionStatus.setTextColor(if (connected) 0xFF4CAF50.toInt() else 0xFFF44336.toInt())
        }
    }

    private fun scrollToBottom() {
        recyclerView.post { recyclerView.smoothScrollToPosition(messageAdapter.itemCount - 1) }
    }

    data class Message(val text: String, val isUser: Boolean, val timestamp: Long)

    inner class MessageAdapter : RecyclerView.Adapter<MessageViewHolder>() {
        private val messages = mutableListOf<Message>()

        fun addMessage(message: Message) {
            messages.add(message)
            notifyItemInserted(messages.size - 1)
        }

        fun clear() {
            messages.clear()
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): MessageViewHolder {
            val view = layoutInflater.inflate(R.layout.item_debug_message, parent, false)
            return MessageViewHolder(view)
        }

        override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
            holder.bind(messages[position])
        }

        override fun getItemCount(): Int = messages.size
    }

    inner class MessageViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        private val textView: TextView = view.findViewById(R.id.messageText)
        private val timeView: TextView = view.findViewById(R.id.messageTime)

        fun bind(message: Message) {
            textView.text = message.text
            textView.setTextAlignment(if (message.isUser) View.TEXT_ALIGNMENT_VIEW_END else View.TEXT_ALIGNMENT_VIEW_START)
            textView.background = if (message.isUser) {
                itemView.context.getDrawable(R.drawable.bg_message_sent)
            } else {
                itemView.context.getDrawable(R.drawable.bg_message_received)
            }
            val time = android.text.format.DateFormat.format("HH:mm:ss", message.timestamp)
            timeView.text = time
        }
    }
}