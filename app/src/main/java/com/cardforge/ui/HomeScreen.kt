package com.cardforge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cardforge.ui.theme.Gold
import com.cardforge.ui.theme.Surface1

@Composable
fun HomeScreen(
    onOpenCards: () -> Unit,
    onOpenDecks: () -> Unit,
    onOpenMaster: () -> Unit,
    onStartDuel: () -> Unit
) {
    ScreenScaffold(title = "カードフォージ") { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                "自分だけのカードを作って戦う",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                "属性・種族・カテゴリから効果テキストまで、全てを自由に作成できます。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(6.dp))

            MenuButton(
                icon = Icons.Default.Bolt,
                title = "デュエルを始める",
                subtitle = "ライフ8000。作ったデッキで対戦する。",
                highlighted = true,
                onClick = onStartDuel
            )
            MenuButton(
                icon = Icons.Default.Style,
                title = "カードを作る",
                subtitle = "モンスター・魔法・罠を作成／編集する。",
                onClick = onOpenCards
            )
            MenuButton(
                icon = Icons.Default.Layers,
                title = "デッキを組む",
                subtitle = "作ったカードからデッキを構築する。",
                onClick = onOpenDecks
            )
            MenuButton(
                icon = Icons.Default.Tune,
                title = "属性・種族・カテゴリ",
                subtitle = "カードの構成要素そのものを編集する。",
                onClick = onOpenMaster
            )
        }
    }
}

@Composable
private fun MenuButton(
    icon: ImageVector,
    title: String,
    subtitle: String,
    highlighted: Boolean = false,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            else Surface1
        )
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (highlighted) Gold else MaterialTheme.colorScheme.primary
            )
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
