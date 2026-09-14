package com.siftalpha.studio.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.siftalpha.studio.ui.theme.StudioThemeTokens

enum class StudioStatusTone {
    Neutral,
    Success,
    Warning,
    Error,
}

@Composable
fun StudioSectionCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = StudioThemeTokens.spacing
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(spacing.large),
            content = content,
        )
    }
}

@Composable
fun StudioPrimaryAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(text = label)
    }
}

@Composable
fun StudioStatusBadge(
    label: String,
    tone: StudioStatusTone,
    modifier: Modifier = Modifier,
) {
    val status = StudioThemeTokens.status
    val (container, content) = when (tone) {
        StudioStatusTone.Neutral -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        StudioStatusTone.Success -> status.successContainer to status.onSuccess
        StudioStatusTone.Warning -> status.warningContainer to status.onWarning
        StudioStatusTone.Error -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    StatusSurface(label = label, container = container, content = content, modifier = modifier)
}

@Composable
private fun StatusSurface(
    label: String,
    container: Color,
    content: Color,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = container,
        contentColor = content,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
