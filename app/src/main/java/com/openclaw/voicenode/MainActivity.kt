package com.openclaw.voicenode

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.openclaw.voicenode.service.ServiceState
import com.openclaw.voicenode.service.VoiceNodeService
import com.openclaw.voicenode.service.VoiceState
import com.openclaw.voicenode.websocket.WebSocketManager
import kotlinx.coroutines.launch

/**
 * 主界面 - 显示连接状态和控制按钮
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var voiceStatusText: TextView
    private lateinit var gatewayUrlInput: EditText
    private lateinit var deviceTokenInput: EditText
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var logText: TextView
    private lateinit var scrollView: ScrollView
    
    private var voiceService: VoiceNodeService? = null
    private var isBound = false
    
    private val REQUIRED_PERMISSIONS = mutableListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.INTERNET
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()
    
    private val REQUEST_CODE_PERMISSIONS = 1001

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as VoiceNodeService.LocalBinder
            voiceService = binder.getService()
            isBound = true
            observeService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            voiceService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        initViews()
        loadSettings()
        setupListeners()
        checkPermissions()
    }
    
    override fun onStart() {
        super.onStart()
        bindService()
    }
    
    override fun onStop() {
        super.onStop()
        unbindService()
    }

    private fun initViews() {
        statusText = findViewById(R.id.statusText)
        voiceStatusText = findViewById(R.id.voiceStatusText)
        gatewayUrlInput = findViewById(R.id.gatewayUrlInput)
        deviceTokenInput = findViewById(R.id.deviceTokenInput)
        connectButton = findViewById(R.id.connectButton)
        disconnectButton = findViewById(R.id.disconnectButton)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        logText = findViewById(R.id.logText)
        scrollView = findViewById(R.id.scrollView)
    }
    
    private fun loadSettings() {
        val prefs = getSharedPreferences("voice_node_prefs", Context.MODE_PRIVATE)
        gatewayUrlInput.setText(prefs.getString("gateway_url", ""))
        deviceTokenInput.setText(prefs.getString("device_token", ""))
    }
    
    private fun saveSettings() {
        val prefs = getSharedPreferences("voice_node_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("gateway_url", gatewayUrlInput.text.toString())
            .putString("device_token", deviceTokenInput.text.toString())
            .apply()
    }

    private fun setupListeners() {
        // 连接 Gateway
        connectButton.setOnClickListener {
            val gatewayUrl = gatewayUrlInput.text.toString().trim()
            if (gatewayUrl.isEmpty()) {
                Toast.makeText(this, "请输入 Gateway URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            saveSettings()
            
            if (!isBound || voiceService == null) {
                startAndBindService()
            }
            
            lifecycleScope.launch {
                val token = deviceTokenInput.text.toString().trim().takeIf { it.isNotEmpty() }
                voiceService?.connect(gatewayUrl, token)
            }
        }
        
        // 断开连接
        disconnectButton.setOnClickListener {
            voiceService?.disconnect()
        }
        
        // 启动后台服务
        startButton.setOnClickListener {
            saveSettings()
            startAndBindService()
        }
        
        // 停止服务
        stopButton.setOnClickListener {
            stopService(Intent(this, VoiceNodeService::class.java))
            isBound = false
            voiceService = null
            updateButtons()
        }
    }
    
    private fun startAndBindService() {
        val gatewayUrl = gatewayUrlInput.text.toString().trim()
        val token = deviceTokenInput.text.toString().trim()
        
        val intent = Intent(this, VoiceNodeService::class.java).apply {
            action = VoiceNodeService.ACTION_START
            putExtra(VoiceNodeService.EXTRA_GATEWAY_URL, gatewayUrl)
            putExtra(VoiceNodeService.EXTRA_DEVICE_TOKEN, token.takeIf { it.isNotEmpty() })
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        bindService()
    }
    
    private fun bindService() {
        if (!isBound) {
            val intent = Intent(this, VoiceNodeService::class.java)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }
    
    private fun unbindService() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
    
    private fun observeService() {
        // 观察服务状态
        lifecycleScope.launch {
            voiceService?.serviceState?.collect { state ->
                runOnUiThread {
                    when (state) {
                        is ServiceState.Idle -> {
                            statusText.text = "状态: 空闲"
                        }
                        is ServiceState.Running -> {
                            statusText.text = "状态: 服务运行中"
                        }
                        is ServiceState.Connecting -> {
                            statusText.text = "状态: 连接中..."
                        }
                        is ServiceState.Connected -> {
                            statusText.text = "状态: 已连接"
                        }
                        is ServiceState.Error -> {
                            statusText.text = "状态: 错误 - ${state.message}"
                        }
                    }
                    updateButtons()
                }
            }
        }
        
        // 观察语音状态
        lifecycleScope.launch {
            voiceService?.voiceState?.collect { state ->
                runOnUiThread {
                    when (state) {
                        is VoiceState.Idle -> {
                            voiceStatusText.text = "语音: 空闲"
                        }
                        is VoiceState.Listening -> {
                            voiceStatusText.text = "语音: 正在监听..."
                        }
                        is VoiceState.Speaking -> {
                            voiceStatusText.text = "语音: 正在播放..."
                        }
                    }
                }
            }
        }
        
        // 观察日志
        lifecycleScope.launch {
            voiceService?.logs?.collect { logs ->
                runOnUiThread {
                    logText.text = "日志:\n${logs.joinToString("\n")}"
                    scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }
        }
    }
    
    private fun observeWebSocket() {
        lifecycleScope.launch {
            WebSocketManager.instance.connectionState.collect { state ->
                runOnUiThread {
                    updateButtons()
                }
            }
        }
    }

    private fun updateButtons() {
        val connected = voiceService?.serviceState?.value is ServiceState.Connected
        val running = voiceService?.serviceState?.value !is ServiceState.Idle
        
        connectButton.isEnabled = !connected
        disconnectButton.isEnabled = connected
        startButton.isEnabled = !running
        stopButton.isEnabled = running
    }
    
    private fun checkPermissions() {
        val missingPermissions = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                REQUEST_CODE_PERMISSIONS
            )
        }
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (!allGranted) {
                Toast.makeText(
                    this,
                    "需要麦克风权限才能使用语音功能",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_clear_logs -> {
                voiceService?.clearLogs()
                true
            }
            R.id.action_settings -> {
                // 打开设置页面
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}