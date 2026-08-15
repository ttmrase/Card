package com.cardforge.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cardforge.data.LibraryRepository
import com.cardforge.model.CardDef
import com.cardforge.model.CardKind
import androidx.compose.ui.platform.LocalContext
import com.cardforge.text.EffectTextRenderer
import com.cardforge.ui.theme.Surface1

@Composable
fun CardListScreen(
    repository: LibraryRepository,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onCreate: () -> Unit
) {
    val library = repository.library
    var query by remember { mutableStateOf("") }
    var kindFilter by remember { mutableStateOf<CardKind?>(null) }
    var pendingDelete by remember { mutableStateOf<CardDef?>(null) }
    var preview by remember { mutableStateOf<CardDef?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var exportSelection by remember { mutableStateOf<ExchangeSelection?>(null) }
    var choosingExport by remember { mutableStateOf(false) }
    var importPayload by remember { mutableStateOf<com.cardforge.data.CardExchange?>(null) }
    var importSelection by remember { mutableStateOf<ExchangeSelection?>(null) }
    val context = LocalContext.current

    // 書き出しは端末のファイルとして保存する。イラストも埋め込まれる。
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val selection = exportSelection
        exportSelection = null
        if (uri == null || selection == null) return@rememberLauncherForActivityResult
        message = runCatching {
            val chosenCards = library.cards.filter { it.id in selection.cardIds }
            val chosenDecks = library.decks.filter { it.id in selection.deckIds }
            val text = repository.buildExport(chosenCards, chosenDecks)
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            "カード${chosenCards.size}枚とデッキ${chosenDecks.size}個を書き出しました。"
        }.getOrElse { "書き出しに失敗しました。" }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // まず中身を読んで、何を取り込むか選ばせる。
        runCatching {
            val text = context.contentResolver.openInputStream(uri)
                ?.use { it.readBytes().decodeToString() }
            if (text == null) {
                message = "ファイルを読み込めませんでした。"
                return@runCatching
            }
            repository.parseExchange(text).fold(
                onSuccess = { importPayload = it },
                onFailure = { message = "このファイルは読み込めませんでした。" }
            )
        }.onFailure { message = "取り込みに失敗しました。" }
    }

    val visible = library.cards.filter { card ->
        (kindFilter == null || card.kind == kindFilter) &&
            (query.isBlank() || card.name.contains(query, ignoreCase = true))
    }

    ScreenScaffold(
        title = "カード (${library.cards.size})",
        onBack = onBack,
        actions = {
            IconButton(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }) {
                Icon(Icons.Default.FileUpload, contentDescription = "インポート")
            }
            IconButton(onClick = { choosingExport = true }) {
                Icon(Icons.Default.FileDownload, contentDescription = "エクスポート")
            }
        },
        floatingAction = {
            FloatingActionButton(onClick = onCreate) {
                Icon(Icons.Default.Add, contentDescription = "カードを作成")
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("カード名で検索") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Chip("すべて", selected = kindFilter == null) { kindFilter = null }
                CardKind.all.forEach { kind ->
                    Chip(
                        text = kind.label,
                        selected = kindFilter == kind,
                        color = kindColor(kind)
                    ) { kindFilter = if (kindFilter == kind) null else kind }
                }
            }

            if (visible.isEmpty()) {
                EmptyHint("カードがありません。右下のボタンから作成できます。")
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(visible, key = { it.id }) { card ->
                        CardRow(
                            card = card,
                            summary = EffectTextRenderer.summary(card, library.master),
                            onClick = { onEdit(card.id) },
                            onLongClick = { preview = card },
                            onDuplicate = { repository.duplicateCard(card.id) },
                            onDelete = { pendingDelete = card }
                        )
                    }
                    item { Spacer(Modifier.height(72.dp)) }
                }
            }
        }
    }

    if (choosingExport) {
        ExchangeSelectionDialog(
            title = "書き出す内容を選択",
            confirmLabel = "書き出す",
            cards = library.cards,
            decks = library.decks,
            master = library.master,
            onDismiss = { choosingExport = false },
            onConfirm = { selection ->
                choosingExport = false
                exportSelection = selection
                exportLauncher.launch("cardforge-cards.json")
            }
        )
    }

    importPayload?.let { payload ->
        ImportSelectionDialog(
            payload = payload,
            master = library.master,
            onDismiss = { importPayload = null },
            onConfirm = { selection ->
                importSelection = selection
                val summary = repository.importSelection(
                    payload, selection.cardIds, selection.deckIds
                )
                message = "取り込みました。新規カード${summary.addedCards}枚、" +
                    "更新${summary.updatedCards}枚、デッキ${summary.addedDecks}個。"
                importPayload = null
            }
        )
    }

    preview?.let { card ->
        CardPreviewDialog(
            card = card,
            master = library.master,
            onDismiss = { preview = null }
        )
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("お知らせ") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } }
        )
    }

    pendingDelete?.let { card ->
        ConfirmDialog(
            title = "カードを削除",
            message = "「${card.name}」を削除します。このカードを使っているデッキからも取り除かれます。",
            confirmLabel = "削除",
            onConfirm = {
                repository.deleteCard(card.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun CardRow(
    card: CardDef,
    summary: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .tapOrHold(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(containerColor = Surface1)
    ) {
        Row(
            Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CardArt(
                imagePath = card.imagePath,
                kind = card.kind,
                modifier = Modifier.size(width = 48.dp, height = 64.dp)
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(card.name, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(card.kind.label, selected = true, color = kindColor(card.kind))
                    if (card.hasEffect) Chip("効果")
                    if (card.hasContinuous) Chip("永続")
                }
            }
            IconButton(onClick = onDuplicate) {
                Icon(Icons.Default.ContentCopy, contentDescription = "複製")
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "削除",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
