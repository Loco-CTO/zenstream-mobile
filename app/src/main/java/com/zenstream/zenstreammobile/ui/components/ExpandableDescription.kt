package com.zenstream.zenstreammobile.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import com.zenstream.zenstreammobile.R

private const val COLLAPSED_DESCRIPTION_LINES = 3

@Composable
internal fun ExpandableDescription(
    description: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    var expanded by remember(description) { mutableStateOf(false) }
    var canExpand by remember(description) { mutableStateOf(false) }

    Column(modifier.fillMaxWidth()) {
        Text(
            text = description,
            modifier = Modifier.fillMaxWidth(),
            maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_DESCRIPTION_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result ->
                if (!expanded) canExpand = result.hasVisualOverflow
            },
            style = style,
            color = color,
        )
        if (canExpand || expanded) {
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(horizontal = 0.dp),
            ) {
                Icon(
                    painter =
                        painterResource(
                            if (expanded) LucideR.drawable.lucide_ic_chevron_up
                            else LucideR.drawable.lucide_ic_chevron_down
                        ),
                    contentDescription = null,
                )
                Spacer(Modifier.width(4.dp))
                Text(stringResource(if (expanded) R.string.show_less else R.string.show_more))
            }
        }
    }
}
