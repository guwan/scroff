package com.scroff.ui.screen

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scroff.debug.DebugLog
import com.scroff.debug.DebugLogStore
import com.scroff.debug.LogLevel
import com.scroff.ui.ScheduleViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val viewModel: ScheduleViewModel = viewModel(
        factory = ScheduleViewModel.Factory(context.applicationContext as android.app.Application)
    )
    val logs by DebugLogStore.logs.collectAsState()
    val listState = rememberLazyListState()

    // 新日志到达时自动滚动到底部
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("诊断日志", fontSize = 16.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 自检按钮
                    IconButton(onClick = {
                        viewModel.runDiagnostics()
                        Toast.makeText(context, "已开始自检，请查看日志", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Filled.BugReport, contentDescription = "自检")
                    }
                    // 分享按钮
                    IconButton(onClick = {
                        val text = DebugLogStore.exportText()
                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, text)
                            type = "text/plain"
                        }
                        context.startActivity(Intent.createChooser(sendIntent, "分享日志"))
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = "分享")
                    }
                    // 清空按钮
                    IconButton(onClick = { viewModel.clearLogs() }) {
                        Icon(Icons.Filled.Delete, contentDescription = "清空")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF4F7CFF),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1E1E1E))
                .padding(padding)
        ) {
            // 统计信息条
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2D2D2D))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val errorCount = logs.count { it.level == LogLevel.ERROR }
                val warnCount = logs.count { it.level == LogLevel.WARN }
                Text(
                    text = "共 ${logs.size} 条  |  E:$errorCount  W:$warnCount",
                    color = Color(0xFFCCCCCC),
                    fontSize = 11.sp
                )
                Text(
                    text = "自动滚动到底部",
                    color = Color(0xFF888888),
                    fontSize = 10.sp
                )
            }

            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("暂无日志", color = Color(0xFF888888), fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "点击右上角虫子图标开始自检\n或回到首页点击执行按钮",
                            color = Color(0xFF666666),
                            fontSize = 11.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(logs) { log ->
                        LogItem(log)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogItem(log: DebugLog) {
    val levelColor = when (log.level) {
        LogLevel.DEBUG -> Color(0xFF9E9E9E)
        LogLevel.INFO -> Color(0xFF4FC3F7)
        LogLevel.WARN -> Color(0xFFFFB74D)
        LogLevel.ERROR -> Color(0xFFEF5350)
    }

    val levelBgColor = when (log.level) {
        LogLevel.DEBUG -> Color(0xFF2A2A2A)
        LogLevel.INFO -> Color(0xFF1A2332)
        LogLevel.WARN -> Color(0xFF33291A)
        LogLevel.ERROR -> Color(0xFF321A1A)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(levelBgColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = log.formatTime(),
            color = Color(0xFF888888),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = log.formatLevel(),
            color = levelColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = log.message,
            color = Color(0xFFEEEEEE),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
    }
}
