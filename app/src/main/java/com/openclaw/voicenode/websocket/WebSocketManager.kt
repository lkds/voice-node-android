package com.openclaw.voicenode.websocket

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * WebSocket 连接管理器 - 实现完整的 OpenClaw Gateway Node 协议
 * 
 * 协议规范:
 * - 消息格式: JSON
 * - 连接流程: connect (challenge) -> auth -> ready
 * - 心跳: ping/pong 每 30 秒
 * - 命令: node.invoke (Gateway -> Node) -> res (Node -> Gateway)
 */
class WebSocketManager private constructor() {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private var websocket: WebSocket? = null
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val messageIdCounter = AtomicLong(0)
    
    // 连接状态
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    // 日志输出
    private val _logs = MutableSharedFlow<String>(replay = 100)
    val logs: SharedFlow<String> = _logs.asSharedFlow()
    
    // 设备认证信息
    private var deviceId: String = UUID.randomUUID().toString()
    private var deviceToken: String? = null
    private var gatewayUrl: String = ""
    private var pendingChallenge: String? = null
    
    // 消息处理器
    private var messageHandler: MessageHandler? = null
    
    // 心跳
    private var heartbeatJob: Job? = null
    
    // 重连
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 10
    private var reconnectJob: Job? = null

    companion object {
        val instance = WebSocketManager()
        private const val TAG = "WebSocketManager"
    }
    
    /**
     * 设置消息处理器
     */
    fun setMessageHandler(handler: MessageHandler) {
        this.messageHandler = handler
    }
    
    /**
     * 设置设备 Token（用于认证）
     */
    fun setDeviceToken(token: String?) {
        this.deviceToken = token
    }
    
    /**
     * 设置设备 ID
     */
    fun setDeviceId(id: String) {
        this.deviceId = id
    }

