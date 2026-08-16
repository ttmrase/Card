package com.cardforge.model

import kotlinx.serialization.Serializable
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

/** プレイヤーが自由に作成・編集できるマスターデータの1項目（属性・種族・カテゴリ）。 */
@Serializable
data class NamedEntry(
    val id: String,
    val name: String
)

@Serializable
data class MasterData(
    val attributes: List<NamedEntry> = emptyList(),
    val races: List<NamedEntry> = emptyList(),
    val categories: List<NamedEntry> = emptyList(),
    /** 「魔力カウンター」のような、カードに乗せるカウンターの種類。 */
    val counters: List<NamedEntry> = emptyList()
) {
    fun attributeName(id: String?): String =
        attributes.firstOrNull { it.id == id }?.name ?: "－"

    fun raceName(id: String?): String =
        races.firstOrNull { it.id == id }?.name ?: "－"

    fun categoryName(id: String?): String =
        categories.firstOrNull { it.id == id }?.name ?: "－"

    fun counterName(id: String?): String =
        counters.firstOrNull { it.id == id }?.name ?: "カウンター"
}

@Serializable
data class CardDef(
    val id: String,
    val name: String,
    val kind: CardKind,
    /** 端末から取り込んだイラストの内部ファイルパス。 */
    val imagePath: String? = null,
    val categoryIds: List<String> = emptyList(),
    // 以下はモンスターカードのみ使用。
    val level: Int = 4,
    val attributeId: String? = null,
    val raceId: String? = null,
    val atk: Int = 0,
    val def: Int = 0,
    val flavor: String = "",
    /**
     * 自動生成した効果テキストを手直ししたもの。
     * 設定されているとカードにはこちらが表示される（動作は効果データのまま）。
     */
    val textOverride: String? = null,
    /**
     * トークンかどうか。
     *
     * トークンはデッキに入れられず、効果でしか出てこない。
     * モンスターゾーンを離れるとゲームから取り除かれる。
     */
    val isToken: Boolean = false,
    /** 「通常召喚できない」カード。 */
    val cannotNormalSummon: Boolean = false,
    /** 「特殊召喚できない」カード。 */
    val cannotSpecialSummon: Boolean = false,
    /**
     * 「〜の効果によってのみ特殊召喚できる」。
     * 空なら特殊召喚のしかたを問わない。
     */
    val specialSummonOnlyBy: List<CardFilter> = emptyList(),
    /** null または空 = 効果を持たないカード。 */
    val effect: EffectText? = null,
    /** 旧データ互換。読み込み時に永続の効果へ変換される。 */
    val continuous: List<ContinuousEffect> = emptyList()
) {
    val hasEffect: Boolean get() = effect != null && !effect.isEmpty

    /** 発動を必要としない永続の効果を持っているか。 */
    val hasContinuous: Boolean
        get() = effect?.clauses?.any { it.mode.isContinuous } == true || continuous.isNotEmpty()

    /** 通常召喚に必要なリリース数。レベル4以下は0、5〜6は1、7以上は2。 */
    val tributesRequired: Int
        get() = when {
            kind != CardKind.MONSTER -> 0
            level <= 4 -> 0
            level <= 6 -> 1
            else -> 2
        }
}

@Serializable
data class Deck(
    val id: String,
    val name: String,
    /** カードIDの並び。同じIDを複数入れると、その枚数だけデッキに入る。 */
    val cardIds: List<String> = emptyList()
) {
    val size: Int get() = cardIds.size
}

@Serializable
data class Library(
    val master: MasterData = MasterData(),
    val cards: List<CardDef> = emptyList(),
    val decks: List<Deck> = emptyList(),
    /** 他のカードで使い回すために保存した効果。 */
    val effectPresets: List<EffectPreset> = emptyList()
) {
    fun card(id: String): CardDef? = cards.firstOrNull { it.id == id }
}

/** 名前を付けて保存した効果。カード作成画面から呼び出して使う。 */
@Serializable
data class EffectPreset(
    val id: String,
    val name: String,
    val effect: EffectText
)

object DeckRules {
    const val MIN_SIZE = 20
    const val MAX_SIZE = 60
    const val MAX_COPIES = 3
    const val STARTING_HAND = 5
    const val STARTING_LIFE = 8000
    const val ZONE_COUNT = 5
}
