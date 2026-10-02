package com.badmintonspeed.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.badmintonspeed.app.domain.AnalysisRecord
import com.badmintonspeed.app.domain.SpeedUnit
import com.badmintonspeed.app.ui.formatSpeed
import com.badmintonspeed.app.ui.theme.OnSurface
import com.badmintonspeed.app.ui.theme.OnSurfaceVariant
import com.badmintonspeed.app.ui.theme.SpeedColors

@Composable
fun RecordRow(
    record: AnalysisRecord,
    unit: SpeedUnit,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(record.title, color = OnSurface, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${record.totalHits} 次击球 · ${record.smashCount} 次杀球",
                    color = OnSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(
                formatSpeed(record.maxSpeedKmh, unit),
                color = SpeedColors.forSpeed(record.maxSpeedKmh),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
            Text(unit.displayName, color = OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除", tint = Color(0xFFEF4444))
            }
        }
    }
}
