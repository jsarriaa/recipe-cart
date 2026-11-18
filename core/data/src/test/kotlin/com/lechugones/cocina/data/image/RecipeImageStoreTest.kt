package com.lechugones.cocina.data.image

import org.junit.Test
import kotlin.test.assertEquals

/** Size maths of photo downscaling (decoding itself is checked on the device). */
class RecipeImageStoreTest {
    @Test
    fun `sample size keeps at least 1600 px on the long side`() {
        assertEquals(1, RecipeImageStore.sampleSize(1600, 1200))
        assertEquals(1, RecipeImageStore.sampleSize(3199, 2000))
        assertEquals(2, RecipeImageStore.sampleSize(3200, 2400))
        assertEquals(2, RecipeImageStore.sampleSize(4000, 3000))
        assertEquals(4, RecipeImageStore.sampleSize(3000, 8000))
    }

    @Test
    fun `scale factor brings the long side to 1600 px`() {
        assertEquals(1f, RecipeImageStore.scaleFactor(1200, 900))
        assertEquals(0.8f, RecipeImageStore.scaleFactor(2000, 1500))
        assertEquals(0.5f, RecipeImageStore.scaleFactor(1000, 3200))
    }
}
