package com.lechugones.cocina.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lechugones.cocina.R
import com.lechugones.cocina.feature.catalogue.CatalogueScreen
import com.lechugones.cocina.feature.pantry.PantryScreen
import com.lechugones.cocina.feature.recipes.RecipesScreen
import com.lechugones.cocina.feature.settings.SettingsScreen
import com.lechugones.cocina.feature.shopping.ShoppingScreen
import com.lechugones.cocina.ui.navigation.CatalogueRoute
import com.lechugones.cocina.ui.navigation.PantryRoute
import com.lechugones.cocina.ui.navigation.RecipesRoute
import com.lechugones.cocina.ui.navigation.SettingsRoute
import com.lechugones.cocina.ui.navigation.ShoppingRoute
import com.lechugones.cocina.ui.navigation.TopLevelDestination

/** App shell: top bar, bottom navigation and the navigation graph. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CocinaApp(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val topLevel = TopLevelDestination.entries.firstOrNull { currentDestination.isOn(it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(topLevel?.label ?: R.string.settings_title)) },
                navigationIcon = {
                    if (topLevel == null && navController.previousBackStackEntry != null) {
                        IconButton(onClick = { navController.navigateUp() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                },
                actions = {
                    if (topLevel != null) {
                        IconButton(onClick = { navController.navigate(SettingsRoute) { launchSingleTop = true } }) {
                            Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.action_open_settings))
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (topLevel != null) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { destination ->
                        val selected = destination == topLevel
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateToTopLevel(destination) },
                            icon = {
                                Icon(
                                    if (selected) destination.selectedIcon else destination.unselectedIcon,
                                    contentDescription = null,
                                )
                            },
                            label = { Text(stringResource(destination.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = RecipesRoute,
            modifier = Modifier.padding(padding),
        ) {
            composable<RecipesRoute> { RecipesScreen() }
            composable<PantryRoute> { PantryScreen() }
            composable<ShoppingRoute> { ShoppingScreen() }
            composable<CatalogueRoute> { CatalogueScreen() }
            composable<SettingsRoute> { SettingsScreen() }
        }
    }
}

private fun NavDestination?.isOn(destination: TopLevelDestination): Boolean =
    this?.hierarchy?.any { it.hasRoute(destination.routeClass) } == true

private fun NavHostController.navigateToTopLevel(destination: TopLevelDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
