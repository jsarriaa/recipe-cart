package com.lechugones.cocina.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.lechugones.cocina.feature.catalogue.CatalogueScreen
import com.lechugones.cocina.feature.importer.ImportScreen
import com.lechugones.cocina.feature.pantry.PantryScreen
import com.lechugones.cocina.feature.recipes.RecipesScreen
import com.lechugones.cocina.feature.recipes.detail.RecipeDetailScreen
import com.lechugones.cocina.feature.recipes.editor.RecipeEditorScreen
import com.lechugones.cocina.feature.settings.SettingsScreen
import com.lechugones.cocina.feature.shopping.ShoppingScreen
import com.lechugones.cocina.ui.navigation.CatalogueRoute
import com.lechugones.cocina.ui.navigation.ImportRoute
import com.lechugones.cocina.ui.navigation.PantryRoute
import com.lechugones.cocina.ui.navigation.RecipeDetailRoute
import com.lechugones.cocina.ui.navigation.RecipeEditorRoute
import com.lechugones.cocina.ui.navigation.RecipesRoute
import com.lechugones.cocina.ui.navigation.SettingsRoute
import com.lechugones.cocina.ui.navigation.ShoppingRoute
import com.lechugones.cocina.ui.navigation.TopLevelDestination
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull

/**
 * App shell: bottom navigation and the navigation graph. Every screen draws its own top bar
 * through [com.lechugones.cocina.ui.components.ScreenScaffold].
 */
@Composable
fun CocinaApp(
    pendingImport: StateFlow<ImportSource?>,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val topLevel = TopLevelDestination.entries.firstOrNull { currentDestination.isOn(it) }

    // Shares and "open with" from other apps land on the import screen.
    LaunchedEffect(pendingImport) {
        pendingImport.filterNotNull().collect { navController.navigate(ImportRoute) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
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
        val openSettings = { navController.navigate(SettingsRoute) { launchSingleTop = true } }
        NavHost(
            navController = navController,
            startDestination = RecipesRoute,
            modifier = Modifier.padding(padding),
        ) {
            composable<RecipesRoute> {
                RecipesScreen(
                    onOpenRecipe = { navController.navigate(RecipeDetailRoute(it)) },
                    onNewRecipe = { navController.navigate(RecipeEditorRoute()) },
                    onImport = { navController.navigate(ImportRoute) },
                    onOpenSettings = openSettings,
                )
            }
            composable<RecipeDetailRoute> {
                RecipeDetailScreen(
                    onBack = { navController.popBackStack() },
                    onEdit = { navController.navigate(RecipeEditorRoute(it)) },
                    onOpenRecipe = { id ->
                        navController.navigate(RecipeDetailRoute(id)) {
                            popUpTo<RecipeDetailRoute> { inclusive = true }
                        }
                    },
                )
            }
            composable<RecipeEditorRoute> {
                RecipeEditorScreen(
                    onClose = { navController.popBackStack() },
                    onSavedNew = { id ->
                        navController.navigate(RecipeDetailRoute(id)) {
                            popUpTo<RecipeEditorRoute> { inclusive = true }
                        }
                    },
                )
            }
            composable<ImportRoute> {
                ImportScreen(
                    onBack = { navController.popBackStack() },
                    onDone = { navController.navigateToTopLevel(TopLevelDestination.RECIPES) },
                    onOpenSettings = openSettings,
                )
            }
            composable<PantryRoute> { PantryScreen(onOpenSettings = openSettings) }
            composable<ShoppingRoute> { ShoppingScreen(onOpenSettings = openSettings) }
            composable<CatalogueRoute> { CatalogueScreen(onOpenSettings = openSettings) }
            composable<SettingsRoute> { SettingsScreen(onBack = { navController.popBackStack() }) }
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
