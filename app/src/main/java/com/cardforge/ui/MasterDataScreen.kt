package com.cardforge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cardforge.data.LibraryRepository
import com.cardforge.data.MasterKind
import com.cardforge.model.NamedEntry
import com.cardforge.model.newId

/**
 * 属性・種族・カテゴリを自由に作成／編集／削除する画面。
 * カードの構成要素そのものをプレイヤーが決められるようにするための画面。
 */
@Composable
fun MasterDataScreen(
    repository: LibraryRepository,
    onBack: () -> Unit
) {
    val master = repository.library.master
    var editing by remember { mutableStateOf<Triple<MasterKind, NamedEntry?, String>?>(null) }
    var pendingDelete by remember { mutableStateOf<Pair<MasterKind, NamedEntry>?>(null) }

    ScreenScaffold(title = "属性・種族・カテゴリ", onBack = onBack) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                "ここで作った項目は、カード作成画面と効果テキストの中から選べるようになります。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            MasterSection(
                kind = MasterKind.ATTRIBUTE,
                entries = master.attributes,
                onAdd = { editing = Triple(MasterKind.ATTRIBUTE, null, "") },
                onEdit = { editing = Triple(MasterKind.ATTRIBUTE, it, it.name) },
                onDelete = { pendingDelete = MasterKind.ATTRIBUTE to it }
            )
            MasterSection(
                kind = MasterKind.RACE,
                entries = master.races,
                onAdd = { editing = Triple(MasterKind.RACE, null, "") },
                onEdit = { editing = Triple(MasterKind.RACE, it, it.name) },
                onDelete = { pendingDelete = MasterKind.RACE to it }
            )
            MasterSection(
                kind = MasterKind.CATEGORY,
                entries = master.categories,
                onAdd = { editing = Triple(MasterKind.CATEGORY, null, "") },
                onEdit = { editing = Triple(MasterKind.CATEGORY, it, it.name) },
                onDelete = { pendingDelete = MasterKind.CATEGORY to it }
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    editing?.let { (kind, entry, initial) ->
        NameInputDialog(
            title = if (entry == null) "${kind.label}を追加" else "${kind.label}の名前を変更",
            initial = initial,
            onDismiss = { editing = null },
            onConfirm = { name ->
                val updated = entry?.copy(name = name) ?: NamedEntry(newId(), name)
                repository.updateMaster(applyEntry(repository, kind, updated, entry != null))
                editing = null
            }
        )
    }

    pendingDelete?.let { (kind, entry) ->
        ConfirmDialog(
            title = "${kind.label}を削除",
            message = "「${entry.name}」を削除します。この${kind.label}を使っているカードからは外れます。",
            confirmLabel = "削除",
            onConfirm = {
                repository.deleteMasterEntry(kind, entry.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

private fun applyEntry(
    repository: LibraryRepository,
    kind: MasterKind,
    entry: NamedEntry,
    isUpdate: Boolean
) = repository.library.master.let { master ->
    fun merge(list: List<NamedEntry>) =
        if (isUpdate) list.map { if (it.id == entry.id) entry else it } else list + entry

    when (kind) {
        MasterKind.ATTRIBUTE -> master.copy(attributes = merge(master.attributes))
        MasterKind.RACE -> master.copy(races = merge(master.races))
        MasterKind.CATEGORY -> master.copy(categories = merge(master.categories))
    }
}

@Composable
private fun MasterSection(
    kind: MasterKind,
    entries: List<NamedEntry>,
    onAdd: () -> Unit,
    onEdit: (NamedEntry) -> Unit,
    onDelete: (NamedEntry) -> Unit
) {
    SectionCard(
        title = "${kind.label} (${entries.size})",
        trailing = {
            IconButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "${kind.label}を追加")
            }
        }
    ) {
        if (entries.isEmpty()) {
            Text(
                "まだ${kind.label}がありません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        entries.forEach { entry ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.name, modifier = Modifier.weight(1f))
                IconButton(onClick = { onEdit(entry) }) {
                    Icon(Icons.Default.Edit, contentDescription = "編集")
                }
                IconButton(onClick = { onDelete(entry) }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "削除",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
fun NameInputDialog(
    title: String,
    initial: String,
    label: String = "名前",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text.trim()) },
                enabled = text.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}
