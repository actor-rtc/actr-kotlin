/**
 * LocalFileService 用户业务逻辑实现
 *
 * 这个文件实现了 LocalFileService.SendFile RPC 方法， 模拟了文件传输流程：
 * 1. 发现 FileTransferService
 * 2. 发送 StartTransfer RPC
 * 3. 发送 DataStream chunks
 * 4. 发送 EndTransfer RPC
 *
 * 使用 protobuf gradle 插件生成的消息类。
 */
package com.example

// 使用 protobuf 生成的消息类
import android.util.Log
import com.example.generated.LocalFileServiceHandler
import file_transfer.FileTransfer.EndTransferRequest
import file_transfer.FileTransfer.EndTransferResponse
import file_transfer.FileTransfer.StartTransferRequest
import file_transfer.FileTransfer.StartTransferResponse
import io.actor_rtc.actr.ActrId
import io.actor_rtc.actr.ActrType
import io.actor_rtc.actr.ContextBridge
import io.actor_rtc.actr.DataStream
import io.actor_rtc.actr.PayloadType
import kotlinx.coroutines.delay
import local_file.File.SendFileRequest
import local_file.File.SendFileResponse

/**
 * LocalFileService 服务的具体实现
 *
 * 此实现负责处理文件发送请求，通过以下步骤：
 * 1. 发现 FileTransferService 远程服务
 * 2. 发送控制消息（StartTransfer/EndTransfer）
 * 3. 发送数据流（DataStream chunks）
 */
class MyLocalFileService(private val customContent: String? = null) : LocalFileServiceHandler {

    companion object {
        private const val TAG = "MyLocalFileService"
        private const val CHUNK_SIZE = 1024
    }

    // 缓存的 FileTransferService ID
    private var receiverId: ActrId? = null

    /** 获取或发现 FileTransferService */
    private suspend fun getReceiverId(ctx: ContextBridge): ActrId {
        receiverId?.let {
            return it
        }

        val targetType = ActrType(manufacturer = "acme", name = "FileTransferService")
        Log.i(
                TAG,
                "🌐 Discovering receiver via signaling for type: ${targetType.manufacturer}/${targetType.name}"
        )

        val discovered = ctx.discover(targetType)
        Log.i(TAG, "🎯 Discovered receiver: ${discovered.serialNumber}")

        receiverId = discovered
        return discovered
    }

    /**
     * 实现 SendFile RPC 方法
     *
     * 处理文件发送请求，包括：
     * 1. Phase 1: StartTransfer RPC（控制平面）
     * 2. Phase 2: 发送 DataStream chunks（数据平面）
     * 3. Phase 3: EndTransfer RPC（控制平面）
     */
    override suspend fun send_file(request: SendFileRequest, ctx: ContextBridge): SendFileResponse {
        val filename = request.filename
        Log.i(TAG, "📤 Starting file transfer:")
        Log.i(TAG, "   Filename: $filename")

        try {
            val receiverId = getReceiverId(ctx)
            val (content, chunks) = createContent()

            // Phase 1: StartTransfer RPC (Control Plane)
            Log.i(TAG, "📡 Phase 1: Sending StartTransfer RPC...")
            val startReq =
                    StartTransferRequest.newBuilder()
                            .setStreamId("test-stream-001")
                            .setFilename(filename)
                            .setTotalSize(content.length.toLong())
                            .setChunkCount(chunks.size)
                            .build()

            val startRespPayload =
                    ctx.callRaw(
                            receiverId,
                            "file_transfer.FileTransferService.StartTransfer",
                            PayloadType.RPC_RELIABLE,
                            startReq.toByteArray(),
                            30000L
                    )
            val startResp = StartTransferResponse.parseFrom(startRespPayload)

            if (!startResp.ready) {
                Log.e(TAG, "❌ Server not ready: ${startResp.message}")
                return SendFileResponse.newBuilder().setSuccess(false).build()
            }

            Log.i(TAG, "✅ StartTransfer RPC succeeded: ${startResp.message}")

            // Phase 2: Send DataStream chunks (Data Plane - Fast Path)
            Log.i(TAG, "📦 Phase 2: Sending ${chunks.size} DataStream chunks...")

            for ((index, chunk) in chunks.withIndex()) {
                val dataStream =
                        DataStream(
                                streamId = "test-stream-001",
                                sequence = index.toULong(),
                                payload = chunk,
                                metadata = emptyList(),
                                timestampMs = System.currentTimeMillis()
                        )

                ctx.sendDataStreamRaw(receiverId, dataStream)

                val progress = ((index + 1).toFloat() / chunks.size * 100).toInt()
                Log.i(
                        TAG,
                        "   Sent chunk #${index + 1}/${chunks.size}: ${chunk.size} bytes ($progress%)"
                )

                // Small delay to avoid overwhelming the receiver
                delay(10)
            }

            Log.i(TAG, "✅ All chunks sent successfully")

            // Phase 3: EndTransfer RPC (Control Plane)
            Log.i(TAG, "🏁 Phase 3: Sending EndTransfer RPC...")
            val endReq =
                    EndTransferRequest.newBuilder()
                            .setStreamId("test-stream-001")
                            .setSuccess(true)
                            .build()

            val endRespPayload =
                    ctx.callRaw(
                            receiverId,
                            "file_transfer.FileTransferService.EndTransfer",
                            PayloadType.RPC_RELIABLE,
                            endReq.toByteArray(),
                            30000L
                    )
            val endResp = EndTransferResponse.parseFrom(endRespPayload)

            Log.i(TAG, "✅ EndTransfer RPC succeeded!")
            Log.i(TAG, "📊 Transfer Statistics:")
            Log.i(TAG, "   Acknowledged: ${endResp.acknowledged}")
            Log.i(TAG, "   Chunks received: ${endResp.chunksReceived}")
            Log.i(TAG, "   Bytes received: ${endResp.bytesReceived}")
            Log.i(TAG, "🎉 File transfer completed successfully!")

            return SendFileResponse.newBuilder().setSuccess(true).build()
        } catch (e: Exception) {
            Log.e(TAG, "❌ File transfer failed", e)
            return SendFileResponse.newBuilder().setSuccess(false).build()
        }
    }

    /** 创建测试内容和分块 */
    private fun createContent(): Pair<String, List<ByteArray>> {
        val content =
                customContent
                        ?: "Hello DataStream from Android! This is a test file content. ".repeat(
                                100
                        )
        val chunks =
                content.toByteArray(Charsets.UTF_8).asIterable().chunked(CHUNK_SIZE).map {
                    it.toByteArray()
                }

        Log.i(TAG, "   Total size: ${content.length} bytes")
        Log.i(TAG, "   Chunk size: $CHUNK_SIZE bytes")
        Log.i(TAG, "   Chunk count: ${chunks.size}")

        return Pair(content, chunks)
    }
}
