package com.zenstream.zenstreammobile.ui.locale

import android.content.res.Configuration
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

@Composable
fun ZenStreamLocale(locale: String, content: @Composable () -> Unit) {
    val baseContext = LocalContext.current
    // createConfigurationContext does not retain an Activity in its Context chain.
    // Preserve the owners before replacing LocalContext so permission/picker launchers still work.
    val activity = LocalActivity.current
    val registryOwner = LocalActivityResultRegistryOwner.current
    val owners =
        if (registryOwner != null)
            arrayOf(
                LocalActivity provides activity,
                LocalActivityResultRegistryOwner provides registryOwner,
            )
        else arrayOf(LocalActivity provides activity)
    val baseConfiguration = LocalConfiguration.current
    val configuration =
        remember(baseConfiguration, locale) {
            Configuration(baseConfiguration).apply {
                setLocale(Locale.forLanguageTag(locale))
            }
        }
    val localizedContext =
        remember(baseContext, configuration) {
            baseContext.createConfigurationContext(configuration)
        }
    CompositionLocalProvider(
        *owners,
        LocalConfiguration provides configuration,
        LocalContext provides localizedContext,
        content = content,
    )
}
