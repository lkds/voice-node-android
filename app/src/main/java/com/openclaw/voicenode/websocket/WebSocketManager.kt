package com.openclaw.voicenode.websocket

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * WebSocket 连接管理器 - 实现 OpenClaw Gateway Node 协议
 */
class WebSocketManager private constructor() {

    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
    
    private var websocket: WebSocket? = null
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // 连接状态
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    // 设备认证信息
    private var deviceId: String = UUID.randomUUID().toString()
    private var deviceToken: String? = null
    
    companion object {
        val instance = WebSocketManager()
    }

    /**
     * 连接 Gateway
     */
    suspend fun connect(url: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            _connectionState.value = ConnectionState.CONNECTING
            
            val request = Request.Builder()
                .url(url)
                .build()
            
            websocket = client.newWebSocket(request, WebSocketListener())
            
            // 等待连接建立
            delay(1000)
            
            // 发送 connect 消息（等待 challenge）
            // Gateway 会先发送 challenge，然后我们签名响应
            
            Result.success(Unit)
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.DISCONNECTED
            Result.failure(e)
        }
    }

    /**
     * 发送 Node 命令响应
     */
    fun sendResponse(id: String, result: Any) {
        val response = mapOf(
            "type" to "res",
            "id" to id,
            "ok" to true,
            "payload" to result
        )
        sendJson(response)
    }

    /**
     * 发送 JSON 消息
     */
    private fun sendJson(data: Map<String, Any>) {
        websocket?.send(data.toString())
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        websocket?.close(1000, "User disconnect")
        websocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * WebSocket 监听器
     */
    private inner class WebSocketListener : okhttp3.WebSocketListener() {
        
        override fun onOpen(webSocket: WebSocket, response: Response) {
            _connectionState.value = ConnectionState.CONNECTED
            scope.launch {
                // 发送 connect 请求
                sendConnectRequest()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            scope.launch {
                handleIncomingMessage(text)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            _connectionState.value = ConnectionState.DISCONNECTED
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            _connectionState.value = ConnectionState.DISCONNECTED
            // 自动重连逻辑
            scope.launch {
                retryConnect()
            }
        }
    }

    /**
     * 发送 Node connect 请求
     */
    private fun sendConnectRequest() {
        val nonce = UUID.randomUUID().toString() // 实际应从 Gateway challenge 获取
        
        val connectParams = mapOf(
            "minProtocol" to 3,
            "maxProtocol" to 3,
            "client" to mapOf(
                "id" to "voice-node-android",
                "version" to "1.0.0",
                "platform" to "android",
                "mode" to "node"
            ),
            "role" to "node",
            "scopes" to emptyList<String>(),
            "caps" to listOf("voice"),
            "commands" to listOf("voice.listen", "voice.speak"),
            "permissions" to mapOf("microphone" to true),
            "auth" to mapOf("token" to (deviceToken ?: "")),
            "device" to mapOf(
                "id" to deviceId,
                "nonce" to nonce
                // TODO: 添加签名
            )
        )
        
        val message = mapOf(
            "type" to "req",
            "id" to UUID.randomUUID().toString(),
            "method" to "connect",
            "params" to connectParams
        )
        
        sendJson(message)
    }

    /**
     * 处理 incoming 消息
     */
    private fun handleIncomingMessage(text: String) {
        // 解析 JSON 并处理
        // 这里需要根据消息类型路由到不同的处理器
        
        // 消息类型包括：
        // - event: Gateway 发送的事件（如 connect.challenge）
        // - req: Gateway 调用 Node 命令（如 node.invoke voice.listen）
        // - res: Gateway 响应我们的请求
        
        // TODO: 实现 JSON 解析和消息路由
    }

    /**
     * 重连逻辑（指数退避）
     */
    private suspend fun retryConnect() {
        val maxRetries = 5
        var attempt = 0
        
        while (attempt < maxRetries) {
            attempt++
            val delay = minOf(1000L * (1.5 * attempt).toLong(), 30000L)
            
            kotlinx.coroutines.delay(delay)
            
            // 尝试重连
            // TODO: 实现重连
        }
    }

    /**
     * 连接状态枚举
     */
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        AUTHENTICATED,
        RETRYING
    }
}