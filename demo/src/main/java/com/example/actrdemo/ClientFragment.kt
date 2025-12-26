package com.example.actrdemo

// 使用 protobuf 生成的消息类
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.LocalFileServiceWorkload
import com.example.MyLocalFileService
import io.actor_rtc.actr.ActrId
import io.actor_rtc.actr.PayloadType
import io.actor_rtc.actr.WorkloadBridge
import io.actor_rtc.actr.dsl.*
import io.actorrtc.demo.R
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import local_file.File.SendFileRequest
import local_file.File.SendFileResponse

/** Client Fragment - handles client connection and message sending */
class ClientFragment : Fragment() {

    companion object {
        private const val TAG = "ClientFragment"
        private const val REALM_ID = 2281844430u

        fun newInstance(): ClientFragment {
            return ClientFragment()
        }
    }

    // Callback to get signaling URL from parent activity
    var getSignalingUrl: (() -> String)? = null

    // UI elements
    private var clientStatusText: TextView? = null
    private var connectButton: Button? = null
    private var disconnectButton: Button? = null
    private var messageInput: EditText? = null
    private var sendButton: Button? = null
    private var streamButton: Button? = null
    private var logText: TextView? = null
    private var scrollView: ScrollView? = null

    // Client state
    private var clientSystem: ActrSystem? = null
    private var clientRef: ActrRef? = null
    // Echo client uses SimpleWorkload
    private var echoClientWorkload: SimpleWorkload? = null
    private var echoClientRef: ActrRef? = null
    private var echoServerId: ActrId? = null
    // File transfer uses LocalFileServiceWorkload
    private var fileTransferWorkload: WorkloadBridge? = null
    private var fileTransferRef: ActrRef? = null
    private var fileTransferContent: String? = null

    // Background executor
    private val executor = Executors.newSingleThreadExecutor()
    private val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Log buffer
    private val logBuffer = StringBuilder()

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_client, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        clientStatusText = view.findViewById(R.id.clientStatusText)
        connectButton = view.findViewById(R.id.connectButton)
        disconnectButton = view.findViewById(R.id.disconnectButton)
        messageInput = view.findViewById(R.id.messageInput)
        sendButton = view.findViewById(R.id.sendButton)
        streamButton = view.findViewById(R.id.streamButton)
        logText = view.findViewById(R.id.logText)
        scrollView = view.findViewById(R.id.scrollView)

        setupListeners()

        // Flush buffered logs
        if (logBuffer.isNotEmpty()) {
            logText?.text = logBuffer.toString()
            scrollToBottom()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        clientStatusText = null
        connectButton = null
        disconnectButton = null
        messageInput = null
        sendButton = null
        streamButton = null
        logText = null
        scrollView = null
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
        coroutineScope.cancel()
        executor.shutdown()
    }

    private fun setupListeners() {
        connectButton?.setOnClickListener { connect() }
        disconnectButton?.setOnClickListener { disconnect() }
        sendButton?.setOnClickListener { sendMessage() }
        streamButton?.setOnClickListener { sendDataStream() }
    }

