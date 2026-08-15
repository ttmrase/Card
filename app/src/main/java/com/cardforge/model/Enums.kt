package com.cardforge.model

import kotlinx.serialization.Serializable

/** 修飾語: どちらのプレイヤーの領域を指すか。 */
@Serializable
enum class PlayerRef(val label: String) {
    SELF("自分"),
    OPPONENT("相手"),
    BOTH("お互い");

    companion object {
        val all: List<PlayerRef> get() = entries
    }
}

/** 修飾語: どの領域か。FIELD はモンスターゾーンと魔法・罠ゾーンの両方を指す。 */
@Serializable
enum class ZoneType(val label: String) {
    MONSTER_ZONE("モンスターゾーン"),
    SPELL_TRAP_ZONE("魔法・罠ゾーン"),
    FIELD("フィールド"),
    HAND("手札"),
    DECK("デッキ"),
    GRAVEYARD("墓地"),
    BANISHED("除外ゾーン");

    /** 手札・デッキのように、非公開でランダム性のある領域か。 */
    val isHidden: Boolean get() = this == HAND || this == DECK

    companion object {
        val all: List<ZoneType> get() = entries
    }
}

@Serializable
enum class CardKind(val label: String) {
    MONSTER("モンスター"),
    SPELL("魔法"),
    TRAP("罠");

    companion object {
        val all: List<CardKind> get() = entries
    }
}

@Serializable
enum class Position(val label: String, val short: String) {
    ATTACK("表側攻撃表示", "攻"),
    DEFENSE("表側守備表示", "守"),
    FACE_DOWN_DEFENSE("裏側守備表示", "裏");

    val isFaceDown: Boolean get() = this == FACE_DOWN_DEFENSE
    val isDefense: Boolean get() = this == DEFENSE || this == FACE_DOWN_DEFENSE

    companion object {
        val all: List<Position> get() = entries
    }
}

/** 数値比較。ラベルは「レベル4以下」のように後置で読める形にしてある。 */
@Serializable
enum class Cmp(val label: String) {
    GE("以上"),
    LE("以下"),
    EQ("ちょうど"),
    GT("より大きい"),
    LT("未満");

    fun test(actual: Int, target: Int): Boolean = when (this) {
        GE -> actual >= target
        LE -> actual <= target
        EQ -> actual == target
        GT -> actual > target
        LT -> actual < target
    }

    companion object {
        val all: List<Cmp> get() = entries
    }
}

/** 対象の選び方。 */
@Serializable
enum class SelectionMode(val label: String) {
    CHOOSE("選んで"),
    RANDOM("ランダムに"),
    ALL("全ての");

    companion object {
        val all: List<SelectionMode> get() = entries
    }
}

@Serializable
enum class StatKind(val label: String) {
    ATK("攻撃力"),
    DEF("守備力");

    companion object {
        val all: List<StatKind> get() = entries
    }
}

/** 【場所】: そのカードを発動できる場所。 */
@Serializable
enum class ActivationLocation(val label: String) {
    HAND("手札"),
    FIELD("フィールド"),
    GRAVEYARD("墓地");

    companion object {
        val all: List<ActivationLocation> get() = entries
    }
}

/**
 * 【発動後】発動して解決したあと、そのカードをどうするか。
 *
 * 魔法・罠で省略した場合は「墓地へ送る」（通常魔法・通常罠）。
 * モンスターで省略した場合は「そのまま残す」。
 * 手札で発動するモンスターは、コストで墓地へ送るのか、効果の解決後に
 * 墓地へ送るのかをここで区別できる。
 */
@Serializable
enum class AfterActivation(val label: String) {
    TO_GRAVE("墓地へ送る"),
    STAY_ON_FIELD("そのまま残す"),
    BANISH("除外する"),
    TO_HAND("手札に戻す"),
    TO_DECK("デッキに戻す");

    companion object {
        val all: List<AfterActivation> get() = entries

        /** 記述が省略されている場合の既定。 */
        val DEFAULT: AfterActivation get() = TO_GRAVE
    }
}

/** 【制限】発動回数を数える単位。 */
@Serializable
enum class LimitScope(val label: String) {
    THIS_CARD("このカード"),
    SAME_NAME("同名カード"),
    CATEGORY("カテゴリ");

    companion object {
        val all: List<LimitScope> get() = entries
    }
}

/**
 * 【条件】で参照できる出来事。「〜した場合」という書き方を可能にする。
 */
@Serializable
enum class GameEventType(val label: String, val isPlayerEvent: Boolean = false) {
    SUMMONED("召喚・特殊召喚された"),
    NORMAL_SUMMONED("召喚された"),
    SPECIAL_SUMMONED("特殊召喚された"),
    DESTROYED("破壊された"),
    SENT_TO_GRAVEYARD("墓地へ送られた"),
    BANISHED("除外された"),
    ACTIVATED("効果を発動した"),
    ATTACK_DECLARED("攻撃宣言した"),
    DAMAGE_TAKEN("ダメージを受けた", isPlayerEvent = true),
    LIFE_RECOVERED("ライフを回復した", isPlayerEvent = true),
    CARD_DRAWN("カードをドローした", isPlayerEvent = true);

    companion object {
        val all: List<GameEventType> get() = entries
    }
}

/** 発動が任意か強制か。 */
@Serializable
enum class ActivationMode(val label: String, val suffix: String) {
    OPTIONAL("発動できる（任意）", "できる"),
    MANDATORY("発動する（強制）", "する");

    companion object {
        val all: List<ActivationMode> get() = entries
    }
}

/**
 * 効果が発動するタイミング。
 *
 * 旧データ互換のために残している。現在は【条件】のイベント条件
 * （[GameEventType]）で表現する。
 */
@Serializable
enum class EffectTiming(val label: String) {
    ON_ACTIVATE("発動時"),
    IGNITION("自分のメインフェイズに"),
    ON_SUMMON("このカードが召喚・特殊召喚に成功した時"),
    ON_DESTROYED("このカードが破壊された時"),
    ON_ATTACK("このカードが攻撃宣言した時");

    companion object {
        /** モンスターカードで選べるタイミング。 */
        val forMonster: List<EffectTiming>
            get() = listOf(IGNITION, ON_SUMMON, ON_DESTROYED, ON_ATTACK)

        /** 魔法・罠カードのタイミングは発動時のみ。 */
        val forSpellTrap: List<EffectTiming> get() = listOf(ON_ACTIVATE)
    }
}
