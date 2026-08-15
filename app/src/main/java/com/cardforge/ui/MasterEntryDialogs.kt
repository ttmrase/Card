package com.cardforge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cardforge.data.MasterKind
import com.cardforge.model.NamedEntry

/**
 * 属性・種族・カテゴリの一覧を出し、その場で追加・名前変更・削除ができる。
 * カード作成中でも直せるよう、カード編集画面からも開ける。
 */
@Composable
fun MasterEntryManagerDialog(
    kind: MasterKind,
    entries: List<NamedEntry>,
    onAdd: (String) -> Unit,
    onRename: (NamedEntry, String) -> Unit,
    onDelete: (NamedEntry) -> Unit,
    onDismiss: () -> Unit
) {
    var editing by remember { mutableStateOf<NamedEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<NamedEntry?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${kind.label}の管理") },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "名前を変えると、その${kind.label}を使っている全てのカードの表示も変わります。" +
                        "削除すると、使っていたカードからは外れます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (entries.isEmpty()) {
                    Text("まだ${kind.label}がありません。", style = MaterialTheme.typography.bodySmall)
                }

                LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(entries, key = { it.id }) { entry ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(entry.name, modifier = Modifier.weight(1f))
                            IconButton(onClick = { editing = entry }) {
                                Icon(Icons.Default.Edit, contentDescription = "名前を変更")
                            }
                            IconButton(onClick = { pendingDelete = entry }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "削除",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("${kind.label}を追加")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } }
    )

    if (adding) {
        NameInputDialog(
            title = "${kind.label}を追加",
            initial = "",
            label = "${kind.label}名",
            onDismiss = { adding = false },
            onConfirm = {
                onAdd(it)
                adding = false
            }
        )
    }

    editing?.let { entry ->
        NameInputDialog(
            title = "${kind.label}の名前を変更",
            initial = entry.name,
            label = "${kind.label}名",
            onDismiss = { editing = null },
            onConfirm = {
                onRename(entry, it)
                editing = null
            }
        )
    }

    pendingDelete?.let { entry ->
        ConfirmDialog(
            title = "${kind.label}を削除",
            message = "「${entry.name}」を削除します。この${kind.label}を使っているカードからは外れます。",
            confirmLabel = "削除",
            onConfirm = {
                onDelete(entry)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}