    /**
     * 连接 Gateway
     */
    suspend fun connect(url: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (_connectionState.value == ConnectionState.CONNECTED || 
                _connectionState.value == ConnectionState.AUTHENTICATED) {
                disconnect()
            }
            
            gatewayUrl = url
            _connectionState.value = ConnectionState.CONNECTING
            log("连接中: $url")
            
            val request = Request.Builder()
                .url(url)
                .build()
            
            websocket = client.newWebSocket(request, WebSocketListenerImpl())
            
            // 等待连接结果
            val result = waitForConnection(10000)
            
            if (result) {
                reconnectAttempts = 0
                Result.success(Unit)
            } else {
                _connectionState.value = ConnectionState.DISCONNECTED
                Result.failure(Exception("Connection timeout"))
            }
        } catch (e: Exception) {
            log("连接失败: ${e.message}")
            _connectionState.value = ConnectionState.DISCONNECTED
            Result.failure(e)
        }
    }
    
    /**
     * 等待连接建立
     */
    private suspend fun waitForConnection(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) {
        connectionState.first { it == ConnectionState.CONNECTED || it == ConnectionState.AUTHENTICATED || it == ConnectionState.DISCONNECTED }
        _connectionState.value == ConnectionState.CONNECTED || _connectionState.value == ConnectionState.AUTHENTICATED
    } ?: false

    /**
     * 发送请求消息
     */
    fun sendRequest(method: String, params: Map<String, Any> = emptyMap()): String {
        val id = generateMessageId()
        val message = NodeMessage.Request(
            id = id,
            method = method,
            params = params.toJsonObject()
        )
        sendMessage(message)
        return id
    }
    
    /**
     * 发送响应消息
     */
    fun sendResponse(id: String, result: Any?, error: String? = null) {
        val message = if (error != null) {
            NodeMessage.Response(id = id, ok = false, error = error)
        } else {
            NodeMessage.Response(id = id, ok = true, payload = result)
        }
        sendMessage(message)
    }
    
    /**
     * 发送事件消息
     */
    fun sendEvent(event: String, data: Any? = null) {
        val message = NodeMessage.Event(event = event, payload = data)
        sendMessage(message)
    }

    /**
     * 发送消息
     */
    private fun sendMessage(message: NodeMessage) {
        try {
            val jsonString = json.encodeToString(message)
            websocket?.send(jsonString)
            log("发送: $jsonString")
        } catch (e: Exception) {
            log("发送失败: ${e.message}")
        }
    }

    /**
     * 断开连接
     */
    fun disconnect() {
        heartbeatJob?.cancel()
        reconnectJob?.cancel()
        websocket?.close(1000, "User disconnect")
        websocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        log("已断开连接")
    }
    
    /**
     * 生成消息 ID
     */
    private fun generateMessageId(): String {
        return "msg-${System.currentTimeMillis()}-${messageIdCounter.incrementAndGet()}"
    }
    
    /**
     * 日志输出
     */
    private fun log(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logLine = "[$timestamp] $message"
        scope.launch {
            _logs.emit(logLine)
        }
    }

    /**
     * WebSocket 监听器实现
     */
    private inner class WebSocketListenerImpl : okhttp3.WebSocketListener() {
        
        override fun onOpen(webSocket: WebSocket, response: Response) {
            log("WebSocket 已打开")
            _connectionState.value = ConnectionState.CONNECTED
            
            // 发送 connect 请求
            sendConnectRequest()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            log("收到: $text")
            scope.launch {
                handleIncomingMessage(text)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            log("服务器关闭连接: $code - $reason")
            webSocket.close(1000, null)
            _connectionState.value = ConnectionState.DISCONNECTED
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            log("连接已关闭: $code - $reason")
            _connectionState.value = ConnectionState.DISCONNECTED
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            log("连接失败: ${t.message}")
            _connectionState.value = ConnectionState.DISCONNECTED
            
            // 自动重连
            scheduleReconnect()
        }
    }

    /**
     * 发送 Node connect 请求
     */
    private fun sendConnectRequest() {
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
            "caps" to listOf("voice.stt", "voice.tts"),
            "commands" to listOf("voice.listen", "voice.speak", "voice.stop"),
            "permissions" to mapOf("microphone" to true),
            "auth" to mapOf(
                "token" to (deviceToken ?: "")
            ),
            "device" to mapOf(
                "id" to deviceId,
                "type" to "android",
                "name" to android.os.Build.MODEL,
                "nonce" to UUID.randomUUID().toString()
            )
        )
        
        sendRequest("connect", connectParams)
    }

    /**
     * 处理 incoming 消息
     */
    private suspend fun handleIncomingMessage(text: String) {
        try {
            val jsonElement = json.parseToJsonElement(text)
            val type = jsonElement.jsonObject["type"]?.jsonPrimitive?.content
            
            when (type) {
                "event" -> handleEvent(jsonElement)
                "req" -> handleRequest(jsonElement)
                "res" -> handleResponse(jsonElement)
                else -> log("未知消息类型: $type")
            }
        } catch (e: Exception) {
            log("解析消息失败: ${e.message}")
        }
    }
    
    /**
     * 处理事件消息
     */
    private suspend fun handleEvent(jsonElement: JsonElement) {
        val event = jsonElement.jsonObject["event"]?.jsonPrimitive?.content ?: return
        
        when (event) {
            "connect.challenge" -> {
                // Gateway 发送 challenge，需要签名响应
                val payload = jsonElement.jsonObject["payload"]?.jsonObject
                val challenge = payload?.get("challenge")?.jsonPrimitive?.content
                if (challenge != null) {
                    pendingChallenge = challenge
                    // 发送签名响应
                    handleChallenge(challenge)
                }
            }
            "connect.ready" -> {
                // 连接成功
                _connectionState.value = ConnectionState.AUTHENTICATED
                log("认证成功，连接就绪")
                startHeartbeat()
            }
            "connect.error" -> {
                val error = jsonElement.jsonObject["payload"]?.jsonObject?.get("error")?.jsonPrimitive?.content
                log("连接错误: $error")
                _connectionState.value = ConnectionState.DISCONNECTED
            }
            else -> {
                messageHandler?.onEvent(event, jsonElement.jsonObject["payload"])
            }
        }
    }
    
    /**
     * 处理 challenge（签名验证）
     */
    private fun handleChallenge(challenge: String) {
        // 简化实现：如果有 token，直接用 token 作为签名
        // 实际应该使用 HMAC-SHA256 签名
        val signature = if (deviceToken != null) {
            // TODO: 实现真正的签名
            deviceToken!!
        } else {
            // 无 token，跳过认证
            ""
        }
        
        sendRequest("connect.auth", mapOf(
            "challenge" to challenge,
            "signature" to signature
        ))
    }
    
    /**
     * 处理请求消息（Gateway 调用 Node 命令）
     */
    private suspend fun handleRequest(jsonElement: JsonElement) {
        val id = jsonElement.jsonObject["id"]?.jsonPrimitive?.content ?: return
        val method = jsonElement.jsonObject["method"]?.jsonPrimitive?.content ?: return
        val params = jsonElement.jsonObject["params"]?.jsonObject
        
        log("收到命令: $method")
        
        when (method) {
            "node.invoke" -> handleNodeInvoke(id, params)
            else -> {
                messageHandler?.onRequest(id, method, params)
                    ?: sendResponse(id, null, "Unsupported method: $method")
            }
        }
    }
    
    /**
     * 处理 node.invoke 命令
     */
    private suspend fun handleNodeInvoke(id: String, params: JsonObject?) {
        val command = params?.get("command")?.jsonPrimitive?.content
        val commandParams = params?.get("params")?.jsonObject
        
        if (command == null) {
            sendResponse(id, null, "Missing command")
            return
        }
        
        val result = messageHandler?.onCommand(command, commandParams)
        if (result != null) {
            sendResponse(id, result.toJsonObject())
        } else {
            sendResponse(id, null, "Unknown command: $command")
        }
    }
    
    /**
     * 处理响应消息
     */
    private fun handleResponse(jsonElement: JsonElement) {
        val id = jsonElement.jsonObject["id"]?.jsonPrimitive?.content ?: return
        val ok = jsonElement.jsonObject["ok"]?.jsonPrimitive?.boolean ?: false
        
        if (ok) {
            log("请求成功: $id")
        } else {
            val error = jsonElement.jsonObject["error"]?.jsonPrimitive?.content ?: "Unknown error"
            log("请求失败: $id - $error")
        }
        
        messageHandler?.onResponse(id, ok, jsonElement.jsonObject["payload"], 
            jsonElement.jsonObject["error"]?.jsonPrimitive?.content)
    }
    
    /**
     * 启动心跳
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(30000)
                if (_connectionState.value == ConnectionState.AUTHENTICATED) {
                    sendRequest("ping")
                }
            }
        }
    }
    
    /**
     * 调度重连
     */
    private fun scheduleReconnect() {
        if (reconnectAttempts >= maxReconnectAttempts) {
            log("达到最大重连次数，停止重连")
            return
        }
        
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            reconnectAttempts++
            val delayMs = minOf(1000L * (1 shl reconnectAttempts), 30000L) // 指数退避
            log("将在 ${delayMs/1000} 秒后重连 (尝试 $reconnectAttempts/$maxReconnectAttempts)")
            delay(delayMs)
            
            if (gatewayUrl.isNotEmpty()) {
                connect(gatewayUrl)
            }
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

/**
 * 消息类型定义
 */
@Serializable
sealed class NodeMessage {
    @Serializable
    data class Request(
        val type: String = "req",
        val id: String,
        val method: String,
        val params: JsonObject = JsonObject(emptyMap())
    ) : NodeMessage()
    
    @Serializable
    data class Response(
        val type: String = "res",
        val id: String,
        val ok: Boolean,
        val payload: Any? = null,
        val error: String? = null
    ) : NodeMessage()
    
    @Serializable
    data class Event(
        val type: String = "event",
        val event: String,
        val payload: Any? = null
    ) : NodeMessage()
}

/**
 * 消息处理器接口
 */
interface MessageHandler {
    /**
     * 处理命令
     */
    suspend fun onCommand(command: String, params: JsonObject?): Map<String, Any>? {
        return null
    }
    
    /**
     * 处理事件
     */
    fun onEvent(event: String, payload: JsonElement?) {}
    
    /**
     * 处理请求
     */
    suspend fun onRequest(id: String, method: String, params: JsonObject?) {
        // 默认返回不支持
    }
    
    /**
     * 处理响应
     */
    fun onResponse(id: String, ok: Boolean, payload: JsonElement?, error: String?) {}
}

/**
 * 扩展函数：Map 转 JsonObject
 */
fun Map<String, Any?>.toJsonObject(): JsonObject {
    return JsonObject(this.mapValues { (_, value) ->
        when (value) {
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Map<*, *> -> (value as Map<String, Any?>).toJsonObject()
            is List<*> -> JsonArray(value.map { 
                when (it) {
                    is String -> JsonPrimitive(it)
                    is Number -> JsonPrimitive(it)
                    is Boolean -> JsonPrimitive(it)
                    is Map<*, *> -> (it as Map<String, Any?>).toJsonObject()
                    else -> JsonPrimitive(it.toString())
                }
            })
            null -> JsonNull
            else -> JsonPrimitive(value.toString())
        }
    })
}