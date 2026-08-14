package com.cardforge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cardforge.data.LibraryRepository
import com.cardforge.model.CardDef
import com.cardforge.model.CardKind
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

    val visible = library.cards.filter { card ->
        (kindFilter == null || card.kind == kindFilter) &&
            (query.isBlank() || card.name.contains(query, ignoreCase = true))
    }

    ScreenScaffold(
        title = "カード (${library.cards.size})",
        onBack = onBack,
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
                            onDuplicate = { repository.duplicateCard(card.id) },
                            onDelete = { pendingDelete = card }
                        )
                    }
                    item { Spacer(Modifier.height(72.dp)) }
                }
            }
        }
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
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
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
