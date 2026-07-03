package com.scroff.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scroff.data.model.Schedule
import com.scroff.data.model.ScheduleAction
import java.util.Locale

@Composable
fun ScheduleCard(
    schedule: Schedule,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onExecute: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    val isOff = schedule.action == ScheduleAction.SCREEN_OFF
    val accentColor = if (isOff) Color(0xFFE53935) else Color(0xFF43A047)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(accentColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isOff) "关闭屏幕" else "打开屏幕",
                        color = accentColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Switch(
                    checked = schedule.enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = Color(0xFF4F7CFF),
                        checkedThumbColor = Color.White
                    )
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = String.format(Locale.getDefault(), "%02d:%02d", schedule.hour, schedule.minute),
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF2C3E50)
            )

            Text(
                text = schedule.name,
                fontSize = 13.sp,
                color = Color(0xFF888888),
                modifier = Modifier.padding(top = 2.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onExecute,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF4F7CFF))
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("执行", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF666666))
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("编辑", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE53935))
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("删除", fontSize = 12.sp)
                }
            }
        }
    }
}
