package com.cardforge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cardforge.model.*
import com.cardforge.text.EffectTextRenderer
import com.cardforge.ui.theme.Accent
import com.cardforge.ui.theme.Surface2

// ===========================================================================
// フィルタ（目的語の修飾）
// ===========================================================================

fun filterChipLabel(filter: CardFilter, master: MasterData): String = when (filter) {
    is KindFilter -> filter.kind.label
    is AttributeFilter -> master.attributeName(filter.attributeId) + "属性"
    is RaceFilter -> master.raceName(filter.raceId)
    is CategoryFilter -> "「${master.categoryName(filter.categoryId)}」"
    is LevelFilter -> "レベル${filter.value}${filter.cmp.label}"
    is AtkFilter -> "攻撃力${filter.value}${filter.cmp.label}"
    is DefFilter -> "守備力${filter.value}${filter.cmp.label}"
    is PositionFilter -> filter.position.label
    is NameFilter -> "名前に「${filter.text}」"
    is SummonedByThisFilter ->
        if (filter.enabled) "このカードの効果で特殊召喚された" else "それ以外"

    is AnyFilter ->
        if (filter.filters.isEmpty()) "（未設定）"
        else filter.filters.joinToString("／") { filterChipLabel(it, master) }

    is NotFilter -> filterChipLabel(filter.filter, master) + "を除く"
    is AffectedNameFilter ->
        if (filter.exclude) "直前に扱ったカードと同名を除く" else "直前に扱ったカードと同名"
}

private enum class FilterType(val label: String) {
    KIND("カードの種類"),
    ATTRIBUTE("属性"),
    RACE("種族"),
    CATEGORY("カテゴリ"),
    LEVEL("レベル"),
    ATK("攻撃力"),
    DEF("守備力"),
    POSITION("表示形式"),
    NAME("カード名"),
    SUMMONED_BY_THIS("このカードの効果で特殊召喚された"),
    ANY_OF("いずれかに当てはまる（または）"),
    NOT("〜を除く（当てはまらないカード）"),
    AFFECTED_NAME("直前に扱ったカードと同名"),
    NOT_AFFECTED_NAME("直前に扱ったカードと同名を除く")
}

