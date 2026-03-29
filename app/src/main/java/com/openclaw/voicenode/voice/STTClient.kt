package com.openclaw.voicenode.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * 语音识别客户端 - 封装 Android SpeechRecognizer
 */
class STTClient(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    
    sealed class Result {
        data class Success(val text: String) : Result()
        data class Partial(val text: String) : Result()
        data class Error(val message: String) : Result()
        object Ready : Result()
        object Begin : Result()
        object End : Result()
    }

    /**
     * 开始监听，返回 Flow 流式结果
     */
    fun listen(timeoutMs: Long = 10000): Flow<Result> = callbackFlow {
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer = recognizer
        
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, timeoutMs)
        }
        
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                trySend(Result.Ready)
            }
            
            override fun onBeginningOfSpeech() {
                trySend(Result.Begin)
            }
            
            override fun onRmsChanged(rmsdB: Float) {
                // 音量变化，可用于 UI 反馈
            }
            
            override fun onBufferReceived(buffer: ByteArray?) {
                // 接收到音频数据
            }
            
            override fun onEndOfSpeech() {
                trySend(Result.End)
            }
            
            override fun onError(error: Int) {
                val message = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "音频录制错误"
                    SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "权限不足"
                    SpeechRecognizer.ERROR_NETWORK -> "网络错误"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                    SpeechRecognizer.ERROR_NO_MATCH -> "未能识别"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙碌"
                    SpeechRecognizer.ERROR_SERVER -> "服务器错误"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未检测到语音"
                    else -> "未知错误: $error"
                }
                trySend(Result.Error(message))
                close()
            }
            
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                if (text.isNotEmpty()) {
                    trySend(Result.Success(text))
                }
                close()
            }
            
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                if (text.isNotEmpty()) {
                    trySend(Result.Partial(text))
                }
            }
            
            override fun onEvent(eventType: Int, params: Bundle?) {
                // 其他事件
            }
        })
        
        recognizer.startListening(intent)
        
        awaitClose {
            recognizer.cancel()
            recognizer.destroy()
            speechRecognizer = null
        }
    }

    /**
     * 停止监听
     */
    fun stop() {
        speechRecognizer?.stopListening()
    }

    /**
     * 取消监听
     */
    fun cancel() {
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}