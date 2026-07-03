package com.scroff

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.scroff.debug.DebugLogStore
import com.scroff.ui.navigation.ScroffNavHost
import com.scroff.ui.theme.ScroffTheme

class MainActivity : ComponentActivity() {

    private var serviceConnection: ServiceConnection? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        DebugLogStore.i("MainActivity", "onCreate: 启动主动绑定 AccessibilityService")
        bindAccessibilityService()
        setContent {
            ScroffTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ScroffNavHost()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceConnection?.let {
            try {
                unbindService(it)
                DebugLogStore.d("MainActivity", "onDestroy: 已解绑 AccessibilityService")
            } catch (e: Exception) {
                DebugLogStore.d("MainActivity", "解绑异常: ${e.message}")
            }
        }
    }

    /**
     * 主动绑定 AccessibilityService，触发系统连接
     * 这是解决"无障碍已启用但本进程 instance 为 null"问题的关键：
     * - 系统不会主动调 onServiceConnected() 给未被使用的 AccessibilityService
     * - 我们主动 bind 一次，让系统创建实例并回调 onServiceConnected
     */
    private fun bindAccessibilityService() {
        try {
            val intent = Intent(android.accessibilityservice.AccessibilityService.SERVICE_INTERFACE)
                .setComponent(
                    ComponentName(
                        packageName,
                        "com.scroff.service.ScreenControlService"
                    )
                )
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    DebugLogStore.i("MainActivity", "ServiceConnection.onServiceConnected: $name")
                }
                override fun onServiceDisconnected(name: ComponentName?) {
                    DebugLogStore.w("MainActivity", "ServiceConnection.onServiceDisconnected")
                }
            }
            val bound = bindService(intent, conn, Context.BIND_AUTO_CREATE)
            serviceConnection = conn
            DebugLogStore.i("MainActivity", "bindService 返回: $bound")
        } catch (e: Exception) {
            DebugLogStore.e("MainActivity", "bindAccessibilityService 异常", e)
        }
    }
}
