/**
 * LocalFileService Workload 实现
 *
 * 此 Workload 使用生成的 Dispatcher 进行消息路由， 将业务逻辑委托给 LocalFileServiceHandler 实现。
 */
package com.example

import android.util.Log
import com.example.generated.LocalFileServiceDispatcher
import com.example.generated.LocalFileServiceHandler
import io.actor_rtc.actr.ActrId
import io.actor_rtc.actr.ActrType
import io.actor_rtc.actr.ContextBridge
import io.actor_rtc.actr.Realm
import io.actor_rtc.actr.RpcEnvelopeBridge
import io.actor_rtc.actr.WorkloadBridge

/**
 * LocalFileService 的 Workload 实现
 *
 * 使用方式：
 * ```kotlin
 * val handler = MyLocalFileService()
 * val workload = LocalFileServiceWorkload(handler)
 * val system = createActrSystem(configPath)
 * val node = system.attach(workload)
 * val actrRef = node.start()
 *
 * // 调用 SendFile 方法
 * val response = actrRef.call(
 *     actrRef.actorId(),
 *     "local_file.LocalFileService.SendFile",
 *     PayloadType.RPC_RELIABLE,
 *     SendFileRequest(filename = "test.txt").encode(),
 *     30000L
 * )
 * ```
 */
class LocalFileServiceWorkload(
        private val handler: LocalFileServiceHandler,
        private val realmId: UInt = 2281844430u
) : WorkloadBridge {

    companion object {
        private const val TAG = "LocalFileServiceWorkload"
    }

    private val selfId =
            ActrId(
                    realm = Realm(realmId = realmId),
                    serialNumber = System.currentTimeMillis().toULong(),
                    type = ActrType(manufacturer = "acme", name = "LocalFileService")
            )

    override suspend fun serverId(): ActrId = selfId

    override suspend fun onStart(ctx: ContextBridge) {
        Log.i(TAG, "LocalFileServiceWorkload.onStart")
        // 可以在这里预热缓存，发现远程服务等
    }

    override suspend fun onStop(ctx: ContextBridge) {
        Log.i(TAG, "LocalFileServiceWorkload.onStop")
    }

    /**
     * 分发 RPC 请求
     *
     * 使用生成的 Dispatcher 将请求路由到对应的 Handler 方法
     */
    override suspend fun dispatch(ctx: ContextBridge, envelope: RpcEnvelopeBridge): ByteArray {
        Log.i(TAG, "🔀 dispatch() called")
        Log.i(TAG, "   route_key: ${envelope.routeKey}")
        Log.i(TAG, "   request_id: ${envelope.requestId}")
        Log.i(TAG, "   payload size: ${envelope.payload.size} bytes")

        return LocalFileServiceDispatcher.dispatch(handler, ctx, envelope)
    }
}
