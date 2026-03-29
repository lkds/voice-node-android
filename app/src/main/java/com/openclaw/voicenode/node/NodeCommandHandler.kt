package com.openclaw.voicenode.node

import com.openclaw.voicenode.voice.STTClient
import com.openclaw.voicenode.voice.TTSClient
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * Node 命令处理器 - 处理 Gateway 发来的 node.invoke 命令
 * 
 * 支持的命令:
 * - voice.listen: 开始语音识别
 * - voice.speak: 语音合成播放
 * - voice.stop: 停止当前操作
 */
class NodeCommandHandler(
    private val sttClient: STTClient,
    private val ttsClient: TTSClient
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // 当前操作
    private var currentListenJob: Job? = null
    private var currentSpeakJob: Job? = null
    
    /**
     * 处理命令
     * @param command 命令名称，如 "voice.listen"
     * @param params 命令参数 (JSONObject)
     * @return 响应结果 Map
     */
    suspend fun handleCommand(
        command: String,
        params: JSONObject?
    ): Map<String, Any> = withContext(Dispatchers.IO) {
        when (command) {
            "voice.listen" -> handleVoiceListen(params)
            "voice.speak" -> handleVoiceSpeak(params)
            "voice.stop" -> handleVoiceStop()
            else -> mapOf("error" to "Unknown command: $command")
        }
    }

    /**
     * 处理 voice.listen 命令
     */
    private suspend fun handleVoiceListen(params: JSONObject?): Map<String, Any> {
        val timeout = params?.optLong("timeout", 10000L) ?: 10000L
        
        return try {
            var finalText = ""
            var hasError = false
            var errorMessage = ""
            var partialText = ""
            
            currentListenJob = scope.launch {
                sttClient.listen(timeout).collect { result ->
                    when (result) {
                        is STTClient.Result.Success -> {
                            finalText = result.text
                        }
                        is STTClient.Result.Partial -> {
                            partialText = result.text
                        }
                        is STTClient.Result.Error -> {
                            hasError = true
                            errorMessage = result.message
                        }
                        else -> {
                            // Ready, Begin, End
                        }
                    }
                }
            }
            
            currentListenJob?.join()
            currentListenJob = null
            
            if (hasError) {
                mapOf("error" to errorMessage, "success" to false)
            } else {
                mapOf(
                    "text" to finalText,
                    "partial" to partialText,
                    "success" to true
                )
            }
        } catch (e: Exception) {
            mapOf("error" to (e.message ?: "STT failed"), "success" to false)
        }
    }

    /**
     * 处理 voice.speak 命令
     */
    private suspend fun handleVoiceSpeak(params: JSONObject?): Map<String, Any> {
        val text = params?.optString("text", "") ?: ""
        
        if (text.isEmpty()) {
            return mapOf("error" to "Text is required", "success" to false)
        }
        
        return try {
            val utteranceId = java.util.UUID.randomUUID().toString()
            var completed = false
            var hasError = false
            
            currentSpeakJob = scope.launch {
                ttsClient.speakFlow.collect { result ->
                    when (result) {
                        is TTSClient.Result.Done -> {
                            if (result.utteranceId == utteranceId) {
                                completed = true
                                cancel()
                            }
                        }
                        is TTSClient.Result.Error -> {
                            hasError = true
                            cancel()
                        }
                        else -> {}
                    }
                }
            }
            
            val success = ttsClient.speak(text, utteranceId)
            
            if (!success) {
                currentSpeakJob?.cancel()
                currentSpeakJob = null
                return mapOf("error" to "TTS failed to start", "success" to false)
            }
            
            // 等待播放完成（最多 60 秒）
            withTimeoutOrNull(60000) {
                while (!completed && !hasError) {
                    delay(100)
                }
            }
            
            currentSpeakJob?.cancel()
            currentSpeakJob = null
            
            if (hasError) {
                mapOf("error" to "TTS playback error", "success" to false)
            } else {
                mapOf("utteranceId" to utteranceId, "success" to true)
            }
        } catch (e: Exception) {
            mapOf("error" to (e.message ?: "TTS failed"), "success" to false)
        }
    }

    /**
     * 处理 voice.stop 命令
     */
    private fun handleVoiceStop(): Map<String, Any> {
        currentListenJob?.cancel()
        currentListenJob = null
        
        currentSpeakJob?.cancel()
        currentSpeakJob = null
        
        sttClient.cancel()
        ttsClient.stop()
        
        return mapOf("stopped" to true, "success" to true)
    }

    /**
     * 释放资源
     */
    fun destroy() {
        scope.cancel()
        currentListenJob?.cancel()
        currentSpeakJob?.cancel()
        sttClient.cancel()
        ttsClient.shutdown()
    }
}