@Composable
fun FilterDialog(
    master: MasterData,
    onDismiss: () -> Unit,
    onConfirm: (CardFilter) -> Unit
) {
    var type by remember { mutableStateOf(FilterType.KIND) }
    var kind by remember { mutableStateOf(CardKind.MONSTER) }
    var attributeId by remember { mutableStateOf(master.attributes.firstOrNull()?.id) }
    var raceId by remember { mutableStateOf(master.races.firstOrNull()?.id) }
    var categoryId by remember { mutableStateOf(master.categories.firstOrNull()?.id) }
    var cmp by remember { mutableStateOf(Cmp.LE) }
    var value by remember { mutableIntStateOf(4) }
    var position by remember { mutableStateOf(Position.ATTACK) }
    var name by remember { mutableStateOf("") }
    var anyOf by remember { mutableStateOf<List<CardFilter>>(emptyList()) }
    var addingAnyOf by remember { mutableStateOf(false) }
    var excluded by remember { mutableStateOf<CardFilter?>(null) }
    var pickingExcluded by remember { mutableStateOf(false) }

    val built: CardFilter? = when (type) {
        FilterType.KIND -> KindFilter(kind)
        FilterType.ATTRIBUTE -> attributeId?.let { AttributeFilter(it) }
        FilterType.RACE -> raceId?.let { RaceFilter(it) }
        FilterType.CATEGORY -> categoryId?.let { CategoryFilter(it) }
        FilterType.LEVEL -> LevelFilter(cmp, value)
        FilterType.ATK -> AtkFilter(cmp, value)
        FilterType.DEF -> DefFilter(cmp, value)
        FilterType.POSITION -> PositionFilter(position)
        FilterType.NAME -> if (name.isBlank()) null else NameFilter(name.trim())
        FilterType.SUMMONED_BY_THIS -> SummonedByThisFilter()
        FilterType.ANY_OF -> if (anyOf.isEmpty()) null else AnyFilter(anyOf)
        FilterType.NOT -> excluded?.let { NotFilter(it) }
        FilterType.AFFECTED_NAME -> AffectedNameFilter(exclude = false)
        FilterType.NOT_AFFECTED_NAME -> AffectedNameFilter(exclude = true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("条件を追加") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Dropdown(
                    label = "絞り込む要素",
                    items = FilterType.entries.toList(),
                    selected = type,
                    itemLabel = { it.label },
                    onSelect = { type = it }
                )
                when (type) {
                    FilterType.KIND -> Dropdown(
                        "カードの種類", CardKind.all, kind, { it.label }
                    ) { kind = it }

                    FilterType.ATTRIBUTE -> Dropdown(
                        "属性", master.attributes, master.attributes.firstOrNull { it.id == attributeId },
                        { it.name }
                    ) { attributeId = it.id }

                    FilterType.RACE -> Dropdown(
                        "種族", master.races, master.races.firstOrNull { it.id == raceId }, { it.name }
                    ) { raceId = it.id }

                    FilterType.CATEGORY -> Dropdown(
                        "カテゴリ", master.categories,
                        master.categories.firstOrNull { it.id == categoryId }, { it.name }
                    ) { categoryId = it.id }

                    FilterType.LEVEL, FilterType.ATK, FilterType.DEF -> {
                        NumberField("数値", value) { value = it }
                        Dropdown("比較", Cmp.all, cmp, { it.label }) { cmp = it }
                    }

                    FilterType.POSITION -> Dropdown(
                        "表示形式", Position.all, position, { it.label }
                    ) { position = it }

                    FilterType.NAME -> OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("カード名に含まれる文字") },
                        singleLine = true
                    )

                    FilterType.ANY_OF -> {
                        Text(
                            "並べた条件のうち、どれか1つに当てはまるカードが対象になります。" +
                                "「『VALIS』モンスターまたはレベル4以下のモンスター」" +
                                "のように書けます。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRowSimple {
                            anyOf.forEachIndexed { index, inner ->
                                Chip(filterChipLabel(inner, master) + " ✕", selected = true) {
                                    anyOf = anyOf.toMutableList().also { it.removeAt(index) }
                                }
                            }
                            Chip("＋ 「または」でつなぐ条件") { addingAnyOf = true }
                        }
                    }

                    FilterType.NOT -> {
                        Text(
                            "ここで選んだ条件に当てはまらないカードだけを指します。" +
                                "「『VALIS』を除くこのカテゴリのカード」のように使います。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRowSimple {
                            excluded?.let {
                                Chip(filterChipLabel(it, master) + " ✕", selected = true) {
                                    excluded = null
                                }
                            }
                            if (excluded == null) {
                                Chip("＋ 除く条件を選ぶ") { pickingExcluded = true }
                            }
                        }
                    }

                    FilterType.AFFECTED_NAME, FilterType.NOT_AFFECTED_NAME -> Text(
                        "直前の処理で扱ったカード（破壊した・墓地へ送った等）と" +
                            "同じ名前かどうかで絞り込みます。" +
                            "「破壊して、デッキから同名カードを」のような効果に使います。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    FilterType.SUMMONED_BY_THIS -> Text(
                        "この効果を持つカードの効果で特殊召喚されたカードだけを指します。" +
                            "「このカードの効果によって特殊召喚されたモンスターは〜」" +
                            "と書きたいときに使います。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { built?.let(onConfirm) },
                enabled = built != null
            ) { Text("追加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )

    // 「または」でつなぐ条件は、同じダイアログをもう一段開いて作る。
    if (addingAnyOf) {
        FilterDialog(
            master = master,
            onDismiss = { addingAnyOf = false },
            onConfirm = {
                anyOf = anyOf + it
                addingAnyOf = false
            }
        )
    }

    if (pickingExcluded) {
        FilterDialog(
            master = master,
            onDismiss = { pickingExcluded = false },
            onConfirm = {
                excluded = it
                pickingExcluded = false
            }
        )
    }
}

// ===========================================================================
// 対象指定（主語・目的語・修飾語）
// ===========================================================================

@Composable
fun CardScopeEditor(
    scope: CardScope,
    master: MasterData,
    /** 枚数と選び方を出すか。数を数えるだけの用途では不要。 */
    showCount: Boolean = true,
    onChange: (CardScope) -> Unit
) {
    var showFilterDialog by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = scope.selfOnly,
                onCheckedChange = { onChange(scope.copy(selfOnly = it)) }
            )
            Text("この効果を持つカード自身を対象にする")
        }

        if (scope.selfOnly) {
            Surface(color = Surface2, shape = MaterialTheme.shapes.small) {
                Text(
                    EffectTextRenderer.scopeToText(scope, master),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(8.dp)
                )
            }
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = scope.excludeSelf,
                onCheckedChange = { onChange(scope.copy(excludeSelf = it)) }
            )
            Text("この効果を持つカード自身を除く")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dropdown(
                "誰の", PlayerRef.all, scope.who, { it.label }, Modifier.weight(1f)
            ) { onChange(scope.copy(who = it)) }
            Dropdown(
                "どこの", ZoneType.all, scope.zone, { it.label }, Modifier.weight(1f)
            ) { onChange(scope.copy(zone = it)) }
        }

        if (showCount) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Dropdown(
                    "選び方", SelectionMode.all, scope.selection, { it.label }, Modifier.weight(1f)
                ) { onChange(scope.copy(selection = it)) }
                if (scope.selection != SelectionMode.ALL && scope.countSpec == null) {
                    NumberField("枚数／体数", scope.count, Modifier.weight(1f)) {
                        onChange(scope.copy(count = it.coerceIn(1, 9)))
                    }
                }
            }

            if (scope.selection != SelectionMode.ALL) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = scope.countSpec != null,
                        onCheckedChange = { useCount ->
                            onChange(
                                scope.copy(
                                    countSpec = if (useCount) CountValue(multiplier = 1) else null
                                )
                            )
                        }
                    )
                    Text("枚数を別のカードの枚数から決める")
                }
                scope.countSpec?.let { spec ->
                    Text(
                        "「〜の数だけ」の〜にあたるカード",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    CountSpecEditor(spec, master) { onChange(scope.copy(countSpec = it)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = scope.upTo,
                        onCheckedChange = { onChange(scope.copy(upTo = it)) }
                    )
                    Text("「〜まで」（足りなくても発動できる）")
                }
            }
        }

        Text(
            "対象の条件",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRowSimple {
            scope.filters.forEachIndexed { index, filter ->
                Chip(
                    text = filterChipLabel(filter, master) + " ✕",
                    selected = true,
                    color = Accent
                ) {
                    onChange(
                        scope.copy(
                            filters = scope.filters.toMutableList().also { it.removeAt(index) }
                        )
                    )
                }
            }
            Chip("＋ 条件を追加") { showFilterDialog = true }
        }

        Surface(color = Surface2, shape = MaterialTheme.shapes.small) {
            Text(
                EffectTextRenderer.scopeToText(scope, master),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp)
            )
        }
    }

    if (showFilterDialog) {
        FilterDialog(
            master = master,
            onDismiss = { showFilterDialog = false },
            onConfirm = { filter ->
                onChange(scope.copy(filters = scope.filters + filter))
                showFilterDialog = false
            }
        )
    }
}

/** 折り返しながらチップを並べるための薄いラッパー。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlowRowSimple(content: @Composable () -> Unit) {
    // FlowRowScope を公開シグネチャに出さないので、呼び出し側は opt-in 不要。
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        content()
    }
}
