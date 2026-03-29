package com.openclaw.voicenode.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import java.util.*

/**
 * 语音合成客户端 - 封装 Android TextToSpeech
 */
class TTSClient(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    
    // 用于观察 TTS 事件
    private val _speakFlow = MutableSharedFlow<Result>(replay = 0)
    val speakFlow: SharedFlow<Result> = _speakFlow.asSharedFlow()
    
    sealed class Result {
        object Ready : Result()
        data class Start(val utteranceId: String) : Result()
        data class Done(val utteranceId: String) : Result()
        data class Error(val utteranceId: String, val error: Int = 0) : Result()
    }

    /**
     * 初始化 TTS
     */
    fun init(): Flow<Result> = callbackFlow {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                
                // 设置中文语音
                val localeResult = tts?.setLanguage(Locale.CHINESE)
                if (localeResult == TextToSpeech.LANG_MISSING_DATA || localeResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    // 回退到英文
                    tts?.setLanguage(Locale.US)
                }
                
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        utteranceId?.let { 
                            trySend(Result.Start(it))
                            _speakFlow.tryEmit(Result.Start(it))
                        }
                    }
                    
                    override fun onDone(utteranceId: String?) {
                        utteranceId?.let { 
                            trySend(Result.Done(it))
                            _speakFlow.tryEmit(Result.Done(it))
                        }
                    }
                    
                    override fun onError(utteranceId: String?) {
                        utteranceId?.let { 
                            trySend(Result.Error(it))
                            _speakFlow.tryEmit(Result.Error(it))
                        }
                    }
                    
                    // Android 12+ 支持更详细的错误回调
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        utteranceId?.let { 
                            trySend(Result.Error(it, errorCode))
                            _speakFlow.tryEmit(Result.Error(it, errorCode))
                        }
                    }
                })
                
                trySend(Result.Ready)
            } else {
                close(Exception("TTS initialization failed with status: $status"))
            }
        }
        
        awaitClose {
            // 不在这里 shutdown，保持实例
        }
    }

    /**
     * 播放文本（替换队列）
     */
    fun speak(text: String, utteranceId: String = UUID.randomUUID().toString()): Boolean {
        if (!isInitialized) return false
        return tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId) == TextToSpeech.SUCCESS
    }

    /**
     * 添加到队列播放
     */
    fun speakQueue(text: String, utteranceId: String = UUID.randomUUID().toString()): Boolean {
        if (!isInitialized) return false
        return tts?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId) == TextToSpeech.SUCCESS
    }

    /**
     * 停止播放
     */
    fun stop() {
        tts?.stop()
    }

    /**
     * 设置语速
     */
    fun setSpeed(speed: Float) {
        tts?.setSpeechRate(speed)
    }

    /**
     * 设置音调
     */
    fun setPitch(pitch: Float) {
        tts?.setPitch(pitch)
    }
    
    /**
     * 设置语言
     */
    fun setLanguage(locale: Locale): Int {
        return tts?.setLanguage(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED
    }

    /**
     * 释放资源
     */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}