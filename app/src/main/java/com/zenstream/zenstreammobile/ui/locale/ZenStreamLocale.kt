package com.zenstream.zenstreammobile.ui.locale

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

@Composable
fun ZenStreamLocale(locale: String, content: @Composable () -> Unit) {
    val baseContext = LocalContext.current
    val baseConfiguration = LocalConfiguration.current
    val configuration = remember(baseConfiguration, locale) {
        Configuration(baseConfiguration).apply {
            setLocale(Locale.forLanguageTag(locale))
        }
    }
    val localizedContext = remember(baseContext, configuration) {
        baseContext.createConfigurationContext(configuration)
    }
    CompositionLocalProvider(
        LocalConfiguration provides configuration,
        LocalContext provides localizedContext,
        content = content,
    )
}
