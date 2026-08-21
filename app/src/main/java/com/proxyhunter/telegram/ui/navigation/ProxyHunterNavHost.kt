package com.proxyhunter.telegram.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.proxyhunter.telegram.ui.screens.addproxy.AddProxyScreen
import com.proxyhunter.telegram.ui.screens.details.ProxyDetailsScreen
import com.proxyhunter.telegram.ui.screens.onboarding.RiskWarningScreen
import com.proxyhunter.telegram.ui.screens.proxylist.ProxyListScreen
import com.proxyhunter.telegram.ui.screens.settings.SettingsScreen

private object Routes {
    const val RISK_WARNING = "risk_warning"
    const val PROXY_LIST = "proxy_list"
    const val PROXY_DETAILS = "proxy_details/{proxyId}"
    const val ADD_PROXY = "add_proxy"
    const val SETTINGS = "settings"

    fun proxyDetails(proxyId: Long) = "proxy_details/$proxyId"
}

// Корневой граф. Начальный экран определяется тем, видел ли пользователь предупреждение
// о рисках (RootViewModel читает флаг из SettingsRepository) — это не просто первый экран
// в списке маршрутов, а обязательный гейт, который нельзя обойти системной кнопкой "назад"
// (RiskWarningScreen не кладётся в back stack повторно после подтверждения).
@Composable
fun ProxyHunterNavHost(
    navController: NavHostController = rememberNavController(),
    rootViewModel: RootViewModel = hiltViewModel(),
) {
    val hasSeenRiskWarning by rootViewModel.hasSeenRiskWarning.collectAsState(initial = null)

    // null — ещё не прочитали значение из DataStore, ничего не рендерим, чтобы не мигнуть
    // основным экраном перед тем, как понять, что предупреждение ещё не показано.
    val startDestination = when (hasSeenRiskWarning) {
        true -> Routes.PROXY_LIST
        false -> Routes.RISK_WARNING
        null -> return
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.RISK_WARNING) {
            RiskWarningScreen(
                onAccept = {
                    rootViewModel.markRiskWarningSeen()
                    navController.navigate(Routes.PROXY_LIST) {
                        popUpTo(Routes.RISK_WARNING) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.PROXY_LIST) {
            ProxyListScreen(
                onOpenDetails = { proxyId -> navController.navigate(Routes.proxyDetails(proxyId)) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onAddProxy = { navController.navigate(Routes.ADD_PROXY) },
            )
        }

        composable(Routes.ADD_PROXY) {
            AddProxyScreen(
                onSaved = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }

        // navArgument с явным типом Long — ключ "proxyId" совпадает с тем, что читает
        // ProxyDetailsViewModel из SavedStateHandle через Hilt-навигационную интеграцию.
        composable(
            route = Routes.PROXY_DETAILS,
            arguments = listOf(navArgument("proxyId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val proxyId = backStackEntry.arguments?.getLong("proxyId") ?: return@composable
            ProxyDetailsScreen(
                proxyId = proxyId,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
