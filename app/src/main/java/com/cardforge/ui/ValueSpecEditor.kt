package com.cardforge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cardforge.model.*
import com.cardforge.text.EffectTextRenderer
import com.cardforge.ui.theme.Surface2

/**
 * 数値の指定。固定値のほか「条件を満たすカードの枚数×係数」を組み立てられる。
 */
@Composable
fun ValueSpecEditor(
    label: String,
    spec: ValueSpec,
    master: MasterData,
    allowNegative: Boolean = false,
    /** 「決まった数」を選べるか。枚数の指定では別の欄で入れるので不要。 */
    allowFixed: Boolean = true,
    onChange: (ValueSpec) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Dropdown(
            label = "決め方",
            items = if (allowFixed) ValueKind.all else ValueKind.all - ValueKind.FIXED,
            selected = ValueKind.of(spec),
            itemLabel = { it.label }
        ) { kind -> onChange(kind.create(spec)) }

        when (spec) {
            is FixedValue -> NumberField(
                label = label + if (allowNegative) "（マイナスで下げる）" else "",
                value = spec.value
            ) { onChange(FixedValue(it)) }

            is CountValue -> {
                Text(
                    "数えるカード",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CardScopeEditor(
                    scope = spec.scope,
                    master = master,
                    showCount = false
                ) { onChange(spec.copy(scope = it)) }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField("1枚あたり", spec.multiplier, Modifier.weight(1f)) {
                        onChange(spec.copy(multiplier = it))
                    }
                    NumberField("固定で足す", spec.base, Modifier.weight(1f)) {
                        onChange(spec.copy(base = it))
                    }
                }
            }

            is AffectedCountValue -> {
                Text(
                    "直前の処理（破壊した・墓地へ送った等）で扱ったカードの数を使います。" +
                        "「破壊した数だけ特殊召喚する」のような効果に使います。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField("1枚あたり", spec.multiplier, Modifier.weight(1f)) {
                        onChange(spec.copy(multiplier = it))
                    }
                    NumberField("固定で足す", spec.base, Modifier.weight(1f)) {
                        onChange(spec.copy(base = it))
                    }
                }
            }

            is LevelValue -> {
                Dropdown("どのモンスターのレベルか", LevelSource.all, spec.source, { it.label }) {
                    onChange(spec.copy(source = it))
                }
                Text(
                    when (spec.source) {
                        LevelSource.EVENT_CARD ->
                            "【条件】に書いた出来事（召喚された・破壊された等）のモンスターのレベルです。"
                        LevelSource.EVENT_SOURCE ->
                            "出来事の相手側（攻撃してきたモンスター等）のレベルです。"
                        LevelSource.LAST_HANDLED ->
                            "直前の処理（破壊した・特殊召喚した等）で扱ったモンスターのレベルです。" +
                                "複数なら合計します。別のカードの効果で扱ったものも指せます。"
                        LevelSource.SELF -> "この効果を持つカード自身のレベルです。"
                        LevelSource.SUMMON_TARGET ->
                            "この効果で特殊召喚するモンスターのレベルです。【コスト】に使うと、" +
                                "コストを払うときに出すモンスターを先に選びます。" +
                                "足りるモンスターがいなければ発動できません。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField("レベル1あたり", spec.multiplier, Modifier.weight(1f)) {
                        onChange(spec.copy(multiplier = it))
                    }
                    NumberField("固定で足す", spec.base, Modifier.weight(1f)) {
                        onChange(spec.copy(base = it))
                    }
                }
            }

            is CounterValue -> {
                Text(
                    "カウンターが乗っているカード",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CardScopeEditor(
                    scope = spec.scope,
                    master = master,
                    showCount = false
                ) { onChange(spec.copy(scope = it)) }
                CounterPicker(master, spec.counterId) { onChange(spec.copy(counterId = it)) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField("1個あたり", spec.multiplier, Modifier.weight(1f)) {
                        onChange(spec.copy(multiplier = it))
                    }
                    NumberField("固定で足す", spec.base, Modifier.weight(1f)) {
                        onChange(spec.copy(base = it))
                    }
                }
            }
        }

        HorizontalDivider()
        Surface(color = Surface2, shape = MaterialTheme.shapes.small) {
            Text(
                EffectTextRenderer.valueToText(spec, master),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            )
        }
    }
}

/**
 * 「〜の数だけ」の枚数指定。数を数えるカードだけを組み立てる。
 *
 * [CardScopeEditor] を枚数なしで呼ぶので、ここから先は入れ子にならない。
 */
@Composable
fun CountSpecEditor(
    spec: ValueSpec,
    master: MasterData,
    onChange: (ValueSpec) -> Unit
) {
    ValueSpecEditor(
        label = "枚数",
        spec = spec,
        master = master,
        allowFixed = false,
        onChange = onChange
    )
}

/** 数値の決め方。 */
private enum class ValueKind(val label: String) {
    FIXED("決まった数"),
    CARD_COUNT("カードの枚数で決める"),
    AFFECTED("直前の処理で扱った枚数で決める"),
    COUNTER("カウンターの数で決める"),
    LEVEL("モンスターのレベルで決める");

    fun create(current: ValueSpec): ValueSpec = when (this) {
        FIXED -> FixedValue((current as? FixedValue)?.value ?: 0)
        CARD_COUNT -> CountValue()
        AFFECTED -> AffectedCountValue()
        COUNTER -> CounterValue()
        LEVEL -> LevelValue()
    }

    companion object {
        val all: List<ValueKind> get() = entries

        fun of(spec: ValueSpec): ValueKind = when (spec) {
            is FixedValue -> FIXED
            is CountValue -> CARD_COUNT
            is AffectedCountValue -> AFFECTED
            is CounterValue -> COUNTER
            is LevelValue -> LEVEL
        }
    }
}
