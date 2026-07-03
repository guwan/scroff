package com.scroff.ui.screen

import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scroff.data.model.Schedule
import com.scroff.data.model.ScheduleAction
import com.scroff.ui.ScheduleViewModel
import java.util.Locale

@Composable
fun EditScheduleScreen(
    editingSchedule: Schedule?,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val viewModel: ScheduleViewModel = viewModel(
        factory = ScheduleViewModel.Factory(context.applicationContext as android.app.Application)
    )

    // 初始化表单
    LaunchedEffect(editingSchedule?.id) {
        if (editingSchedule != null) {
            viewModel.startEdit(editingSchedule)
        } else {
            viewModel.startAdd()
        }
    }

    val form = viewModel.formState
    var name by remember(form.isEditing, form.editingId) { mutableStateOf(form.name) }
    var hour by remember(form.isEditing, form.editingId) { mutableStateOf(form.hour) }
    var minute by remember(form.isEditing, form.editingId) { mutableStateOf(form.minute) }
    var action by remember(form.isEditing, form.editingId) { mutableStateOf(form.action) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFF4F6FA))
            .padding(16.dp)
    ) {
        Text(
            text = if (form.isEditing) "编辑定时任务" else "添加定时任务",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF2C3E50),
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // 任务名称
                Text("任务名称（可选）", fontSize = 12.sp, color = Color(0xFF888888))
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("留空将自动生成", fontSize = 13.sp, color = Color(0xFFAAAAAA)) },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 时间选择
                Text("执行时间", fontSize = 12.sp, color = Color(0xFF888888))
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(
                    onClick = {
                        TimePickerDialog(
                            context,
                            { _, h, m ->
                                hour = h
                                minute = m
                            },
                            hour,
                            minute,
                            true
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF2C3E50))
                ) {
                    Text(
                        String.format(Locale.getDefault(), "%02d:%02d", hour, minute),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 动作选择
                Text("动作类型", fontSize = 12.sp, color = Color(0xFF888888))
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = action == ScheduleAction.SCREEN_OFF,
                        onClick = { action = ScheduleAction.SCREEN_OFF },
                        label = { Text("关闭屏幕") },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFE53935),
                            selectedLabelColor = Color.White
                        )
                    )
                    FilterChip(
                        selected = action == ScheduleAction.SCREEN_ON,
                        onClick = { action = ScheduleAction.SCREEN_ON },
                        label = { Text("打开屏幕") },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF43A047),
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 操作按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("取消")
            }
            Button(
                onClick = {
                    viewModel.updateForm(
                        name = name,
                        hour = hour,
                        minute = minute,
                        action = action
                    )
                    viewModel.saveForm { onSaved() }
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F7CFF))
            ) {
                Text(if (form.isEditing) "更新" else "保存")
            }
        }
    }
}
