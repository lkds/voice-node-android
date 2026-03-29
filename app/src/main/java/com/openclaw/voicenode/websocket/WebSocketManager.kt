package com.openclaw.voicenode.websocket

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * WebSocket 连接管理器 - 实现完整的 OpenClaw Gateway Node 协议
 */
class WebSocketManager private constructor() {

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private var websocket: WebSocket? = null
    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val messageIdCounter = AtomicLong(0)
    
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _logs = MutableSharedFlow<String>(replay = 100)
    val logs: SharedFlow<String> = _logs.asSharedFlow()
    
    private var deviceId: String = UUID.randomUUID().toString()
    private var deviceToken: String? = null
    private var gatewayUrl: String = ""
    
    private var messageHandler: MessageHandler? = null
    private var heartbeatJob: Job? = null
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 10
    private var reconnectJob: Job? = null

    companion object {
        val instance = WebSocketManager()
    }
    
    fun setMessageHandler(handler: MessageHandler) { this.messageHandler = handler }
    fun setDeviceToken(token: String?) { this.deviceToken = token }
    fun setDeviceId(id: String) { this.deviceId = id }

    suspend fun connect(url: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (_connectionState.value == ConnectionState.CONNECTED || _connectionState.value == ConnectionState.AUTHENTICATED) disconnect()
            
            gatewayUrl = url
            _connectionState.value = ConnectionState.CONNECTING
            log("连接中: $url")
            
            websocket = client.newWebSocket(Request.Builder().url(url).build(), WebSocketListenerImpl())
            
            val result = waitForConnection(10000)
            if (result) { reconnectAttempts = 0; Result.success(Unit) }
            else { _connectionState.value = ConnectionState.DISCONNECTED; Result.failure(Exception("Connection timeout")) }
        } catch (e: Exception) {
            log("连接失败: ${e.message}")
            _connectionState.value = ConnectionState.DISCONNECTED
            Result.failure(e)
        }
    }
    
    private suspend fun waitForConnection(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) {
        connectionState.first { it == ConnectionState.CONNECTED || it == ConnectionState.AUTHENTICATED || it == ConnectionState.DISCONNECTED }
        _connectionState.value == ConnectionState.CONNECTED || _connectionState.value == ConnectionState.AUTHENTICATED
    } ?: false

    fun sendRequest(method: String, params: Map<String, Any> = emptyMap()): String {
        val id = "msg-${System.currentTimeMillis()}-${messageIdCounter.incrementAndGet()}"
        val message = JSONObject().apply {
            put("type", "req")
            put("id", id)
            put("method", method)
            put("params", params.toJSONObject())
        }
        sendMessage(message)
        return id
    }
    
    fun sendResponse(id: String, result: Map<String, Any>? = null, error: String? = null) {
        val message = JSONObject().apply {
            put("type", "res")
            put("id", id)
            put("ok", error == null)
            error?.let { put("error", it) }
            result?.let { put("payload", it.toJSONObject()) }
        }
        sendMessage(message)
    }
    
    fun sendEvent(event: String, data: Map<String, Any>? = null) {
        val message = JSONObject().apply {
            put("type", "event")
            put("event", event)
            data?.let { put("payload", it.toJSONObject()) }
        }
        sendMessage(message)
    }

    private fun sendMessage(message: JSONObject) {
        try {
            val jsonString = message.toString()
            websocket?.send(jsonString)
            log("发送: $jsonString")
        } catch (e: Exception) { log("发送失败: ${e.message}") }
    }

    fun disconnect() {
        heartbeatJob?.cancel()
        reconnectJob?.cancel()
        websocket?.close(1000, "User disconnect")
        websocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        log("已断开连接")
    }
    
    private fun log(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        scope.launch { _logs.emit("[$timestamp] $message") }
    }

    private inner class WebSocketListenerImpl : okhttp3.WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            log("WebSocket 已打开")
            _connectionState.value = ConnectionState.CONNECTED
            sendConnectRequest()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            log("收到: $text")
            scope.launch { handleIncomingMessage(text) }
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
            scheduleReconnect()
        }
    }

    private fun sendConnectRequest() {
        val connectParams = mapOf(
            "minProtocol" to 3, "maxProtocol" to 3,
            "client" to mapOf("id" to "voice-node-android", "version" to "1.0.0", "platform" to "android", "mode" to "node"),
            "role" to "node", "scopes" to emptyList<String>(),
            "caps" to listOf("voice.stt", "voice.tts"),
            "commands" to listOf("voice.listen", "voice.speak", "voice.stop"),
            "permissions" to mapOf("microphone" to true),
            "auth" to mapOf("token" to (deviceToken ?: "")),
            "device" to mapOf("id" to deviceId, "type" to "android", "name" to android.os.Build.MODEL, "nonce" to UUID.randomUUID().toString())
        )
        sendRequest("connect", connectParams)
    }

    private suspend fun handleIncomingMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "event" -> handleEvent(json)
                "req" -> handleRequest(json)
                "res" -> handleResponse(json)
                else -> log("未知消息类型")
            }
        } catch (e: Exception) { log("解析消息失败: ${e.message}") }
    }
    
    private suspend fun handleEvent(json: JSONObject) {
        when (json.optString("event")) {
            "connect.challenge" -> json.optJSONObject("payload")?.optString("challenge")?.let { handleChallenge(it) }
            "connect.ready" -> { _connectionState.value = ConnectionState.AUTHENTICATED; log("认证成功，连接就绪"); startHeartbeat() }
            "connect.error" -> { log("连接错误: ${json.optJSONObject("payload")?.optString("error")}"); _connectionState.value = ConnectionState.DISCONNECTED }
            else -> messageHandler?.onEvent(json.optString("event"), json.optJSONObject("payload"))
        }
    }
    
    private fun handleChallenge(challenge: String) {
        sendRequest("connect.auth", mapOf("challenge" to challenge, "signature" to (deviceToken ?: "")))
    }
    
    private suspend fun handleRequest(json: JSONObject) {
        val id = json.optString("id")
        val method = json.optString("method")
        val params = json.optJSONObject("params")
        log("收到命令: $method")
        
        if (method == "node.invoke") {
            val command = params?.optString("command")
            val commandParams = params?.optJSONObject("params")
            if (command == null) { sendResponse(id, null, "Missing command"); return }
            val result = messageHandler?.onCommand(command, commandParams)
            sendResponse(id, result, if (result == null) "Unknown command: $command" else null)
        } else {
            messageHandler?.onRequest(id, method, params) ?: sendResponse(id, null, "Unsupported method: $method")
        }
    }
    
    private fun handleResponse(json: JSONObject) {
        val id = json.optString("id")
        val ok = json.optBoolean("ok", false)
        log("${if (ok) "请求成功" else "请求失败"}: $id")
        messageHandler?.onResponse(id, ok, json.optJSONObject("payload"), json.optString("error"))
    }
    
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(30000)
                if (_connectionState.value == ConnectionState.AUTHENTICATED) sendRequest("ping")
            }
        }
    }
    
    private fun scheduleReconnect() {
        if (reconnectAttempts >= maxReconnectAttempts) { log("达到最大重连次数"); return }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            reconnectAttempts++
            val delayMs = minOf(1000L * (1 shl reconnectAttempts), 30000L)
            log("将在 ${delayMs/1000}s 后重连 ($reconnectAttempts/$maxReconnectAttempts)")
            delay(delayMs)
            if (gatewayUrl.isNotEmpty()) connect(gatewayUrl)
        }
    }

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, AUTHENTICATED, RETRYING }
}

interface MessageHandler {
    suspend fun onCommand(command: String, params: JSONObject?): Map<String, Any>? = null
    fun onEvent(event: String, payload: JSONObject?) {}
    suspend fun onRequest(id: String, method: String, params: JSONObject?) {}
    fun onResponse(id: String, ok: Boolean, payload: JSONObject?, error: String?) {}
}

fun Map<String, Any?>.toJSONObject(): JSONObject = JSONObject().apply {
    for ((key, value) in this@toJSONObject) {
        when (value) {
            is String -> put(key, value)
            is Number -> put(key, value)
            is Boolean -> put(key, value)
            is Map<*, *> -> put(key, (value as Map<String, Any?>).toJSONObject())
            is List<*> -> put(key, value)
            null -> put(key, null)
            else -> put(key, value.toString())
        }
    }
}