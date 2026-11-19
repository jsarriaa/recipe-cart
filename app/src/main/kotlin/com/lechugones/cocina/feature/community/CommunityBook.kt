package com.lechugones.cocina.feature.community

import com.lechugones.cocina.domain.community.CommunityRecipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject

/** The community recipe book: a public GitHub repository of `cpl-recipe/1` files (docs/COMMUNITY.md). */
object CommunityLinks {
    const val REPO = "jsarriaa/recetario"
    const val REPO_URL = "https://github.com/$REPO"
    const val RAW_BASE = "https://raw.githubusercontent.com/$REPO/main/"
    const val INDEX_URL = RAW_BASE + "index.json"

    /**
     * The owner's Cloudflare Worker that commits published recipes (community/worker/). Empty
     * until it is deployed: "Publicar" then says publishing is not available yet.
     */
    const val PUBLISH_URL = ""

    /** Recipe file paths come from the index; anything that could leave the book is refused. */
    private val SAFE_PATH = Regex("^recipes/[A-Za-z0-9._/-]+\\.md$")

    fun recipeUrl(path: String): String? = if (SAFE_PATH.matches(path) && ".." !in path) RAW_BASE + path else null
}

@Serializable
data class CommunityIndexDto(
    val format: String,
    val recipes: List<CommunityRecipeDto> = emptyList(),
)

@Serializable
data class CommunityRecipeDto(
    val path: String,
    val title: String,
    val servings: Int,
    val uid: String? = null,
    val author: String? = null,
    val description: String? = null,
    @SerialName("total_minutes") val totalMinutes: Int? = null,
    val tags: List<String> = emptyList(),
    val ingredients: List<String> = emptyList(),
) {
    fun toDomain() = CommunityRecipe(path, title, servings, uid, author, description, totalMinutes, tags, ingredients)
}

@Serializable
data class PublishRequest(
    val recipe: String,
)

enum class PublishResult { Published, RateLimited, Rejected, Failed, NotAvailable }

/** Downloads the book's index and recipe files, only when the user opens the screen or taps. */
class CommunityBook
    @Inject
    constructor(
        private val client: OkHttpClient,
    ) {
        private val json = Json { ignoreUnknownKeys = true }

        /** The recipes in the book, newest first; null when it cannot be read. */
        suspend fun index(): List<CommunityRecipe>? =
            withContext(Dispatchers.IO) {
                try {
                    get(CommunityLinks.INDEX_URL)?.let { body ->
                        val index = json.decodeFromString(CommunityIndexDto.serializer(), body)
                        if (index.format == INDEX_FORMAT) index.recipes.map { it.toDomain() } else null
                    }
                } catch (e: IOException) {
                    null
                } catch (e: SerializationException) {
                    null
                } catch (e: IllegalArgumentException) {
                    null
                }
            }

        /** The recipe file's text, ready for the normal import; null when it cannot be downloaded. */
        suspend fun recipeText(recipe: CommunityRecipe): String? {
            val url = CommunityLinks.recipeUrl(recipe.path) ?: return null
            return withContext(Dispatchers.IO) {
                try {
                    get(url)?.takeIf { it.length <= MAX_RECIPE_CHARS }
                } catch (e: IOException) {
                    null
                }
            }
        }

        /** Sends [text] to the publishing Worker (docs/COMMUNITY.md, phase B). */
        suspend fun publish(text: String): PublishResult {
            if (CommunityLinks.PUBLISH_URL.isBlank()) return PublishResult.NotAvailable
            return withContext(Dispatchers.IO) {
                try {
                    val body = json.encodeToString(PublishRequest.serializer(), PublishRequest(text))
                    val request =
                        Request
                            .Builder()
                            .url(CommunityLinks.PUBLISH_URL)
                            .post(body.toRequestBody("application/json".toMediaType()))
                            .build()
                    client.newCall(request).execute().use { response ->
                        when {
                            response.isSuccessful -> PublishResult.Published
                            response.code == HTTP_TOO_MANY -> PublishResult.RateLimited
                            response.code == HTTP_UNPROCESSABLE -> PublishResult.Rejected
                            else -> PublishResult.Failed
                        }
                    }
                } catch (e: IOException) {
                    PublishResult.Failed
                } catch (e: IllegalArgumentException) {
                    PublishResult.Failed
                }
            }
        }

        private fun get(url: String): String? =
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (response.isSuccessful) response.body.string() else null
            }

        private companion object {
            const val INDEX_FORMAT = "cpl-recetario/1"
            const val MAX_RECIPE_CHARS = 200_000
            const val HTTP_TOO_MANY = 429
            const val HTTP_UNPROCESSABLE = 422
        }
    }
