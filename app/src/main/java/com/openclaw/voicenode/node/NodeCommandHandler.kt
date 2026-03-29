package com.openclaw.voicenode.node

import com.openclaw.voicenode.voice.STTClient
import com.openclaw.voicenode.voice.TTSClient
import kotlinx.coroutines.*
import org.json.JSONObject

/**
 * Node 命令处理器 - 处理 Gateway 发来的 node.invoke 命令
 */
class NodeCommandHandler(
    private val sttClient: STTClient,
    private val ttsClient: TTSClient
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * 处理 node.invoke 命令
     * @param command 命令名称，如 "voice.listen"
     * @param params 命令参数
     * @param requestId 请求 ID，用于响应
     * @return 响应结果
     */
    suspend fun handleCommand(
        command: String,
        params: JSONObject,
        requestId: String
    ): NodeResponse = withContext(Dispatchers.IO) {
        when (command) {
            "voice.listen" -> handleVoiceListen(params)
            "voice.speak" -> handleVoiceSpeak(params)
            "voice.stop" -> handleVoiceStop()
            else -> NodeResponse.Error("Unknown command: $command")
        }
    }

    /**
     * 处理 voice.listen 命令
     */
    private suspend fun handleVoiceListen(params: JSONObject): NodeResponse {
        val timeout = params.optLong("timeout", 10000)
        
        return try {
            var finalText = ""
            var hasError = false
            var errorMessage = ""
            
            sttClient.listen(timeout).collect { result ->
                when (result) {
                    is STTClient.Result.Success -> {
                        finalText = result.text
                    }
                    is STTClient.Result.Error -> {
                        hasError = true
                        errorMessage = result.message
                    }
                    else -> {
                        // 其他状态：Ready, Begin, End, Partial
                    }
                }
            }
            
            if (hasError) {
                NodeResponse.Error(errorMessage)
            } else {
                NodeResponse.Success(mapOf("text" to finalText))
            }
        } catch (e: Exception) {
            NodeResponse.Error(e.message ?: "STT failed")
        }
    }

    /**
     * 处理 voice.speak 命令
     */
    private suspend fun handleVoiceSpeak(params: JSONObject): NodeResponse {
        val text = params.optString("text", "")
        
        if (text.isEmpty()) {
            return NodeResponse.Error("Text is required")
        }
        
        return try {
            val utteranceId = java.util.UUID.randomUUID().toString()
            val success = ttsClient.speak(text, utteranceId)
            
            if (success) {
                // 等待播放完成
                // TODO: 使用 Flow 等待播放完成
                NodeResponse.Success(mapOf("utteranceId" to utteranceId))
            } else {
                NodeResponse.Error("TTS failed to start")
            }
        } catch (e: Exception) {
            NodeResponse.Error(e.message ?: "TTS failed")
        }
    }

    /**
     * 处理 voice.stop 命令
     */
    private fun handleVoiceStop(): NodeResponse {
        ttsClient.stop()
        sttClient.cancel()
        return NodeResponse.Success(mapOf("stopped" to true))
    }

    /**
     * 释放资源
     */
    fun destroy() {
        scope.cancel()
        sttClient.cancel()
        ttsClient.shutdown()
    }
}

/**
 * Node 响应密封类
 */
sealed class NodeResponse {
    data class Success(val data: Map<String, Any>) : NodeResponse()
    data class Error(val message: String) : NodeResponse()
    
    fun toMap(): Map<String, Any> = when (this) {
        is Success -> mapOf("ok" to true, "data" to data)
        is Error -> mapOf("ok" to false, "error" to message)
    }
}