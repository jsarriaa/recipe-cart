package com.lechugones.cocina.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.RestaurantMenu
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material.icons.outlined.RestaurantMenu
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.ui.graphics.vector.ImageVector
import com.lechugones.cocina.R
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

@Serializable
data object RecipesRoute

@Serializable
data object PantryRoute

@Serializable
data object ShoppingRoute

@Serializable
data object CatalogueRoute

@Serializable
data object SettingsRoute

@Serializable
data class RecipeDetailRoute(
    val id: String,
)

/** [id] null creates a new recipe. */
@Serializable
data class RecipeEditorRoute(
    val id: String? = null,
)

@Serializable
data object ImportRoute

/** "¿Qué puedo cocinar?" (SPEC § 4.5). */
@Serializable
data object CookableRoute

/** "Recetas de la comunidad" (SPEC § 4.10). */
@Serializable
data object CommunityRoute

/** The four bottom-bar destinations (SPEC § 4). Settings is reached from the top bar. */
enum class TopLevelDestination(
    val route: Any,
    val routeClass: KClass<*>,
    @param:StringRes val label: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    RECIPES(RecipesRoute, RecipesRoute::class, R.string.nav_recipes, Icons.Filled.RestaurantMenu, Icons.Outlined.RestaurantMenu),
    PANTRY(PantryRoute, PantryRoute::class, R.string.nav_pantry, Icons.Filled.Kitchen, Icons.Outlined.Kitchen),
    SHOPPING(ShoppingRoute, ShoppingRoute::class, R.string.nav_shopping, Icons.Filled.ShoppingCart, Icons.Outlined.ShoppingCart),
    CATALOGUE(CatalogueRoute, CatalogueRoute::class, R.string.nav_catalogue, Icons.Filled.Storefront, Icons.Outlined.Storefront),
}
