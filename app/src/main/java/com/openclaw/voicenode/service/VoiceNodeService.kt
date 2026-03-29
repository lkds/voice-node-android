package com.openclaw.voicenode.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.*
import java.util.*

/**
 * 后台语音服务 - Foreground Service 保持后台运行
 */
class VoiceNodeService : Service() {

    private val binder = LocalBinder()
    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    
    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "voice_node_channel"
    }

    inner class LocalBinder : Binder() {
        fun getService(): VoiceNodeService = this@VoiceNodeService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initTTS()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 启动前台服务
        startForeground()
        
        // 连接 WebSocket
        serviceScope.launch {
            // TODO: 从 SharedPreferences 获取 Gateway URL
            // WebSocketManager.instance.connect("ws://...")
        }
        
        return START_STICKY // 服务被杀后自动重启
    }

    override fun onDestroy() {
        serviceScope.cancel()
        tts?.shutdown()
        super.onDestroy()
    }

    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "语音服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "后台语音监听服务运行状态"
            }
            
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * 启动前台服务
     */
    private fun startForeground() {
        val notification = createNotification()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * 创建前台通知
     */
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Voice Node 运行中")
            .setContentText("随时可以语音唤醒")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    /**
     * 初始化 TTS
     */
    private fun initTTS() {
        tts = TextToSpeech(this, TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINESE
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        // 开始播放
                    }
                    override fun onDone(utteranceId: String?) {
                        // 播放完成，通知 WebSocketManager
                        onSpeakComplete()
                    }
                    override fun onError(utteranceId: String?) {
                        // 播放错误
                    }
                })
                isInitialized = true
            }
        })
    }

    /**
     * 播放文本
     */
    fun speak(text: String, onComplete: () -> Unit = {}) {
        if (!isInitialized) {
            onComplete()
            return
        }
        
        val utteranceId = UUID.randomUUID().toString()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        
        // 保存回调
        speakCallbacks[utteranceId] = onComplete
    }

    /**
     * 停止播放
     */
    fun stopSpeaking() {
        tts?.stop()
    }

    private val speakCallbacks = mutableMapOf<String, () -> Unit>()

    private fun onSpeakComplete() {
        // 播放完成后，通知可以继续监听
    }
}