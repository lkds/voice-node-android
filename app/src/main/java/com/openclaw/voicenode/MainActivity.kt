package com.openclaw.voicenode

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.openclaw.voicenode.service.VoiceNodeService
import com.openclaw.voicenode.websocket.WebSocketManager
import kotlinx.coroutines.launch

/**
 * 主界面 - 显示连接状态和控制按钮
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var connectButton: Button
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    
    private var isServiceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        initViews()
        setupListeners()
        observeState()
    }

    private fun initViews() {
        statusText = findViewById(R.id.statusText)
        connectButton = findViewById(R.id.connectButton)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
    }

    private fun setupListeners() {
        // 连接 Gateway
        connectButton.setOnClickListener {
            lifecycleScope.launch {
                val gatewayUrl = "ws://127.0.0.1:18789" // TODO: 从设置读取
                WebSocketManager.instance.connect(gatewayUrl)
            }
        }
        
        // 启动后台服务
        startButton.setOnClickListener {
            startForegroundService()
        }
        
        // 停止服务
        stopButton.setOnClickListener {
            stopService(Intent(this, VoiceNodeService::class.java))
            isServiceRunning = false
            updateButtons()
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            WebSocketManager.instance.connectionState.collect { state ->
                statusText.text = "状态: ${state.name}"
                updateButtons()
            }
        }
    }

    private fun startForegroundService() {
        val intent = Intent(this, VoiceNodeService::class.java)
        startForegroundService(intent)
        isServiceRunning = true
        updateButtons()
    }

    private fun updateButtons() {
        startButton.isEnabled = !isServiceRunning
        stopButton.isEnabled = isServiceRunning
    }
}