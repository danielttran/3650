package com.Bible3650.www

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.Bible3650.www.ui.*
import com.Bible3650.www.ui.theme.Bible3650Theme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val dashboardViewModel: DashboardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Bible3650App(dashboardViewModel)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Bible3650App(dashboardViewModel: DashboardViewModel) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: AppDestinations.HOME.route

    val snackbarHostState = remember { SnackbarHostState() }

    val requestPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }

    val context = androidx.compose.ui.platform.LocalContext.current

    // Request notification permission separately so the event collector below
    // is not blocked waiting for the permission dialog.
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(dashboardViewModel) {
        dashboardViewModel.uiEvents.collect { event ->
            when (event) {
                is DashboardUiEvent.ShowSnackbar -> {
                    snackbarHostState.showSnackbar(
                        message = event.message,
                        duration = SnackbarDuration.Short
                    )
                }
            }
        }
    }

    // Only show the bottom nav on top-level destinations
    val topLevelRoutes = AppDestinations.entries.map { it.route }
    val showBottomNav = topLevelRoutes.any { currentRoute.startsWith(it) }

    Bible3650Theme {
        NavigationSuiteScaffold(
            navigationSuiteItems = {
                if (showBottomNav) {
                    AppDestinations.entries.forEach { dest ->
                        item(
                            icon = { Icon(dest.icon, contentDescription = dest.label) },
                            label = { Text(dest.label) },
                            selected = currentRoute.startsWith(dest.route),
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        ) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                snackbarHost = { SnackbarHost(snackbarHostState) }
            ) { innerPadding ->
                // Center content with a max width so it stays readable on tablets
                // without stretching wall-to-wall on wide screens.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.TopCenter
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = AppDestinations.HOME.route,
                        modifier = Modifier
                            .widthIn(max = 840.dp)
                            .fillMaxHeight()
                    ) {
                        composable(AppDestinations.HOME.route) {
                            HomeScreen(viewModel = dashboardViewModel)
                        }

                        composable(AppDestinations.LISTS.route) {
                            val listsViewModel: ManageListsViewModel = hiltViewModel()
                            val sourceViewModel: SourceManagerViewModel = hiltViewModel()
                            ManageListsScreen(
                                viewModel        = listsViewModel,
                                sourceViewModel  = sourceViewModel,
                                onReviewMappings = { sourceId ->
                                    navController.navigate("review_mappings/$sourceId")
                                }
                            )
                        }

                        composable(
                            route = "review_mappings/{sourceId}",
                            arguments = listOf(navArgument("sourceId") { type = NavType.LongType })
                        ) {
                            ReviewMappingsScreen(
                                onBack = { navController.navigateUp() }
                            )
                        }

                        composable(AppDestinations.PROFILE.route) {
                            val profileViewModel: ProfileViewModel = hiltViewModel()
                            ProfileScreen(viewModel = profileViewModel, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

enum class AppDestinations(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Home", Icons.Default.Home),
    LISTS("lists", "Lists", Icons.AutoMirrored.Filled.List),
    PROFILE("profile", "Profile", Icons.Default.Person),
}
