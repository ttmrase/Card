package com.cardforge.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cardforge.data.LibraryRepository
import com.cardforge.model.*
import androidx.compose.ui.platform.LocalContext
import com.cardforge.text.EffectTextRenderer
import com.cardforge.ui.theme.Danger
import com.cardforge.ui.theme.Gold
import com.cardforge.ui.theme.Surface1

@Composable
fun DeckListScreen(
    repository: LibraryRepository,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onCreate: () -> Unit
) {
    val library = repository.library
    var pendingDelete by remember { mutableStateOf<Deck?>(null) }
    var exporting by remember { mutableStateOf<Deck?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    // デッキと、そのデッキが使っているカードだけを書き出す。
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val deck = exporting
        exporting = null
        if (uri == null || deck == null) return@rememberLauncherForActivityResult
        message = runCatching {
            val text = repository.exportDeck(deck)
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            "「${deck.name}」を書き出しました。"
        }.getOrElse { "書き出しに失敗しました。" }
    }

    ScreenScaffold(
        title = "デッキ",
        onBack = onBack,
        floatingAction = {
            FloatingActionButton(onClick = onCreate) {
                Icon(Icons.Default.Add, contentDescription = "デッキを作成")
            }
        }
    ) { padding ->
        if (library.decks.isEmpty()) {
            Box(Modifier.padding(padding)) {
                EmptyHint("デッキがありません。右下のボタンから作成できます。")
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(library.decks, key = { it.id }) { deck ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEdit(deck.id) },
                        colors = CardDefaults.cardColors(containerColor = Surface1)
                    ) {
                        Row(
                            Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(deck.name, fontWeight = FontWeight.Bold)
                                Text(
                                    "${deck.size}枚" +
                                        if (deck.size < DeckRules.MIN_SIZE) "（${DeckRules.MIN_SIZE}枚以上必要）" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (deck.size < DeckRules.MIN_SIZE) Danger
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = {
                                exporting = deck
                                exportLauncher.launch("cardforge-${deck.name}.json")
                            }) {
                                Icon(Icons.Default.FileDownload, contentDescription = "書き出す")
                            }
                            IconButton(onClick = { pendingDelete = deck }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "削除",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(72.dp)) }
            }
        }
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("お知らせ") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } }
        )
    }

    pendingDelete?.let { deck ->
        ConfirmDialog(
            title = "デッキを削除",
            message = "「${deck.name}」を削除します。",
            confirmLabel = "削除",
            onConfirm = {
                repository.deleteDeck(deck.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
fun DeckEditScreen(
    repository: LibraryRepository,
    deckId: String?,
    onBack: () -> Unit
) {
    val library = repository.library
    val master = library.master
    val original = remember(deckId) { deckId?.let { id -> library.decks.firstOrNull { it.id == id } } }

    var deck by remember {
        mutableStateOf(original ?: Deck(id = newId(), name = "新しいデッキ"))
    }
    var query by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<CardDef?>(null) }

    val counts = deck.cardIds.groupingBy { it }.eachCount()
    // トークンはデッキに入れられない。
    val visible = library.cards.filter {
        !it.isToken && (query.isBlank() || it.name.contains(query, ignoreCase = true))
    }

    fun add(cardId: String) {
        if ((counts[cardId] ?: 0) >= DeckRules.MAX_COPIES) return
        if (deck.size >= DeckRules.MAX_SIZE) return
        deck = deck.copy(cardIds = deck.cardIds + cardId)
    }

    fun remove(cardId: String) {
        val index = deck.cardIds.lastIndexOf(cardId)
        if (index < 0) return
        deck = deck.copy(cardIds = deck.cardIds.toMutableList().also { it.removeAt(index) })
    }

    preview?.let { card ->
        CardPreviewDialog(card = card, master = master, onDismiss = { preview = null })
    }

    ScreenScaffold(
        title = "デッキ編集",
        onBack = onBack,
        actions = {
            IconButton(onClick = {
                repository.upsertDeck(deck)
                onBack()
            }) {
                Icon(Icons.Default.Check, contentDescription = "保存", tint = Gold)
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = deck.name,
                    onValueChange = { deck = deck.copy(name = it) },
                    label = { Text("デッキ名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "${deck.size} / ${DeckRules.MAX_SIZE}枚" +
                        "（${DeckRules.MIN_SIZE}枚以上・同名カードは${DeckRules.MAX_COPIES}枚まで）",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (deck.size < DeckRules.MIN_SIZE) Danger else Gold
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("カードを検索") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (library.cards.isEmpty()) {
                EmptyHint("カードがありません。先にカードを作成してください。")
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(visible, key = { it.id }) { card ->
                        DeckCardRow(
                            card = card,
                            count = counts[card.id] ?: 0,
                            summary = EffectTextRenderer.summary(card, master),
                            onAdd = { add(card.id) },
                            onRemove = { remove(card.id) },
                            onPreview = { preview = card }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeckCardRow(
    card: CardDef,
    count: Int,
    summary: String,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    onPreview: () -> Unit
) {
    Card(
        // タップで1枚追加、長押しで効果を確認できる。
        modifier = Modifier
            .fillMaxWidth()
            .tapOrHold(onClick = onAdd, onLongClick = onPreview),
        colors = CardDefaults.cardColors(containerColor = Surface1)
    ) {
        Row(
            Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CardArt(
                imagePath = card.imagePath,
                kind = card.kind,
                modifier = Modifier.size(width = 38.dp, height = 52.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(card.name, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onRemove, enabled = count > 0) {
                Icon(Icons.Default.Remove, contentDescription = "1枚減らす")
            }
            Text(
                "$count",
                fontWeight = FontWeight.Bold,
                color = if (count > 0) Gold else MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(onClick = onAdd, enabled = count < DeckRules.MAX_COPIES) {
                Icon(Icons.Default.Add, contentDescription = "1枚増やす")
            }
        }
    }
}

@Composable
fun DuelSetupScreen(
    repository: LibraryRepository,
    onBack: () -> Unit,
    onStart: (DuelConfig) -> Unit
) {
    val library = repository.library
    val usable = library.decks.filter { it.size >= DeckRules.MIN_SIZE }

    var deckA by remember { mutableStateOf(usable.firstOrNull()) }
    var deckB by remember { mutableStateOf(usable.firstOrNull()) }
    var versusAi by remember { mutableStateOf(true) }

    ScreenScaffold(title = "デュエル準備", onBack = onBack) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (usable.isEmpty()) {
                EmptyHint(
                    "使用できるデッキがありません。\n" +
                        "${DeckRules.MIN_SIZE}枚以上のデッキを作成してください。"
                )
                return@Column
            }

            SectionCard(title = "対戦形式") {
                FlowRowSimple {
                    Chip("AIと対戦", selected = versusAi) { versusAi = true }
                    Chip("2人で交代（同じ端末）", selected = !versusAi) { versusAi = false }
                }
                Text(
                    if (versusAi) "相手の手番は自動で進みます。"
                    else "1台の端末を回して、お互いのターンを手動で操作します。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SectionCard(title = "デッキ選択") {
                Dropdown(
                    label = "プレイヤー1（先攻）",
                    items = usable,
                    selected = deckA,
                    itemLabel = { "${it.name}（${it.size}枚）" }
                ) { deckA = it }

                Dropdown(
                    label = if (versusAi) "AI" else "プレイヤー2",
                    items = usable,
                    selected = deckB,
                    itemLabel = { "${it.name}（${it.size}枚）" }
                ) { deckB = it }
            }

            SectionCard(title = "ルール") {
                Text(
                    listOf(
                        "・お互いのライフは8000ポイント。",
                        "・相手フィールドにモンスターがいる限り直接攻撃はできない。",
                        "・レベル5〜6の召喚は1体、7以上は2体のリリースが必要。",
                        "・魔法カードは自分のターンのみ発動できる。",
                        "・罠カードは伏せたターンには発動できない。",
                        "・デッキが尽きたプレイヤーは敗北する。"
                    ).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = {
                    val a = deckA ?: return@Button
                    val b = deckB ?: return@Button
                    onStart(
                        DuelConfig(
                            deckAId = a.id,
                            deckBId = b.id,
                            playerAName = "プレイヤー1",
                            playerBName = if (versusAi) "AI" else "プレイヤー2",
                            versusAi = versusAi
                        )
                    )
                },
                enabled = deckA != null && deckB != null,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("デュエル開始")
            }
        }
    }
}
