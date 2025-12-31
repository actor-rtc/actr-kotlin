/**
 * EchoService Client Workload Implementation
 *
 * This client-side Workload forwards RPC requests to a remote EchoService.
 */
package com.example.actrdemo

import android.util.Log
import com.example.actrdemo.generated.EchoServiceDispatcher
import com.example.actrdemo.generated.EchoServiceHandler
import echo.Echo.*
import io.actor_rtc.actr.ActrId
import io.actor_rtc.actr.ActrType
import io.actor_rtc.actr.ContextBridge
import io.actor_rtc.actr.PayloadType
import io.actor_rtc.actr.RpcEnvelopeBridge
import io.actor_rtc.actr.WorkloadBridge

/** Client-side handler that forwards requests to the remote EchoService. */
class EchoClientHandler(private val ctx: ContextBridge, private val serverId: ActrId) :
        EchoServiceHandler {

    companion object {
        private const val TAG = "EchoClientHandler"
    }

    override suspend fun echo(request: EchoRequest, ctx: ContextBridge): EchoResponse {
        Log.i(TAG, "📤 Forwarding echo request to server: ${request.message}")

        // Forward to remote EchoService
        val response =
                ctx.callRaw(
                        serverId,
                        "echo.EchoService.Echo",
                        PayloadType.RPC_RELIABLE,
                        request.toByteArray(),
                        30000L
                )

        val echoResponse = EchoResponse.parseFrom(response)
        Log.i(TAG, "📥 Received response from server: ${echoResponse.reply}")
        return echoResponse
    }
}

/**
 * Client Workload for EchoService
 *
 * This Workload:
 * 1. Discovers the remote EchoService in onStart
 * 2. Uses EchoServiceDispatcher to route requests to handler
 * 3. Handler forwards requests to the remote server
 */
class EchoClientWorkload(private val realmId: UInt = 2281844430u) : WorkloadBridge {

    companion object {
        private const val TAG = "EchoClientWorkload"
    }

    // Server ID discovered in onStart
    private var serverId: ActrId? = null
    private var handler: EchoClientHandler? = null

    override suspend fun onStart(ctx: ContextBridge) {
        Log.i(TAG, "EchoClientWorkload.onStart: Starting...")

        // Pre-discover the EchoService
        Log.i(TAG, "📡 Discovering EchoService...")
        val targetType = ActrType(manufacturer = "acme", name = "EchoService")
        serverId = ctx.discover(targetType)
        Log.i(TAG, "✅ Found EchoService: ${serverId?.serialNumber}")

        // Create handler with discovered server ID
        handler = EchoClientHandler(ctx, serverId!!)
    }

    override suspend fun onStop(ctx: ContextBridge) {
        Log.i(TAG, "EchoClientWorkload.onStop")
        // Cleanup resources
    }

    /** Dispatch RPC requests using the generated Dispatcher */
    override suspend fun dispatch(ctx: ContextBridge, envelope: RpcEnvelopeBridge): ByteArray {
        Log.i(TAG, "🔀 EchoClientWorkload.dispatch() called!")
        Log.i(TAG, "   route_key: ${envelope.routeKey}")
        Log.i(TAG, "   request_id: ${envelope.requestId}")
        Log.i(TAG, "   payload size: ${envelope.payload.size} bytes")

        val currentHandler =
                handler
                        ?: throw IllegalStateException(
                                "Handler not initialized - EchoService not discovered yet"
                        )

        // Use generated Dispatcher to route to handler
        return EchoServiceDispatcher.dispatch(currentHandler, ctx, envelope)
    }
}
