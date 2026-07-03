package com.scroff.ui.screen

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scroff.data.model.Schedule
import com.scroff.data.model.ScheduleAction
import com.scroff.ui.ExecuteResult
import com.scroff.ui.ScheduleViewModel
import com.scroff.ui.component.ScheduleCard
import kotlinx.coroutines.flow.collectLatest
import java.util.Locale

@Composable
fun HomeScreen(
    onNavigateToAdd: () -> Unit,
    onNavigateToEdit: (Schedule) -> Unit,
    onNavigateToDebugLog: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewModel: ScheduleViewModel = viewModel(
        factory = ScheduleViewModel.Factory(context.applicationContext as android.app.Application)
    )
    val schedules by viewModel.schedules.collectAsState()
    val isAutoStart by remember { mutableStateOf(viewModel.isAutoStartEnabled()) }
    var autoStartState by remember { mutableStateOf(isAutoStart) }

    // 响应式无障碍状态：每次回到前台重新检查
    var isAccessibilityEnabled by remember { mutableStateOf(viewModel.isAccessibilityEnabled) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAccessibilityEnabled = viewModel.isAccessibilityEnabled
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val snackbarHostState = remember { SnackbarHostState() }

    // 监听执行结果并弹 Snackbar
    LaunchedEffect(Unit) {
        viewModel.executeResults.collectLatest { result ->
            when (result) {
                is ExecuteResult.Success -> {
                    snackbarHostState.showSnackbar(
                        message = result.message,
                        duration = SnackbarDuration.Short
                    )
                }
                is ExecuteResult.AccessibilityNotEnabled -> {
                    val action = snackbarHostState.showSnackbar(
                        message = "请先开启无障碍服务",
                        actionLabel = "前往开启",
                        duration = SnackbarDuration.Long
                    )
                    if (action == SnackbarResult.ActionPerformed) {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                }
                is ExecuteResult.AccessibilityNotConnected -> {
                    // 系统级已开启，但本进程还没拿到 service 实例（系统没派发过事件）
                    // 让用户按 Home 键再回 App，触发 onServiceConnected
                    val action = snackbarHostState.showSnackbar(
                        message = "无障碍服务已开启但未连接，请按 Home 键返回桌面后再点执行",
                        duration = SnackbarDuration.Long
                    )
                    if (action == SnackbarResult.ActionPerformed) {
                        val home = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(home)
                    }
                }
                is ExecuteResult.Rejected -> {
                    snackbarHostState.showSnackbar(
                        message = result.message,
                        duration = SnackbarDuration.Long
                    )
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier.fillMaxSize()
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF4F6FA))
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp)
        ) {
            // 顶部标题栏
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color(0xFF4F7CFF), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("S", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    "Scroff 屏幕定时",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2C3E50)
                )
                Spacer(modifier = Modifier.weight(1f))
                // 诊断按钮
                IconButton(onClick = onNavigateToDebugLog) {
                    Icon(
                        Icons.Filled.BugReport,
                        contentDescription = "诊断日志",
                        tint = Color(0xFF4F7CFF),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // 无障碍提示
            if (!isAccessibilityEnabled) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3CD))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "需要开启无障碍服务",
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF856404),
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "为了让定时任务能够关闭屏幕，请前往系统设置开启「Scroff」无障碍服务",
                            fontSize = 12.sp,
                            color = Color(0xFF856404)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }) {
                            Text("前往开启", color = Color(0xFF4F7CFF))
                        }
                    }
                }
            }

            // 添加任务按钮
            Button(
                onClick = onNavigateToAdd,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F7CFF))
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("添加定时任务", fontSize = 14.sp)
            }

            // 开机自启开关
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            "开机自启动",
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF2C3E50),
                            fontSize = 14.sp
                        )
                        Text(
                            "开机后自动启动应用并恢复定时任务",
                            fontSize = 11.sp,
                            color = Color(0xFF888888)
                        )
                    }
                    Switch(
                        checked = autoStartState,
                        onCheckedChange = {
                            autoStartState = it
                            viewModel.setAutoStartEnabled(it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = Color(0xFF4F7CFF),
                            checkedThumbColor = Color.White
                        )
                    )
                }
            }

            // 任务列表
            Text(
                "定时任务 (${schedules.size})",
                fontSize = 13.sp,
                color = Color(0xFF888888),
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (schedules.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("暂无定时任务", color = Color(0xFFAAAAAA), fontSize = 14.sp)
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    schedules.forEach { schedule ->
                        ScheduleCard(
                            schedule = schedule,
                            onEdit = { onNavigateToEdit(schedule) },
                            onDelete = { viewModel.delete(schedule) },
                            onExecute = { viewModel.executeNow(schedule) },
                            onToggle = { viewModel.toggleEnabled(schedule) }
                        )
                    }
                }
            }
        }
    }
}
