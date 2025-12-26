package com.example.actrdemo

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.actorrtc.demo.R

class ClientActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var messageInput: EditText
    private lateinit var sendButton: Button
    private lateinit var logText: TextView
    private lateinit var scrollView: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_client)

        // Initialize views
        statusText = findViewById(R.id.statusText)
        connectButton = findViewById(R.id.connectButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)
        logText = findViewById(R.id.logText)
        scrollView = findViewById(R.id.scrollView)

        // Get signaling URL from intent
        val signalingUrl =
                intent.getStringExtra("signalingUrl") ?: "ws://10.0.2.2:8081/signaling/ws"

        // Set up button click listeners
        connectButton.setOnClickListener {
            statusText.text = "Status: Connecting..."
            connectButton.isEnabled = false
            disconnectButton.isEnabled = true
            messageInput.isEnabled = true
            sendButton.isEnabled = true
            // TODO: Implement connection logic
        }

        disconnectButton.setOnClickListener {
            statusText.text = "Status: Disconnecting..."
            connectButton.isEnabled = true
            disconnectButton.isEnabled = false
            messageInput.isEnabled = false
            sendButton.isEnabled = false
            // TODO: Implement disconnection logic
        }

        sendButton.setOnClickListener {
            val message = messageInput.text.toString()
            if (message.isNotEmpty()) {
                // TODO: Send message
                log("Sent: $message")
                messageInput.text.clear()
            }
        }
    }

    private fun log(message: String) {
        val currentTime =
                java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                        .format(java.util.Date())
        val logEntry = "[$currentTime] $message\n"
        logText.append(logEntry)

        // Auto scroll to bottom
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
