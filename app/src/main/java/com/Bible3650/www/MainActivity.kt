package com.Bible3650.www

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import com.Bible3650.www.ui.HomeScreen
import com.Bible3650.www.ui.ManageListsScreen
import com.Bible3650.www.ui.theme.Bible3650Theme
import androidx.lifecycle.viewmodel.compose.viewModel
import com.Bible3650.www.ui.DashboardViewModel
import com.Bible3650.www.ui.DashboardAction

import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.AndroidEntryPoint

import androidx.activity.viewModels

import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Person

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: DashboardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Bible3650App(viewModel = viewModel)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@PreviewScreenSizes
@Composable
fun Bible3650App(
    viewModel: DashboardViewModel
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: AppDestinations.HOME.route

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestinations.entries.forEach { dest ->
                item(
                    icon = {
                        Icon(
                            imageVector = dest.icon,
                            contentDescription = dest.label
                        )
                    },
                    label = { Text(dest.label) },
                    selected = dest.route == currentRoute,
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
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                if (currentRoute == AppDestinations.HOME.route) {
                    CenterAlignedTopAppBar(
                        title = {
                            Text(
                                "AUDIO BIBLE",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { /* TODO */ }) {
                                Icon(
                                    painterResource(R.drawable.ic_home), // Placeholder for menu
                                    contentDescription = "Menu",
                                    tint = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        },
                        actions = {
                            if (currentRoute == AppDestinations.LISTS.route) {
                                IconButton(onClick = { /* TODO */ }) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = "Search"
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent
                        )
                    )
                }
            },
            floatingActionButton = {}
        ) { innerPadding ->
            NavHost(
                navController = navController, 
                startDestination = AppDestinations.HOME.route,
                modifier = Modifier.padding(innerPadding)
            ) {
                composable(AppDestinations.HOME.route) {
                    HomeScreen(viewModel = viewModel)
                }
                composable(AppDestinations.LISTS.route) {
                    val listsViewModel: com.Bible3650.www.ui.ManageListsViewModel = hiltViewModel()
                    ManageListsScreen(
                        viewModel = listsViewModel,
                        onBackClick = { navController.navigateUp() }
                    )
                }
                composable(AppDestinations.PROFILE.route) {
                    val profileViewModel: com.Bible3650.www.ui.ProfileViewModel = hiltViewModel()
                    com.Bible3650.www.ui.ProfileScreen(
                        viewModel = profileViewModel,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}


enum class AppDestinations(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    HOME("home", "Home", Icons.Default.Home),
    LISTS("lists", "Lists", Icons.AutoMirrored.Filled.List),
    PROFILE("profile", "Profile", Icons.Default.Person),
}
