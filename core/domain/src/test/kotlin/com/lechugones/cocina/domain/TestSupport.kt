package com.lechugones.cocina.domain

import java.math.BigDecimal
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Asserts numeric equality regardless of scale (`1.50` equals `1.5`). */
fun assertDecimal(
    expected: String,
    actual: BigDecimal?,
    message: String? = null,
) {
    assertNotNull(actual, message ?: "expected $expected but was null")
    assertTrue(
        BigDecimal(expected).compareTo(actual) == 0,
        (message?.let { "$it: " } ?: "") + "expected <$expected> but was <${actual.toPlainString()}>",
    )
}

/** Asserts that [actual] is within [tolerance] of [expected]. */
fun assertDecimalNear(
    expected: String,
    actual: BigDecimal?,
    tolerance: String = "0.000001",
) {
    assertNotNull(actual)
    val diff = BigDecimal(expected).subtract(actual).abs()
    assertTrue(diff <= BigDecimal(tolerance), "expected <$expected> ± $tolerance but was <${actual.toPlainString()}>")
}
