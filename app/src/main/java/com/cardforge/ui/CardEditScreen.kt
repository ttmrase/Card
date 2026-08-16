package com.cardforge.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cardforge.data.LibraryRepository
import com.cardforge.data.MasterKind
import com.cardforge.model.*
import com.cardforge.text.EffectTextRenderer
import com.cardforge.ui.theme.Gold
import com.cardforge.ui.theme.Surface2

@Composable
fun CardEditScreen(
    repository: LibraryRepository,
    cardId: String?,
    onBack: () -> Unit
) {
    val library = repository.library
    val master = library.master
    val original = remember(cardId) { cardId?.let { library.card(it) } }

    var card by remember {
        mutableStateOf(
            original ?: CardDef(
                id = newId(),
                name = "",
                kind = CardKind.MONSTER,
                attributeId = master.attributes.firstOrNull()?.id,
                raceId = master.races.firstOrNull()?.id,
                atk = 1500,
                def = 1200
            )
        )
    }
    var managingMaster by remember { mutableStateOf<MasterKind?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    var cropTarget by remember { mutableStateOf<String?>(null) }
    var addingSummonFilter by remember { mutableStateOf(false) }
    var savingPreset by remember { mutableStateOf(false) }
    var loadingPreset by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val path = repository.importImage(uri)
            if (path != null) {
                card = card.copy(imagePath = path)
                // 取り込んだ直後に切り抜き画面を出す。
                cropTarget = path
            } else {
                error = "画像の読み込みに失敗しました。"
            }
        }
    }

    fun save() {
        if (card.name.isBlank()) {
            error = "カード名を入力してください。"
            return
        }
        repository.upsertCard(card.copy(name = card.name.trim()))
        onBack()
    }

    ScreenScaffold(
        title = if (original == null) "カードを作成" else "カードを編集",
        onBack = onBack,
        actions = {
            IconButton(onClick = { save() }) {
                Icon(Icons.Default.Check, contentDescription = "保存", tint = Gold)
            }
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {

            // ---- 基本情報 ---------------------------------------------
            SectionCard(title = "基本情報") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CardArt(
                            imagePath = card.imagePath,
                            kind = card.kind,
                            modifier = Modifier.size(width = 96.dp, height = 128.dp)
                        )
                        TextButton(onClick = {
                            imagePicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        }) {
                            Icon(Icons.Default.Image, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("イラスト")
                        }
                        card.imagePath?.let { path ->
                            TextButton(onClick = { cropTarget = path }) { Text("切り抜き") }
                            TextButton(onClick = { card = card.copy(imagePath = null) }) {
                                Text("削除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }

                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = card.name,
                            onValueChange = { card = card.copy(name = it) },
                            label = { Text("カード名") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "カードの種類",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRowSimple {
                            CardKind.all.forEach { kind ->
                                Chip(
                                    text = kind.label,
                                    selected = card.kind == kind,
                                    color = kindColor(kind)
                                ) { card = card.copy(kind = kind) }
                            }
                        }
                    }
                }
            }

            // ---- モンスター固有 ---------------------------------------
            if (card.kind == CardKind.MONSTER) {
                SectionCard(title = "モンスター情報") {
                    Dropdown(
                        label = "レベル",
                        items = (1..12).toList(),
                        selected = card.level,
                        itemLabel = { "★".repeat(minOf(it, 12)) + "  ($it)" }
                    ) { card = card.copy(level = it) }

                    Text(
                        tributeHint(card.level),
                        style = MaterialTheme.typography.bodySmall,
                        color = Gold
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Dropdown(
                            label = "属性",
                            items = master.attributes,
                            selected = master.attributes.firstOrNull { it.id == card.attributeId },
                            itemLabel = { it.name },
                            modifier = Modifier.weight(1f)
                        ) { card = card.copy(attributeId = it.id) }
                        IconButton(onClick = { managingMaster = MasterKind.ATTRIBUTE }) {
                            Icon(Icons.Default.Tune, contentDescription = "属性を追加・編集")
                        }

                        Dropdown(
                            label = "種族",
                            items = master.races,
                            selected = master.races.firstOrNull { it.id == card.raceId },
                            itemLabel = { it.name },
                            modifier = Modifier.weight(1f)
                        ) { card = card.copy(raceId = it.id) }
                        IconButton(onClick = { managingMaster = MasterKind.RACE }) {
                            Icon(Icons.Default.Tune, contentDescription = "種族を追加・編集")
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NumberField("攻撃力", card.atk, Modifier.weight(1f)) {
                            card = card.copy(atk = it.coerceAtLeast(0))
                        }
                        NumberField("守備力", card.def, Modifier.weight(1f)) {
                            card = card.copy(def = it.coerceAtLeast(0))
                        }
                    }
                }

                // ---- 召喚のしかた -------------------------------------
                SectionCard(title = "召喚のしかた") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = card.isToken,
                            onCheckedChange = { card = card.copy(isToken = it) }
                        )
                        Text("トークンにする")
                    }
                    Text(
                        "トークンはデッキに入れられず、効果でしか出てきません。" +
                            "モンスターゾーンを離れるとゲームから取り除かれます。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = card.cannotNormalSummon,
                            onCheckedChange = { card = card.copy(cannotNormalSummon = it) }
                        )
                        Text("通常召喚できない")
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = card.cannotSpecialSummon,
                            onCheckedChange = { card = card.copy(cannotSpecialSummon = it) }
                        )
                        Text("特殊召喚できない")
                    }

                    Text(
                        "特殊召喚できる効果を限定する（空なら制限なし）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRowSimple {
                        card.specialSummonOnlyBy.forEachIndexed { index, filter ->
                            Chip(filterChipLabel(filter, master) + " ✕", selected = true) {
                                card = card.copy(
                                    specialSummonOnlyBy = card.specialSummonOnlyBy
                                        .toMutableList().also { it.removeAt(index) }
                                )
                            }
                        }
                        Chip("＋ 条件を追加") { addingSummonFilter = true }
                    }
                    Text(
                        "「「アララギ」カードの効果によってのみ特殊召喚できる」" +
                            "のように、出せるカードを絞れます。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ---- カテゴリ ---------------------------------------------
            SectionCard(
                title = "カテゴリ",
                trailing = {
                    TextButton(onClick = { managingMaster = MasterKind.CATEGORY }) {
                        Text("追加・編集")
                    }
                }
            ) {
                Text(
                    "カテゴリを付けると、効果テキストから「このカテゴリのモンスター」としてまとめて参照できます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (master.categories.isEmpty()) {
                    Text("カテゴリがまだありません。", style = MaterialTheme.typography.bodySmall)
                }
                FlowRowSimple {
                    master.categories.forEach { category ->
                        Chip(
                            text = category.name,
                            selected = category.id in card.categoryIds,
                            color = Gold
                        ) {
                            card = card.copy(
                                categoryIds =
                                    if (category.id in card.categoryIds) card.categoryIds - category.id
                                    else card.categoryIds + category.id
                            )
                        }
                    }
                }
            }

            // ---- 効果テキスト -----------------------------------------
            SectionCard(title = "効果") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = card.effect != null,
                        onCheckedChange = { checked ->
                            card = card.copy(effect = if (checked) EffectText() else null)
                        }
                    )
                    Text("このカードは効果を持つ")
                }
                if (card.effect == null) {
                    Text(
                        if (card.kind == CardKind.MONSTER)
                            "チェックを外したままにすると、効果を持たない通常モンスターになります。" +
                                "永続効果も、ここにチェックを入れてから【発動タイプ】を「永続」にして作ります。"
                        else "魔法・罠カードは効果が必要です。チェックを入れてください。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            card.effect?.let { effect ->
                SectionCard(title = "効果の保存・呼び出し") {
                    Text(
                        "よく使う効果に名前を付けて保存しておくと、" +
                            "他のカードを作るときに呼び出して使えます。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { savingPreset = true },
                            enabled = !effect.isEmpty,
                            modifier = Modifier.weight(1f)
                        ) { Text("この効果を保存") }
                        OutlinedButton(
                            onClick = { loadingPreset = true },
                            enabled = library.effectPresets.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) { Text("保存した効果 (${library.effectPresets.size})") }
                    }
                }

                EffectEditorSection(
                    kind = card.kind,
                    effect = effect,
                    master = master,
                    tokenCards = library.cards.filter { it.isToken && it.id != card.id }
                ) { card = card.copy(effect = it) }
            }

            // ---- フレーバー -------------------------------------------
            SectionCard(title = "フレーバーテキスト（任意）") {
                OutlinedTextField(
                    value = card.flavor,
                    onValueChange = { card = card.copy(flavor = it) },
                    label = { Text("説明文") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ---- テキストの手直し -------------------------------------
            SectionCard(title = "効果テキストの手直し（任意）") {
                Text(
                    "自動で組み立てた文の助詞などが不自然なときに、表示だけを書き換えられます。" +
                        "ゲーム中の動作は効果データのままで変わりません。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (card.textOverride == null) {
                    OutlinedButton(
                        onClick = {
                            card = card.copy(
                                textOverride = EffectTextRenderer.renderGenerated(card, master)
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("いまの文を読み込んで手直しする") }
                } else {
                    OutlinedTextField(
                        value = card.textOverride.orEmpty(),
                        onValueChange = { card = card.copy(textOverride = it) },
                        label = { Text("表示する効果テキスト") },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            card = card.copy(
                                textOverride = EffectTextRenderer.renderGenerated(card, master)
                            )
                        }) { Text("自動生成し直す") }
                        TextButton(onClick = { card = card.copy(textOverride = null) }) {
                            Text("手直しをやめる")
                        }
                    }
                }
            }

            // ---- プレビュー -------------------------------------------
            SectionCard(title = "カードテキスト プレビュー") {
                Surface(color = Surface2, shape = MaterialTheme.shapes.small) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            card.name.ifBlank { "（カード名未設定）" },
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            EffectTextRenderer.summary(card, master),
                            style = MaterialTheme.typography.bodySmall,
                            color = Gold
                        )
                        val text = EffectTextRenderer.render(card, master)
                        if (text.isBlank()) {
                            Text(
                                card.flavor.ifBlank { "（効果を持たないカード）" },
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            EffectTextView(text)
                        }
                    }
                }
            }

            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) {
                Text("保存する")
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    cropTarget?.let { path ->
        ImageCropDialog(
            imagePath = path,
            onDismiss = { cropTarget = null },
            onConfirm = { rect ->
                val cropped = repository.cropImage(
                    path, rect.left, rect.top, rect.width, rect.height
                )
                if (cropped != null) card = card.copy(imagePath = cropped)
                else error = "切り抜きに失敗しました。"
                cropTarget = null
            }
        )
    }

    if (savingPreset) {
        NameInputDialog(
            title = "この効果を保存",
            initial = card.name.ifBlank { "" },
            onDismiss = { savingPreset = false },
            onConfirm = { name ->
                card.effect?.let { repository.saveEffectPreset(name, it) }
                savingPreset = false
            }
        )
    }

    if (loadingPreset) {
        EffectPresetDialog(
            presets = library.effectPresets,
            master = master,
            kind = card.kind,
            onDismiss = { loadingPreset = false },
            onReplace = { preset ->
                card = card.copy(effect = preset.effect)
                loadingPreset = false
            },
            onAppend = { preset ->
                val current = card.effect ?: EffectText()
                card = card.copy(
                    effect = current.copy(clauses = current.clauses + preset.effect.clauses)
                )
                loadingPreset = false
            },
            onDelete = { repository.deleteEffectPreset(it.id) },
            onRename = { preset, name -> repository.renameEffectPreset(preset.id, name) }
        )
    }

    if (addingSummonFilter) {
        FilterDialog(
            master = master,
            onDismiss = { addingSummonFilter = false },
            onConfirm = {
                card = card.copy(specialSummonOnlyBy = card.specialSummonOnlyBy + it)
                addingSummonFilter = false
            }
        )
    }

    managingMaster?.let { kind ->
        val entries = when (kind) {
            MasterKind.ATTRIBUTE -> master.attributes
            MasterKind.RACE -> master.races
            MasterKind.CATEGORY -> master.categories
            MasterKind.COUNTER -> master.counters
        }
        MasterEntryManagerDialog(
            kind = kind,
            entries = entries,
            onAdd = { name ->
                val entry = NamedEntry(newId(), name)
                // 追加したものは、そのままこのカードに設定する。
                when (kind) {
                    MasterKind.ATTRIBUTE -> {
                        repository.updateMaster(master.copy(attributes = master.attributes + entry))
                        card = card.copy(attributeId = entry.id)
                    }

                    MasterKind.RACE -> {
                        repository.updateMaster(master.copy(races = master.races + entry))
                        card = card.copy(raceId = entry.id)
                    }

                    MasterKind.CATEGORY -> {
                        repository.updateMaster(master.copy(categories = master.categories + entry))
                        card = card.copy(categoryIds = card.categoryIds + entry.id)
                    }

                    MasterKind.COUNTER ->
                        repository.updateMaster(master.copy(counters = master.counters + entry))
                }
            },
            onRename = { entry, name ->
                val renamed = entry.copy(name = name)
                fun merge(list: List<NamedEntry>) =
                    list.map { if (it.id == entry.id) renamed else it }
                repository.updateMaster(
                    when (kind) {
                        MasterKind.ATTRIBUTE -> master.copy(attributes = merge(master.attributes))
                        MasterKind.RACE -> master.copy(races = merge(master.races))
                        MasterKind.CATEGORY -> master.copy(categories = merge(master.categories))
                        MasterKind.COUNTER -> master.copy(counters = merge(master.counters))
                    }
                )
            },
            onDelete = { entry ->
                repository.deleteMasterEntry(kind, entry.id)
                // 編集中のカードからも外す。
                card = when (kind) {
                    MasterKind.ATTRIBUTE ->
                        if (card.attributeId == entry.id) card.copy(attributeId = null) else card

                    MasterKind.RACE ->
                        if (card.raceId == entry.id) card.copy(raceId = null) else card

                    MasterKind.CATEGORY -> card.copy(categoryIds = card.categoryIds - entry.id)
                    MasterKind.COUNTER -> card
                }
            },
            onDismiss = { managingMaster = null }
        )
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("確認") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } }
        )
    }
}

private fun tributeHint(level: Int): String = when {
    level <= 4 -> "レベル4以下：そのまま通常召喚できます。"
    level <= 6 -> "レベル5〜6：通常召喚には1体のリリースが必要です。"
    else -> "レベル7以上：通常召喚には2体のリリースが必要です。"
}
