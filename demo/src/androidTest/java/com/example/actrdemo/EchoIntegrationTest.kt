package com.example.actrdemo

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.actor_rtc.actr.ActrId
import io.actor_rtc.actr.ActrType
import io.actor_rtc.actr.ContextBridge
import io.actor_rtc.actr.PayloadType
import io.actor_rtc.actr.Realm
import io.actor_rtc.actr.RpcEnvelopeBridge
import io.actor_rtc.actr.WorkloadBridge
import io.actor_rtc.actr.dsl.*
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EchoIntegrationTest {

    companion object {
        private const val TAG = "EchoIntegrationTest"
        private const val REALM_ID = 2281844430u
        private const val REGISTRATION_DELAY_MS = 3000L
        private const val DISCOVERY_TIMEOUT_MS = 30000L
        private const val CALL_TIMEOUT_MS = 60000L
    }

    private fun getContext(): Context {
        return InstrumentationRegistry.getInstrumentation().targetContext
    }

    private fun copyAssetToInternalStorage(assetName: String): String {
        val context = getContext()
        val inputStream = context.assets.open(assetName)
        val outputFile = File(context.filesDir, assetName)
        outputFile.parentFile?.mkdirs()
        inputStream.use { input ->
            outputFile.outputStream().use { output -> input.copyTo(output) }
        }
        return outputFile.absolutePath
    }

    // ==================== Protobuf Encoding/Decoding Helpers ====================

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

    // ==================== RPC Test Workload ====================

    /**
     * Workload that handles RPC requests via dispatch method.
     *
     * This demonstrates the dispatch pattern similar to shell-actr-echo/client:
     * 1. Pre-discover the EchoService in onStart
     * 2. Handle incoming RPC requests in dispatch by forwarding to the server
     * 3. Return the response back to the caller
     */
    private inner class RpcTestWorkload : WorkloadBridge {

        private val selfId =
                ActrId(
                        realm = Realm(realmId = REALM_ID),
                        serialNumber = System.currentTimeMillis().toULong(),
                        type = ActrType(manufacturer = "acme", name = "echo-client-app")
                )

        // Server ID discovered in onStart, used in dispatch
        private var echoServerId: ActrId? = null

        override suspend fun onStart(ctx: ContextBridge) {
            Log.i(TAG, "RpcTestWorkload.onStart: Starting...")

            // Pre-discover the EchoService so dispatch can use it
            Log.i(TAG, "📡 Discovering EchoService...")
            val targetType = ActrType(manufacturer = "acme", name = "EchoService")
            echoServerId = ctx.discover(targetType)
            Log.i(TAG, "✅ Found EchoService: ${echoServerId?.serialNumber}")
        }

        override suspend fun onStop(ctx: ContextBridge) {
            Log.i(TAG, "RpcTestWorkload.onStop")
        }

        /**
         * Handle incoming RPC requests from the Shell (test code).
         *
         * This method:
         * 1. Receives requests from the test via ActrRef.call()
         * 2. Forwards the request to the remote EchoService
         * 3. Returns the response back to the test
         */
        override suspend fun dispatch(ctx: ContextBridge, envelope: RpcEnvelopeBridge): ByteArray {
            Log.i(TAG, "🔀 RpcTestWorkload.dispatch() called!")
            Log.i(TAG, "   route_key: ${envelope.routeKey}")
            Log.i(TAG, "   request_id: ${envelope.requestId}")
            Log.i(TAG, "   payload size: ${envelope.payload.size} bytes")

            val serverId =
                    echoServerId ?: throw IllegalStateException("EchoService not discovered yet")

            // Forward to remote EchoService using ctx.callRaw
            Log.i(TAG, "📤 Forwarding to EchoService...")
            val response =
                    ctx.callRaw(
                            serverId,
                            envelope.routeKey,
                            PayloadType.RPC_RELIABLE,
                            envelope.payload,
                            30000L
                    )

            Log.i(TAG, "✅ Got response from server, size: ${response.size} bytes")
            return response
        }
    }

    @Test
    fun testRpcCallToEchoServer(): Unit = runBlocking {
        Log.i(TAG, "=== Starting RPC Call Test (using dispatch pattern) ===")
        val clientConfigPath = copyAssetToInternalStorage("client-config.toml")
        var clientRef: ActrRef? = null

        try {
            val clientSystem = createActrSystem(clientConfigPath)
            val testMessage = "Hello from Android!"
            val expectedResponse = "Echo: $testMessage"

            val clientWorkload = RpcTestWorkload()
            val clientNode = clientSystem.attach(clientWorkload)
            clientRef = clientNode.start()
            Log.i(TAG, "Client started: ${clientRef.actorId().serialNumber}")

            // Wait for onStart to complete (which discovers the server)
            delay(2000)

            // Send RPC via ActrRef.call() - this triggers the dispatch() method
            Log.i(TAG, "📞 Sending RPC via ActrRef.call()...")
            val requestPayload = encodeEchoRequest(testMessage)

            val responsePayload =
                    clientRef.call(
                            "echo.EchoService.Echo",
                            PayloadType.RPC_RELIABLE,
                            requestPayload,
                            30000L
                    )

            val response = decodeEchoResponse(responsePayload)
            Log.i(TAG, "📬 Response: $response")

            assertEquals("Echo mismatch", expectedResponse, response)
            Log.i(TAG, "=== RPC Call Test PASSED ===")
        } finally {
            try {
                clientRef?.shutdown()
                clientRef?.awaitShutdown()
            } catch (e: Exception) {}
        }
    }

    // ==================== Protobuf Encoding Helpers for FileTransfer ====================

    /**
     * Encode StartTransferRequest: field 1 (stream_id): string field 2 (filename): string field 3
     * (total_size): uint64 field 4 (chunk_count): uint32
     */
    private fun encodeStartTransferRequest(
            streamId: String,
            filename: String,
            totalSize: Long,
            chunkCount: Int
    ): ByteArray {
        val output = java.io.ByteArrayOutputStream()

        // Field 1: stream_id (tag = 0x0a = field 1, wire type 2)
        val streamIdBytes = streamId.toByteArray(Charsets.UTF_8)
        output.write(0x0a)
        output.write(streamIdBytes.size)
        output.write(streamIdBytes)

        // Field 2: filename (tag = 0x12 = field 2, wire type 2)
        val filenameBytes = filename.toByteArray(Charsets.UTF_8)
        output.write(0x12)
        output.write(filenameBytes.size)
        output.write(filenameBytes)

        // Field 3: total_size (tag = 0x18 = field 3, wire type 0)
        output.write(0x18)
        writeVarint(output, totalSize.toULong())

        // Field 4: chunk_count (tag = 0x20 = field 4, wire type 0)
        output.write(0x20)
        writeVarint(output, chunkCount.toULong())

        return output.toByteArray()
    }

    /** Decode StartTransferResponse: field 1 (ready): bool field 2 (message): string */
    private fun decodeStartTransferResponse(payload: ByteArray): Pair<Boolean, String> {
        var ready = false
        var message = ""
        var offset = 0

        while (offset < payload.size) {
            val tag = payload[offset].toInt() and 0xFF
            offset++
            val fieldNumber = tag shr 3
            val wireType = tag and 0x07

            when (fieldNumber) {
                1 -> {
                    // ready: bool (wire type 0 = varint)
                    if (wireType == 0) {
                        ready = payload[offset].toInt() != 0
                        offset++
                    }
                }
                2 -> {
                    // message: string (wire type 2 = length-delimited)
                    if (wireType == 2) {
                        val length = payload[offset].toInt() and 0xFF
                        offset++
                        message = String(payload, offset, length, Charsets.UTF_8)
                        offset += length
                    }
                }
                else -> {
                    // Skip unknown field
                    when (wireType) {
                        0 -> offset++ // varint
                        2 -> {
                            val len = payload[offset].toInt() and 0xFF
                            offset += 1 + len
                        }
                    }
                }
            }
        }
        return Pair(ready, message)
    }

    /** Encode EndTransferRequest: field 1 (stream_id): string field 2 (success): bool */
    private fun encodeEndTransferRequest(streamId: String, success: Boolean): ByteArray {
        val output = java.io.ByteArrayOutputStream()

        // Field 1: stream_id
        val streamIdBytes = streamId.toByteArray(Charsets.UTF_8)
        output.write(0x0a)
        output.write(streamIdBytes.size)
        output.write(streamIdBytes)

        // Field 2: success (tag = 0x10 = field 2, wire type 0)
        output.write(0x10)
        output.write(if (success) 1 else 0)

        return output.toByteArray()
    }

    /**
     * Decode EndTransferResponse: field 1 (acknowledged): bool field 2 (bytes_received): uint64
     * field 3 (chunks_received): uint32
     */
    private fun decodeEndTransferResponse(payload: ByteArray): Triple<Boolean, Long, Int> {
        var acknowledged = false
        var bytesReceived = 0L
        var chunksReceived = 0
        var offset = 0

        while (offset < payload.size) {
            val tag = payload[offset].toInt() and 0xFF
            offset++
            val fieldNumber = tag shr 3
            val wireType = tag and 0x07

            when (fieldNumber) {
                1 -> {
                    if (wireType == 0) {
                        acknowledged = payload[offset].toInt() != 0
                        offset++
                    }
                }
                2 -> {
                    if (wireType == 0) {
                        val (value, newOffset) = readVarint(payload, offset)
                        bytesReceived = value.toLong()
                        offset = newOffset
                    }
                }
                3 -> {
                    if (wireType == 0) {
                        val (value, newOffset) = readVarint(payload, offset)
                        chunksReceived = value.toInt()
                        offset = newOffset
                    }
                }
                else -> {
                    when (wireType) {
                        0 -> {
                            val (_, newOffset) = readVarint(payload, offset)
                            offset = newOffset
                        }
                        2 -> {
                            val len = payload[offset].toInt() and 0xFF
                            offset += 1 + len
                        }
                    }
                }
            }
        }
        return Triple(acknowledged, bytesReceived, chunksReceived)
    }

    private fun writeVarint(output: java.io.ByteArrayOutputStream, value: ULong) {
        var v = value
        while (v >= 0x80u) {
            output.write(((v and 0x7Fu) or 0x80u).toInt())
            v = v shr 7
        }
        output.write(v.toInt())
    }

    private fun readVarint(data: ByteArray, startOffset: Int): Pair<ULong, Int> {
        var result = 0uL
        var shift = 0
        var offset = startOffset
        while (offset < data.size) {
            val b = data[offset].toInt() and 0xFF
            offset++
            result = result or ((b and 0x7F).toULong() shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        return Pair(result, offset)
    }

    /**
     * 测试文件传输到 FileTransferService Receiver
     *
     * 此测试使用 LocalFileServiceWorkload + ActrRef.call 模式：
     * - LocalFileServiceWorkload: 使用生成的 Dispatcher 进行消息路由
     * - MyLocalFileService: Handler 实现，包含完整的文件传输逻辑
     * - ActrRef.call: 调用 SendFile 方法触发传输流程
     */
    @Test
    fun testDataStreamToFileTransferReceiver(): Unit = runBlocking {
        Log.i(TAG, "=== Starting DataStream Test (using LocalFileServiceWorkload) ===")
        val clientConfigPath = copyAssetToInternalStorage("client-config.toml")
        var clientRef: ActrRef? = null

        try {
            val clientSystem = createActrSystem(clientConfigPath)

            // 使用生成的代码创建 workload
            val handler = com.example.MyLocalFileService()
            val clientWorkload = com.example.LocalFileServiceWorkload(handler)

            val clientNode = clientSystem.attach(clientWorkload)
            clientRef = clientNode.start()
            Log.i(TAG, "Client started: ${clientRef.actorId().serialNumber}")

            // 等待服务注册
            delay(2000)

            // 使用 ActrRef.call() 调用 SendFile 方法
            Log.i(TAG, "📞 Calling SendFile via ActrRef.call()...")
            val request =
                    local_file.File.SendFileRequest.newBuilder()
                            .setFilename("android-test.txt")
                            .build()

            val responsePayload =
                    clientRef.call(
                            "local_file.LocalFileService.SendFile",
                            PayloadType.RPC_RELIABLE,
                            request.toByteArray(),
                            60000L
                    )

            val response = local_file.File.SendFileResponse.parseFrom(responsePayload)
            Log.i(TAG, "📬 Response: success=${response.success}")

            assertTrue("File transfer should succeed", response.success)
            Log.i(TAG, "=== DataStream Test PASSED ===")
        } finally {
            try {
                clientRef?.shutdown()
                clientRef?.awaitShutdown()
            } catch (e: Exception) {}
        }
    }
}
