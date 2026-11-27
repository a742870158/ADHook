package com.adhook.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adhook.config.model.CustomRule
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun SwipeToRevealRuleItem(
    rule: CustomRule,
    onCheckedChange: (Boolean) -> Unit,
    onDelete: () -> Unit,
    hitCount: Int = 0
) {
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val actionWidthDp = 76.dp
    val actionWidthPx = with(density) { actionWidthDp.toPx() }
    val offsetX = remember { Animatable(0f) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
    ) {
        // 底层：隐藏的红色直接删除按钮区域（向左滑动露出）
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.error)
                .clickable {
                    onDelete()
                },
            contentAlignment = Alignment.CenterEnd
        ) {
            Row(
                modifier = Modifier
                    .width(actionWidthDp)
                    .fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "删除",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "删除",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // 上层：规则内容主卡片，跟随手势位移
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            coroutineScope.launch {
                                // 若向左滑动超过一半，吸附到完全展开；否则平滑回弹收起
                                if (offsetX.value < -actionWidthPx / 2) {
                                    offsetX.animateTo(-actionWidthPx)
                                } else {
                                    offsetX.animateTo(0f)
                                }
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            coroutineScope.launch {
                                val newOffset = (offsetX.value + dragAmount).coerceIn(-actionWidthPx, 0f)
                                offsetX.snapTo(newOffset)
                            }
                        }
                    )
                }
                .clickable(
                    enabled = offsetX.value < 0f,
                    onClick = {
                        coroutineScope.launch { offsetX.animateTo(0f) }
                    }
                ),
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
