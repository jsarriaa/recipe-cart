package com.lechugones.cocina.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestaurantMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lechugones.cocina.data.image.RecipeImageStore
import java.io.File

/**
 * A recipe photo from app storage ([path] is the stored file name), or [bytes] from a pack
 * being previewed. Shows a neutral placeholder when there is neither.
 */
@Composable
fun RecipePhoto(
    path: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    bytes: ByteArray? = null,
    placeholderIconSize: Dp = 32.dp,
) {
    val context = LocalContext.current
    val model: Any? =
        remember(path, bytes) {
            bytes ?: path?.let { File(File(context.filesDir, RecipeImageStore.DIRECTORY), it) }
        }
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (model == null) {
            Icon(
                Icons.Outlined.RestaurantMenu,
                contentDescription = null,
                modifier = Modifier.size(placeholderIconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            AsyncImage(
                model = model,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