    private fun connect() {
        updateUI(isConnecting = true)
        appendLog("Connecting to ACTR network...")

        coroutineScope.launch {
            try {
                val configPath = copyAssetToInternalStorage("client-config.toml")
                appendLog("Config path: $configPath")

                clientSystem = createActrSystem(configPath)
                appendLog("ActrSystem created")

                // 1. Create Echo client using SimpleWorkload
                echoClientWorkload =
                        SimpleWorkload(realmId = REALM_ID, typeString = "acme:echo-client-app")
                val echoNode = clientSystem!!.attach(echoClientWorkload!!)
                echoClientRef = echoNode.start()
                appendLog("Echo client started: ${echoClientRef?.actorId()?.serialNumber}")

                // 2. Create File Transfer client using LocalFileServiceWorkload
                // Note: We'll create this on-demand when sending, so we can set custom content
                appendLog("File Transfer workload will be created on-demand")

                // Discover EchoService for RPC calls
                appendLog("Discovering EchoService...")
                var echoFound = false
                for (attempt in 1..5) {
                    try {
                        echoServerId = echoClientRef?.discoverOne("acme:EchoService")
                        if (echoServerId != null) {
                            appendLog("✅ Found EchoService: ${echoServerId!!.toShortString()}")
                            echoClientWorkload?.setTargetServerId(echoServerId!!)
                            echoFound = true
                            break
                        }
                    } catch (e: Exception) {
                        appendLog("EchoService discovery attempt $attempt failed: ${e.message}")
                    }
                    delay(1000)
                }

                // Check FileTransferService availability (discovery will happen in
                // LocalFileService)
                appendLog("Checking FileTransferService availability...")
                var fileTransferFound = false
                for (attempt in 1..3) {
                    try {
                        val fileTransferServerId =
                                echoClientRef?.discoverOne("acme:FileTransferService")
                        if (fileTransferServerId != null) {
                            appendLog(
                                    "✅ FileTransferService available: ${fileTransferServerId.toShortString()}"
                            )
                            fileTransferFound = true
                            break
                        }
                    } catch (e: Exception) {
                        appendLog("FileTransferService check attempt $attempt failed: ${e.message}")
                    }
                    delay(1000)
                }

                withContext(Dispatchers.Main) {
                    val status = buildString {
                        append("Connected - ")
                        if (echoFound) append("Echo:✓ ") else append("Echo:✗ ")
                        if (fileTransferFound) append("Stream:✓") else append("Stream:✗")
                    }

                    if (echoFound || fileTransferFound) {
                        updateUI(isConnected = true)
                        updateStatus(status)
                    } else {
                        appendLog("❌ No servers found")
                        updateUI(isConnected = false)
                        updateStatus("No servers found")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connect failed", e)
                appendLog("❌ Connection failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    updateUI(isConnected = false)
                    updateStatus("Connection failed")
                }
            }
        }
    }

    private fun disconnect() {
        appendLog("Disconnecting...")

        coroutineScope.launch {
            try {
                // Shutdown file transfer client if exists
                fileTransferRef?.shutdown()
                fileTransferRef?.awaitShutdown()
                fileTransferRef = null
                fileTransferWorkload = null

                // Shutdown echo client
                echoClientRef?.shutdown()
                echoClientRef?.awaitShutdown()
                echoClientRef = null
                echoClientWorkload = null

                clientSystem = null
                echoServerId = null

                appendLog("✅ Disconnected")
                withContext(Dispatchers.Main) {
                    updateUI(isConnected = false)
                    updateStatus("Disconnected")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Disconnect error", e)
                appendLog("Disconnect error: ${e.message}")
            }
        }
    }

    private fun sendMessage() {
        val message = messageInput?.text?.toString()?.trim() ?: ""
        if (message.isEmpty()) {
            appendLog("⚠️ Please enter a message")
            return
        }

        if (echoClientRef == null || echoServerId == null) {
            appendLog("⚠️ EchoService not connected")
            return
        }

        appendLog("📤 Sending RPC: \"$message\"")
        messageInput?.setText("")

        coroutineScope.launch {
            try {
                val requestPayload = encodeEchoRequest(message)
                val responsePayload =
                        echoClientRef!!.call(
                                echoServerId!!,
                                "echo.EchoService.Echo",
                                requestPayload
                        )
                val response = decodeEchoResponse(responsePayload)
                appendLog("📥 Response: \"$response\"")
            } catch (e: Exception) {
                Log.e(TAG, "Send failed", e)
                appendLog("❌ Send failed: ${e.message}")
            }
        }
    }

    /**
     * 使用 LocalFileServiceWorkload + ActrRef.call 模式发送文件
     *
     * 此方法：
     * 1. 创建一个包含用户输入内容的 MyLocalFileService
     * 2. 使用 LocalFileServiceWorkload 包装它
     * 3. 通过 ActrRef.call 触发 SendFile RPC
     * 4. MyLocalFileService.send_file 内部会发现 FileTransferService 并执行完整传输
     */
    private fun sendDataStream() {
        val message = messageInput?.text?.toString()?.trim() ?: ""
        if (message.isEmpty()) {
            appendLog("⚠️ Please enter a message for stream")
            return
        }

        if (clientSystem == null) {
            appendLog("⚠️ Not connected")
            return
        }

        appendLog("🚀 Sending file with content: \"${message.take(50)}...\"")
        messageInput?.setText("")

        coroutineScope.launch {
            try {
                // Shutdown previous file transfer workload if exists
                fileTransferRef?.let {
                    try {
                        it.shutdown()
                        it.awaitShutdown()
                    } catch (e: Exception) {
                        Log.w(TAG, "Previous file transfer shutdown failed: ${e.message}")
                    }
                }

                // Create new LocalFileServiceWorkload with custom content
                val handler = MyLocalFileService(customContent = message)
                fileTransferWorkload = LocalFileServiceWorkload(handler)
                val node = clientSystem!!.attach(fileTransferWorkload!!)
                fileTransferRef = node.start()
                appendLog(
                        "📦 LocalFileService started: ${fileTransferRef?.actorId()?.serialNumber}"
                )

                // Wait for registration
                delay(1000)

                // Call SendFile RPC on self to trigger the file transfer
                appendLog("📞 Calling SendFile via ActrRef.call()...")
                val request = SendFileRequest.newBuilder().setFilename("user-input.txt").build()
                val selfId = fileTransferRef!!.actorId()

                val responsePayload =
                        fileTransferRef!!.call(
                                selfId,
                                "local_file.LocalFileService.SendFile",
                                PayloadType.RPC_RELIABLE,
                                request.toByteArray(),
                                60000L
                        )

                val response = SendFileResponse.parseFrom(responsePayload)
                if (response.success) {
                    appendLog("✅ File transfer completed successfully!")
                } else {
                    appendLog("❌ File transfer failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "File transfer failed", e)
                appendLog("❌ File transfer failed: ${e.message}")
            }
        }
    }

    private fun updateUI(isConnecting: Boolean = false, isConnected: Boolean = false) {
        activity?.runOnUiThread {
            connectButton?.isEnabled = !isConnecting && !isConnected
            disconnectButton?.isEnabled = isConnected
            messageInput?.isEnabled = isConnected
            sendButton?.isEnabled = isConnected
            streamButton?.isEnabled = isConnected
        }
    }

    private fun updateStatus(status: String) {
        activity?.runOnUiThread { clientStatusText?.text = "Status: $status" }
    }

    fun appendLog(message: String) {
        val timestamp =
                java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                        .format(java.util.Date())
        val logMessage = "[$timestamp] $message\n"

        Log.d(TAG, message)

        if (logText != null) {
            activity?.runOnUiThread {
                logText?.append(logMessage)
                scrollToBottom()
            }
        } else {
            logBuffer.append(logMessage)
        }
    }

    private fun scrollToBottom() {
        scrollView?.post { scrollView?.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun copyAssetToInternalStorage(assetName: String): String {
        val context = requireContext()
        val inputStream = context.assets.open(assetName)
        val outputFile = File(context.filesDir, assetName)
        outputFile.parentFile?.mkdirs()

        inputStream.use { input ->
            outputFile.outputStream().use { output -> input.copyTo(output) }
        }

        return outputFile.absolutePath
    }

    private fun encodeEchoRequest(message: String): ByteArray {
        val messageBytes = message.toByteArray(Charsets.UTF_8)
        val result = ByteArray(2 + messageBytes.size)
        result[0] = 0x0a.toByte()
        result[1] = messageBytes.size.toByte()
        System.arraycopy(messageBytes, 0, result, 2, messageBytes.size)
        return result
    }

    private fun decodeEchoResponse(payload: ByteArray): String {
        if (payload.isEmpty()) return ""
        var offset = 0
        while (offset < payload.size) {
            val tag = payload[offset].toInt() and 0xFF
            offset++
            val fieldNumber = tag shr 3
            val wireType = tag and 0x07
            if (fieldNumber == 1 && wireType == 2) {
                val length = payload[offset].toInt() and 0xFF
                offset++
                return String(payload, offset, length, Charsets.UTF_8)
            }
            break
        }
        return ""
    }
}
