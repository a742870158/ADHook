package com.adhook.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.adhook.ui.components.*
import com.adhook.ui.data.*

/**
 * Tab 0: 全局规则页面
 */
@Composable
fun GlobalRulesPage(
    paddingValues: PaddingValues
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(4.dp))
            AdSectionHeader(
                title = "全局执行策略",
                trailingText = "联盟覆盖"
            )
            Spacer(modifier = Modifier.height(8.dp))
            GlobalPolicyCard()
        }

        item {
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * 全局策略卡片：底层通用 SDK 拦截的联盟覆盖列表。
 */
@Composable
fun GlobalPolicyCard() {
    AdCard {
        val sdks = listOf(
            "穿山甲联盟 (Pangle)" to "字节跳动全系开屏、信息流及激励广告",
            "腾讯优量汇 (GDT / 广点通)" to "腾讯联盟广告请求切断并安全快速回调",
            "快手联盟 SDK (Kwad)" to "快手开屏与短视频信息流推广",
            "百度移动广告 (Baidu MobAds)" to "百度联盟广告加载",
            "谷歌移动广告 (Google AdMob)" to "AdMob 插屏与展示位",
            "Sigmob 移动广告 (WindAds)" to "Sigmob 开屏、插屏、激励与原生广告",
            "TopOn 聚合广告 (AnyThink)" to "TopOn / AnyThink 聚合开屏与广告分发",
            "Mintegral (Mobvista / MBridge)" to "Mintegral 开屏、横幅及激励广告",
            "小米 Mimo 广告平台" to "小米商业化插屏与激励广告加载"
        )
        sdks.forEachIndexed { index, (name, description) ->
            if (index > 0) HorizontalDivider(
                modifier = Modifier.padding(vertical = 2.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AdBadge(
                    text = "自动",
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
