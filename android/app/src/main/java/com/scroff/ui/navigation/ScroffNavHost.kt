package com.scroff.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.scroff.data.model.Schedule
import com.scroff.ui.screen.DebugLogScreen
import com.scroff.ui.screen.EditScheduleScreen
import com.scroff.ui.screen.HomeScreen

object Routes {
    const val HOME = "home"
    const val ADD = "add"
    const val EDIT = "edit/{scheduleId}"
    const val DEBUG_LOG = "debug_log"
}

@Composable
fun ScroffNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onNavigateToAdd = { navController.navigate(Routes.ADD) },
                onNavigateToEdit = { schedule ->
                    navController.navigate("edit/${schedule.id}")
                },
                onNavigateToDebugLog = { navController.navigate(Routes.DEBUG_LOG) }
            )
        }
        composable(Routes.DEBUG_LOG) {
            DebugLogScreen(
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.ADD) {
            EditScheduleScreen(
                editingSchedule = null,
                onSaved = { navController.popBackStack() },
                onCancel = { navController.popBackStack() }
            )
        }
        composable(
            route = Routes.EDIT,
            arguments = listOf(navArgument("scheduleId") { type = NavType.LongType })
        ) { backStackEntry ->
            val scheduleId = backStackEntry.arguments?.getLong("scheduleId") ?: -1L
            // 这里需要从 ViewModel 拿到 schedule，简单做法：通过 id 重新查询
            // 由于已有列表里有该 schedule，这里使用 placeholder 让 EditScheduleScreen 通过 VM 内部查询
            // 实际上 EditScheduleScreen 接收 editingSchedule，我们需要从全局状态获取
            // 简化处理：通过 ViewModelFactory 创建 VM 并查询
            val context = androidx.compose.ui.platform.LocalContext.current
            val viewModel: com.scroff.ui.ScheduleViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                factory = com.scroff.ui.ScheduleViewModel.Factory(context.applicationContext as android.app.Application)
            )
            val schedules by viewModel.schedules.collectAsState()
            val schedule = schedules.find { it.id == scheduleId }
            if (schedule != null) {
                EditScheduleScreen(
                    editingSchedule = schedule,
                    onSaved = { navController.popBackStack() },
                    onCancel = { navController.popBackStack() }
                )
            } else {
                // 还未加载完成时显示空状态
                androidx.compose.material3.Text("加载中...")
            }
        }
    }
}
