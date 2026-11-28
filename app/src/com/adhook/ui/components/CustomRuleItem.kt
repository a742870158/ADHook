package com.adhook.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adhook.config.model.CustomRule

@Composable
fun SwipeToRevealRuleItem(
    rule: CustomRule,
    onCheckedChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
    hitCount: Int = 0
) {
    SwipeToRevealContainer(
        onDelete = onDelete,
        shape = RoundedCornerShape(12.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            shadowElevation = 0.5.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = rule.name?.takeIf { it.isNotBlank() } ?: "自定义规则",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        AdBadge(
                            text = rule.type ?: "res_id",
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            textColor = MaterialTheme.colorScheme.primary
                        )
                        if (hitCount > 0) {
                            Spacer(modifier = Modifier.width(4.dp))
                            AdBadge(
                                text = "已拦截 $hitCount",
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                textColor = MaterialTheme.colorScheme.secondary
                            )
                        }
                        if (rule.parentDepth != 0) {
                            Spacer(modifier = Modifier.width(4.dp))
                            AdBadge(
                                text = if (rule.parentDepth == com.adhook.config.model.CustomRule.PARENT_DEPTH_AUTO_ITEM) "整行条目" else "+${rule.parentDepth}层",
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                textColor = MaterialTheme.colorScheme.secondary
                            )
                        }
                        if (rule.maxTextLength > 0 && com.adhook.config.model.CustomRule.TYPE_TEXT == rule.type) {
                            Spacer(modifier = Modifier.width(4.dp))
                            AdBadge(
                                text = "≤${rule.maxTextLength}字",
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                textColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    val targetDesc = buildString {
                        append(rule.targetValue ?: "")
                        if (!rule.activityFilter.isNullOrBlank()) {
                            append(" · 限定 ")
                            append(rule.activityFilter.substringAfterLast('.'))
                        }
                    }
                    Text(
                        text = targetDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Switch(
                    checked = rule.isEnabled,
                    onCheckedChange = onCheckedChange
                )
            }
        }
    }
}
