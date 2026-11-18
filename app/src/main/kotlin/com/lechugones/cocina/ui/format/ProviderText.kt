package com.lechugones.cocina.ui.format

import android.content.res.Resources
import com.lechugones.cocina.R
import com.lechugones.cocina.provider.ProviderError

/** What went wrong talking to [providerName], for a snackbar. */
fun providerErrorMessage(
    resources: Resources,
    error: ProviderError,
    providerName: String,
    postalCode: String?,
): String =
    when (error) {
        ProviderError.Offline -> resources.getString(R.string.provider_error_offline, providerName)
        ProviderError.Blocked -> resources.getString(R.string.provider_error_blocked, providerName)
        is ProviderError.RateLimited -> resources.getString(R.string.provider_error_rate_limited, providerName)
        is ProviderError.Http -> resources.getString(R.string.provider_error_http, providerName, error.code)
        is ProviderError.Parse -> resources.getString(R.string.provider_error_parse, providerName)
        ProviderError.NotServed -> resources.getString(R.string.provider_error_not_served, providerName, postalCode.orEmpty())
    }
