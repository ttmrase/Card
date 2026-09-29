package com.cardforge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardforge.model.CardDef
import com.cardforge.model.CardKind
import com.cardforge.model.Library
import com.cardforge.ui.theme.*

@Composable
fun HomeScreen(
    library: Library,
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
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            HomeHero(library)

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

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("カード", library.cards.count { !it.isToken }, Modifier.weight(1f))
                StatTile("デッキ", library.decks.size, Modifier.weight(1f))
                StatTile("カテゴリ", library.master.categories.size, Modifier.weight(1f))
            }

            DuelButton(onClick = onStartDuel)
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

/** 自分のカードを3枚、扇に広げて見せる。 */
@Composable
private fun HomeHero(library: Library) {
    val picks: List<CardDef> = remember(library.cards) {
        val cards = library.cards.filter { !it.isToken }
        listOfNotNull(
            cards.filter { it.kind == CardKind.SPELL }.firstOrNull(),
            cards.filter { it.kind == CardKind.MONSTER }.maxByOrNull { it.atk },
            cards.filter { it.kind == CardKind.TRAP }.firstOrNull()
        ).ifEmpty { cards.take(3) }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(200.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(Surface2, Ink))),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height * 0.55f)
            drawCircle(
                Brush.radialGradient(listOf(Accent.copy(alpha = 0.35f), Color.Transparent), center, size.height * 0.8f),
                size.height * 0.8f,
                center
            )
            drawCircle(Gold.copy(alpha = 0.18f), size.height * 0.42f, center, style = Stroke(2f))
            drawCircle(Accent.copy(alpha = 0.12f), size.height * 0.6f, center, style = Stroke(1.5f))
        }
        if (picks.isEmpty()) {
            Text("まだカードがありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        picks.forEachIndexed { i, card ->
            val middle = i == picks.size / 2 && picks.size == 3
            val side = i - (picks.size - 1) / 2f
            CardFace(
                card,
                Modifier
                    .size(width = 92.dp, height = 128.dp)
                    .graphicsLayer {
                        rotationZ = side * 13f
                        translationX = side * 70.dp.toPx()
                        translationY = if (middle) -6.dp.toPx() else 10.dp.toPx()
                        val s = if (middle) 1.12f else 1f
                        scaleX = s
                        scaleY = s
                        shadowElevation = 18f
                    }
            )
        }
    }
}

@Composable
private fun StatTile(label: String, value: Int, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value.toString(), fontSize = 22.sp, fontWeight = FontWeight.Black, color = Gold)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** いちばん目立たせる「デュエルを始める」。 */
@Composable
private fun DuelButton(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.horizontalGradient(listOf(Accent.darken(0.25f), TrapColor)))
            .border(1.dp, Gold.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(Icons.Default.Bolt, contentDescription = null, tint = Gold, modifier = Modifier.size(30.dp))
        Column(Modifier.weight(1f)) {
            Text("デュエルを始める", fontSize = 18.sp, fontWeight = FontWeight.Black, color = Color.White)
            Text(
                "ライフ8000。作ったデッキで対戦する。",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
private fun MenuButton(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = Surface1)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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
