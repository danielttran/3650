package com.Bible3650.www

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
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
                topBar = {
                    if (currentRoute == AppDestinations.HOME.route) {
                        CenterAlignedTopAppBar(
                            title = {
                                Text("AUDIO BIBLE",
                                     style = MaterialTheme.typography.titleMedium,
                                     fontWeight = FontWeight.ExtraBold)
                            },
                            navigationIcon = {
                                IconButton(onClick = { }) {
                                    Icon(painterResource(R.drawable.ic_home),
                                         contentDescription = null,
                                         tint = MaterialTheme.colorScheme.tertiary)
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                        )
                    }
                }
            ) { innerPadding ->
                NavHost(
                    navController = navController,
                    startDestination = AppDestinations.HOME.route,
                    modifier = Modifier.padding(innerPadding)
                ) {
                    composable(AppDestinations.HOME.route) {
                        HomeScreen(viewModel = dashboardViewModel)
                    }

                    composable(AppDestinations.LISTS.route) {
                        val listsViewModel: ManageListsViewModel = hiltViewModel()
                        val sourceViewModel: SourceManagerViewModel = hiltViewModel()
                        ManageListsScreen(
                            viewModel       = listsViewModel,
                            sourceViewModel = sourceViewModel,
                            onBackClick     = { navController.navigateUp() },
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

enum class AppDestinations(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Home", Icons.Default.Home),
    LISTS("lists", "Lists", Icons.AutoMirrored.Filled.List),
    PROFILE("profile", "Profile", Icons.Default.Person),
}
