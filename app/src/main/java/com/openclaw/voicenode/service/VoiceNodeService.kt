package com.openclaw.voicenode.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.SharedPreferences
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.openclaw.voicenode.R
import com.openclaw.voicenode.voice.STTClient
import com.openclaw.voicenode.voice.TTSClient
import com.openclaw.voicenode.websocket.MessageHandler
import com.openclaw.voicenode.websocket.WebSocketManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.*

/**
 * 后台语音服务 - Foreground Service 保持后台运行
 */
class VoiceNodeService : Service(), MessageHandler {

    private val binder = LocalBinder()
    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var sttClient: STTClient? = null
    private var ttsClient: TTSClient? = null
    private val wsManager = WebSocketManager.instance
    private lateinit var prefs: SharedPreferences
    
    private val _serviceState = MutableStateFlow<ServiceState>(ServiceState.Idle)
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()
    
    private val _voiceState = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()
    
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs
    
    private var currentListenJob: Job? = null
    private var currentSpeakJob: Job? = null

    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "voice_node_channel"
        const val ACTION_START = "com.openclaw.voicenode.START"
        const val ACTION_STOP = "com.openclaw.voicenode.STOP"
        const val EXTRA_GATEWAY_URL = "gateway_url"
        const val EXTRA_DEVICE_TOKEN = "device_token"
    }

    inner class LocalBinder : Binder() {
        fun getService(): VoiceNodeService = this@VoiceNodeService
    }

    override fun onCreate() {
        super.onCreate()
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        createNotificationChannel()
        initClients()
        observeWebSocket()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val gatewayUrl = intent.getStringExtra(EXTRA_GATEWAY_URL)
                    ?: prefs.getString("gateway_url", null)
                val deviceToken = intent.getStringExtra(EXTRA_DEVICE_TOKEN)
                    ?: prefs.getString("device_token", null)
                
                if (gatewayUrl != null) {
                    startForeground()
                    connectToGateway(gatewayUrl, deviceToken)
                } else {
                    log("未配置 Gateway URL")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                disconnect()
                stopSelf()
            }
            else -> {
                startForeground()
                prefs.getString("gateway_url", null)?.let { connectToGateway(it, prefs.getString("device_token", null)) }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        disconnect()
        sttClient?.cancel()
        ttsClient?.shutdown()
        super.onDestroy()
    }
    
    fun connect(gatewayUrl: String, deviceToken: String? = null) {
        connectToGateway(gatewayUrl, deviceToken)
    }
    
    fun disconnect() {
        wsManager.disconnect()
        _serviceState.value = ServiceState.Idle
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW)
            channel.description = getString(R.string.notification_channel_desc)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startForeground() {
        val notification = createNotification("语音节点就绪")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        _serviceState.value = ServiceState.Running
    }
    
    private fun updateNotification(text: String) {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            .notify(NOTIFICATION_ID, createNotification(text))
    }

    private fun createNotification(contentText: String): Notification {
        val stopIntent = Intent(this, VoiceNodeService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Voice Node")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPendingIntent)
            .build()
    }

    private fun initClients() {
        sttClient = STTClient(applicationContext)
        ttsClient = TTSClient(applicationContext)
        wsManager.setMessageHandler(this)
        
        serviceScope.launch {
            ttsClient?.init()?.collect { if (it is TTSClient.Result.Ready) log("TTS 初始化完成") }
        }
    }
    
    private fun observeWebSocket() {
        serviceScope.launch {
            wsManager.connectionState.collect { state ->
                log("WebSocket 状态: $state")
                when (state) {
                    WebSocketManager.ConnectionState.AUTHENTICATED -> { _serviceState.value = ServiceState.Connected; updateNotification("已连接到 Gateway") }
                    WebSocketManager.ConnectionState.CONNECTED -> _serviceState.value = ServiceState.Connecting
                    WebSocketManager.ConnectionState.DISCONNECTED -> { _serviceState.value = ServiceState.Running; updateNotification("连接断开，等待重连...") }
                    WebSocketManager.ConnectionState.CONNECTING, WebSocketManager.ConnectionState.RETRYING -> { _serviceState.value = ServiceState.Connecting; updateNotification("连接中...") }
                }
            }
        }
        serviceScope.launch { wsManager.logs.collect { addLog(it) } }
    }
    
    private fun connectToGateway(gatewayUrl: String, deviceToken: String?) {
        serviceScope.launch {
            _serviceState.value = ServiceState.Connecting
            log("正在连接: $gatewayUrl")
            deviceToken?.let { wsManager.setDeviceToken(it) }
            
            val result = wsManager.connect(gatewayUrl)
            if (result.isFailure) {
                log("连接失败: ${result.exceptionOrNull()?.message}")
                _serviceState.value = ServiceState.Error(result.exceptionOrNull()?.message ?: "连接失败")
            }
        }
    }
    
    override suspend fun onCommand(command: String, params: JSONObject?): Map<String, Any>? {
        log("执行命令: $command")
        return when (command) {
            "voice.listen" -> handleVoiceListen(params)
            "voice.speak" -> handleVoiceSpeak(params)
            "voice.stop" -> handleVoiceStop()
            else -> { log("未知命令: $command"); mapOf("error" to "Unknown command: $command") }
        }
    }
    
    private suspend fun handleVoiceListen(params: JSONObject?): Map<String, Any> {
        val timeout = params?.optLong("timeout", 10000L) ?: 10000L
        log("开始语音识别，超时: ${timeout}ms")
        _voiceState.value = VoiceState.Listening
        updateNotification("正在监听...")
        
        return try {
            var finalText = ""
            var hasError = false
            var errorMessage = ""
            
            currentListenJob = serviceScope.launch {
                sttClient?.listen(timeout)?.collect { result ->
                    when (result) {
                        is STTClient.Result.Success -> { finalText = result.text; log("识别结果: ${result.text}") }
                        is STTClient.Result.Error -> { hasError = true; errorMessage = result.message; log("识别错误: ${result.message}") }
                        else -> {}
                    }
                }
            }
            currentListenJob?.join()
            currentListenJob = null
            
            _voiceState.value = VoiceState.Idle
            updateNotification("语音节点就绪")
            
            if (hasError) mapOf("error" to errorMessage) else mapOf("text" to finalText, "success" to true)
        } catch (e: Exception) {
            _voiceState.value = VoiceState.Idle
            log("语音识别异常: ${e.message}")
            mapOf("error" to (e.message ?: "Unknown error"))
        }
    }
    
    private suspend fun handleVoiceSpeak(params: JSONObject?): Map<String, Any> {
        val text = params?.optString("text", "") ?: ""
        if (text.isEmpty()) return mapOf("error" to "Text is required")
        
        log("开始语音合成: $text")
        _voiceState.value = VoiceState.Speaking
        updateNotification("正在播放...")
        
        return try {
            val utteranceId = UUID.randomUUID().toString()
            var completed = false
            
            currentSpeakJob = serviceScope.launch {
                ttsClient?.speakFlow?.collect { result ->
                    when (result) {
                        is TTSClient.Result.Done -> if (result.utteranceId == utteranceId) completed = true
                        is TTSClient.Result.Error -> completed = true
                        else -> {}
                    }
                }
            }
            
            val success = ttsClient?.speak(text, utteranceId) ?: false
            if (!success) { currentSpeakJob?.cancel(); currentSpeakJob = null; _voiceState.value = VoiceState.Idle; return mapOf("error" to "TTS failed to start") }
            
            withTimeoutOrNull(60000) { while (!completed) delay(100) }
            currentSpeakJob?.cancel(); currentSpeakJob = null
            
            _voiceState.value = VoiceState.Idle
            updateNotification("语音节点就绪")
            mapOf("success" to true, "utteranceId" to utteranceId)
        } catch (e: Exception) {
            _voiceState.value = VoiceState.Idle
            log("语音合成异常: ${e.message}")
            mapOf("error" to (e.message ?: "Unknown error"))
        }
    }
    
    private fun handleVoiceStop(): Map<String, Any> {
        log("停止语音操作")
        currentListenJob?.cancel(); currentListenJob = null
        currentSpeakJob?.cancel(); currentSpeakJob = null
        sttClient?.cancel()
        ttsClient?.stop()
        _voiceState.value = VoiceState.Idle
        updateNotification("语音节点就绪")
        return mapOf("stopped" to true)
    }
    
    override fun onEvent(event: String, payload: JSONObject?) { log("收到事件: $event") }
    override suspend fun onRequest(id: String, method: String, params: JSONObject?) { log("收到请求: $method") }
    override fun onResponse(id: String, ok: Boolean, payload: JSONObject?, error: String?) { log("收到响应: $id, ok=$ok") }
    
    private fun log(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        addLog("[$timestamp] $message")
    }
    
    private fun addLog(log: String) { _logs.value = (_logs.value + log).takeLast(100) }
    fun clearLogs() { _logs.value = emptyList() }
}

sealed class ServiceState {
    object Idle : ServiceState()
    object Running : ServiceState()
    object Connecting : ServiceState()
    object Connected : ServiceState()
    data class Error(val message: String) : ServiceState()
}

sealed class VoiceState {
    object Idle : VoiceState()
    object Listening : VoiceState()
    object Speaking : VoiceState()
}