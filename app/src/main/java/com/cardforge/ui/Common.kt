package com.cardforge.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardforge.model.CardDef
import com.cardforge.model.CardKind
import com.cardforge.model.MasterData
import com.cardforge.text.EffectTextRenderer
import com.cardforge.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 内部ストレージのイラストを、画面サイズに合わせて縮小しつつ読み込む。 */
@Composable
fun rememberCardImage(path: String?): ImageBitmap? =
    produceState<ImageBitmap?>(initialValue = null, path) {
        value = if (path.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                val widthSample = bounds.outWidth / 640
                val heightSample = bounds.outHeight / 640
                val options = BitmapFactory.Options().apply {
                    inSampleSize = maxOf(1, minOf(widthSample, heightSample))
                }
                BitmapFactory.decodeFile(path, options)?.asImageBitmap()
            }.getOrNull()
        }
    }.value

fun kindColor(kind: CardKind): Color = when (kind) {
    CardKind.MONSTER -> MonsterColor
    CardKind.SPELL -> SpellColor
    CardKind.TRAP -> TrapColor
}

/** 全画面共通のヘッダー付きレイアウト。 */
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floatingAction: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        containerColor = Ink,
        topBar = {
            Surface(color = Surface1, tonalElevation = 2.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                        }
                    } else {
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    actions()
                }
            }
        },
        floatingActionButton = { floatingAction?.invoke() },
        content = content
    )
}

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface1)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = Gold,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                trailing?.invoke()
            }
            content()
        }
    }
}

/**
 * 汎用のドロップダウン。効果エディタの「主語」「述語」などを
 * 選択肢から選ばせるのに使う。
 */
@Composable
fun <T> Dropdown(
    label: String,
    items: List<T>,
    selected: T?,
    itemLabel: (T) -> String,
    modifier: Modifier = Modifier,
    placeholder: String = "未選択",
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        if (label.isNotBlank()) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = selected?.let(itemLabel) ?: placeholder,
                    maxLines = 1
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (items.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("項目がありません") },
                        onClick = { expanded = false }
                    )
                }
                items.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(itemLabel(item)) },
                        onClick = {
                            onSelect(item)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

/** 数値専用の入力欄。入力途中の状態を壊さないよう、表示文字列を自前で保持する。 */
@Composable
fun NumberField(
    label: String,
    value: Int,
    modifier: Modifier = Modifier,
    onValueChange: (Int) -> Unit
) {
    var text by remember { mutableStateOf(value.toString()) }
    // 外側で値が変わったときだけ表示を追従させる。
    LaunchedEffect(value) {
        if (text.toIntOrNull() != value) text = value.toString()
    }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val filtered = buildString {
                raw.forEachIndexed { index, c ->
                    if (c.isDigit() || (c == '-' && index == 0)) append(c)
                }
            }
            text = filtered
            filtered.toIntOrNull()?.let(onValueChange)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = KeyboardType.Number
        ),
        modifier = modifier
    )
}

@Composable
fun Chip(
    text: String,
    selected: Boolean = false,
    color: Color = Accent,
    onClick: (() -> Unit)? = null
) {
    val base = if (selected) color.copy(alpha = 0.28f) else Surface2
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(base)
            .border(
                width = if (selected) 1.dp else 0.dp,
                color = if (selected) color else Color.Transparent,
                shape = RoundedCornerShape(14.dp)
            )
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(text, fontSize = 12.sp, color = if (selected) color else Color(0xFFC4BCE0))
    }
}

/**
 * タップと長押しを両方受ける修飾子。
 * どの画面でも「長押しでカードの効果を確認できる」ようにするために使う。
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.tapOrHold(
    onClick: () -> Unit,
    onLongClick: () -> Unit
): Modifier = this.combinedClickable(onClick = onClick, onLongClick = onLongClick)

/**
 * カードの内容を一覧できるダイアログ。
 * [statLine] にデュエル中の実際の攻守など、その場の値を渡せる。
 */
@Composable
fun CardPreviewDialog(
    card: CardDef,
    master: MasterData,
    statLine: String? = null,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(card.name) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CardArt(
                    imagePath = card.imagePath,
                    kind = card.kind,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                )
                Text(statLine ?: EffectTextRenderer.summary(card, master), color = Gold)
                if (card.categoryIds.isNotEmpty()) {
                    Text(
                        "カテゴリ: " + card.categoryIds.joinToString("、") {
                            master.categoryName(it)
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                val text = EffectTextRenderer.render(card, master)
                Text(
                    text.ifBlank { card.flavor.ifBlank { "効果を持たないカード。" } },
                    style = MaterialTheme.typography.bodySmall
                )
                if (text.isNotBlank() && card.flavor.isNotBlank()) {
                    Text(
                        card.flavor,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } }
    )
}

@Composable
fun EmptyHint(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "OK",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = Danger) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

/** カードのイラスト枠。イラストが無いときは種類に応じた色で塗る。 */
@Composable
fun CardArt(
    imagePath: String?,
    kind: CardKind,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val image = rememberCardImage(imagePath)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(kindColor(kind).copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center
    ) {
        if (image != null) {
            androidx.compose.foundation.Image(
                bitmap = image,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                kind.label,
                fontSize = 10.sp,
                color = Color(0xFFEDE9F7).copy(alpha = 0.7f)
            )
        }
    }
}
