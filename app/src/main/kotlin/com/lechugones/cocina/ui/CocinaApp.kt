package com.lechugones.cocina.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.lechugones.cocina.feature.cookable.CookableScreen
import com.lechugones.cocina.feature.importer.ImportScreen
import com.lechugones.cocina.feature.pantry.PantryScreen
import com.lechugones.cocina.feature.recipes.RecipesScreen
import com.lechugones.cocina.feature.recipes.detail.RecipeDetailScreen
import com.lechugones.cocina.feature.recipes.editor.RecipeEditorScreen
import com.lechugones.cocina.feature.settings.SettingsScreen
import com.lechugones.cocina.feature.settings.update.AppLinks
import com.lechugones.cocina.feature.shopping.ShoppingScreen
import com.lechugones.cocina.ui.components.LocalSupermarketNames
import com.lechugones.cocina.ui.navigation.CatalogueRoute
import com.lechugones.cocina.ui.navigation.CookableRoute
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
    shell: ShellViewModel = hiltViewModel(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val topLevel = TopLevelDestination.entries.firstOrNull { currentDestination.isOn(it) }

    // Shares and "open with" from other apps land on the import screen.
    LaunchedEffect(pendingImport) {
        pendingImport.filterNotNull().collect { navController.navigate(ImportRoute) }
    }

    val launchUpdate by shell.launchUpdate.collectAsStateWithLifecycle()
    launchUpdate?.let { UpdateDialog(it, onInstall = shell::installUpdate, onDismiss = shell::dismissUpdate) }

    CompositionLocalProvider(LocalSupermarketNames provides shell.supermarketNames) {
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
                        onCookable = { navController.navigate(CookableRoute) },
                        onOpenSettings = openSettings,
                    )
                }
                composable<CookableRoute> {
                    CookableScreen(
                        onBack = { navController.popBackStack() },
                        onOpenRecipe = { navController.navigate(RecipeDetailRoute(it)) },
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
                        // Pop back to the list. navigateToTopLevel would restore the saved stack, which
                        // contains this import screen, and appear to do nothing.
                        onDone = {
                            if (!navController.popBackStack<RecipesRoute>(inclusive = false)) navController.navigate(RecipesRoute)
                        },
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

@Composable
private fun UpdateDialog(
    state: LaunchUpdate,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { if (!state.downloading) onDismiss() },
        title = { Text(stringResource(R.string.update_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(
                        if (state.canInstall) R.string.update_dialog_body else R.string.update_dialog_body_debug,
                        state.update.version,
                    ),
                )
                if (state.downloading) {
                    Text(stringResource(R.string.settings_updates_downloading))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (state.failed) Text(stringResource(R.string.settings_updates_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            if (state.canInstall) {
                TextButton(onClick = onInstall, enabled = !state.downloading) { Text(stringResource(R.string.update_dialog_install)) }
            } else {
                TextButton(onClick = {
                    onDismiss()
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, AppLinks.RELEASES_URL.toUri()))
                    } catch (e: ActivityNotFoundException) {
                        // No browser installed: nothing to open.
                    }
                }) { Text(stringResource(R.string.settings_updates_open_github)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.downloading) { Text(stringResource(R.string.update_dialog_later)) }
        },
    )
}
