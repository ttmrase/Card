package com.cardforge.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// 目的語のフィルタ: 「闇属性の」「レベル4以下の」「『アララギ』の」など。
// ---------------------------------------------------------------------------

@Serializable
sealed interface CardFilter

@Serializable
@SerialName("kind")
data class KindFilter(val kind: CardKind) : CardFilter

@Serializable
@SerialName("attribute")
data class AttributeFilter(val attributeId: String) : CardFilter

@Serializable
@SerialName("race")
data class RaceFilter(val raceId: String) : CardFilter

@Serializable
@SerialName("category")
data class CategoryFilter(val categoryId: String) : CardFilter

@Serializable
@SerialName("level")
data class LevelFilter(val cmp: Cmp, val value: Int) : CardFilter

@Serializable
@SerialName("atk")
data class AtkFilter(val cmp: Cmp, val value: Int) : CardFilter

@Serializable
@SerialName("def")
data class DefFilter(val cmp: Cmp, val value: Int) : CardFilter

@Serializable
@SerialName("position")
data class PositionFilter(val position: Position) : CardFilter

@Serializable
@SerialName("name")
data class NameFilter(val text: String) : CardFilter

/** その条件に当てはまらないカード。「〜を除く」と書きたいときに使う。 */
@Serializable
@SerialName("notFilter")
data class NotFilter(val filter: CardFilter) : CardFilter

/**
 * 直前の処理で扱ったカードと同じ名前のカード。
 * [exclude] が true なら逆に、同名のカードを除く。
 */
@Serializable
@SerialName("affectedName")
data class AffectedNameFilter(val exclude: Boolean = false) : CardFilter

/** 並べた条件のうち、どれか1つに当てはまればよい。 */
@Serializable
@SerialName("anyFilter")
data class AnyFilter(val filters: List<CardFilter> = emptyList()) : CardFilter

/**
 * この効果を持つカードによって特殊召喚されたカード。
 * 「このカードの効果によって特殊召喚されたモンスター」と書きたいときに使う。
 */
@Serializable
@SerialName("summonedByThis")
data class SummonedByThisFilter(val enabled: Boolean = true) : CardFilter

// ---------------------------------------------------------------------------
// 主語・目的語・修飾語をひとまとめにした「対象指定」。
// 例) 『相手フィールドの』『闇属性モンスター』『1体』を『選んで』
// ---------------------------------------------------------------------------

@Serializable
data class CardScope(
    val who: PlayerRef = PlayerRef.OPPONENT,
    val zone: ZoneType = ZoneType.MONSTER_ZONE,
    val filters: List<CardFilter> = emptyList(),
    val count: Int = 1,
    val selection: SelectionMode = SelectionMode.CHOOSE,
    /** この効果を持つカード自身だけを指す。true のとき他の指定は見ない。 */
    val selfOnly: Boolean = false,
    /**
     * 枚数を「〜の数だけ」のように別のカードの枚数から決める。
     * null なら [count] をそのまま使う。
     */
    val countSpec: ValueSpec? = null,
    /**
     * 「〜まで」。対象がその数に足りなくても、あるだけ処理する。
     * これを付けた指定は、発動時の「最後まで処理できるか」の判定でも数に数えない。
     */
    val upTo: Boolean = false,
    /** 「このカードを除く」。この効果を持つカード自身を対象から外す。 */
    val excludeSelf: Boolean = false
) {
    /** 枚数の指定。[countSpec] があればそちらが優先される。 */
    val countValue: ValueSpec get() = countSpec ?: FixedValue(count)
}

// ---------------------------------------------------------------------------
// 数値の指定。固定値のほか「条件を満たすカードの枚数×係数」を書ける。
// ---------------------------------------------------------------------------

@Serializable
sealed interface ValueSpec

@Serializable
@SerialName("fixedValue")
data class FixedValue(val value: Int = 0) : ValueSpec

/** [base] ＋ 直前の処理で扱ったカードの枚数 × [multiplier]。 */
@Serializable
@SerialName("affectedCount")
data class AffectedCountValue(
    val multiplier: Int = 1,
    val base: Int = 0
) : ValueSpec

/** [base] ＋ [scope] のカードに乗っているカウンターの数 × [multiplier]。 */
@Serializable
@SerialName("counterValue")
data class CounterValue(
    val scope: CardScope = CardScope(who = PlayerRef.SELF, selection = SelectionMode.ALL),
    val counterId: String? = null,
    val multiplier: Int = 1,
    val base: Int = 0
) : ValueSpec

/** [base] ＋ [scope] に当てはまるカードの枚数 × [multiplier]。 */
@Serializable
@SerialName("countValue")
data class CountValue(
    val scope: CardScope = CardScope(selection = SelectionMode.ALL),
    val multiplier: Int = 100,
    val base: Int = 0
) : ValueSpec

// ---------------------------------------------------------------------------
// 述語: 効果本体。
// ---------------------------------------------------------------------------

@Serializable
sealed interface Action

@Serializable
@SerialName("destroy")
data class DestroyAction(val scope: CardScope) : Action

@Serializable
@SerialName("banish")
data class BanishAction(val scope: CardScope) : Action

@Serializable
@SerialName("toHand")
data class ToHandAction(val scope: CardScope) : Action

@Serializable
@SerialName("toGrave")
data class ToGraveAction(val scope: CardScope) : Action

@Serializable
@SerialName("toDeck")
data class ToDeckAction(val scope: CardScope, val toBottom: Boolean = false) : Action

/** [scope] で選んだモンスターを [controller] のモンスターゾーンに特殊召喚する。 */
@Serializable
@SerialName("specialSummon")
data class SpecialSummonAction(
    val scope: CardScope,
    val position: Position = Position.ATTACK,
    val controller: PlayerRef = PlayerRef.SELF,
    /**
     * 選べる表示形式。2つ以上あれば、効果を処理するときにプレイヤーが選ぶ。
     * 空のときは [position] で固定。
     */
    val positionChoices: List<Position> = emptyList()
) : Action {
    val choices: List<Position> get() = positionChoices.ifEmpty { listOf(position) }
}

@Serializable
@SerialName("modifyStat")
data class ModifyStatAction(
    val scope: CardScope,
    val stat: StatKind,
    val delta: Int = 0,
    /** 指定があればこちらを使う。無ければ [delta] の固定値。 */
    val deltaValue: ValueSpec? = null
) : Action {
    val amountSpec: ValueSpec get() = deltaValue ?: FixedValue(delta)
}

@Serializable
@SerialName("changePosition")
data class ChangePositionAction(val scope: CardScope, val position: Position) : Action

/**
 * 「このターン、〜は…を特殊召喚できない」という召喚の制限。
 *
 * [except] が true なら「[filters] に当てはまるもの**以外**を出せない」、
 * false なら「[filters] に当てはまるものを出せない」という意味になる。
 * 制限はこのターンの間だけ続く。
 */
@Serializable
@SerialName("restrictSummon")
data class RestrictSummonAction(
    val who: PlayerRef = PlayerRef.SELF,
    val summon: SummonKind = SummonKind.SPECIAL,
    val filters: List<CardFilter> = emptyList(),
    val except: Boolean = true,
    /** 「このカードを発動するターン」と書きたいときに true。 */
    val fromActivation: Boolean = true
) : Action

/** 素材依存の特殊召喚で、素材に求める条件。 */
@Serializable
enum class MaterialRequirement(val label: String) {
    /** レベルの合計が、出すモンスターのレベルとぴったり同じ。 */
    LEVEL_EXACT("レベルの合計がぴったり同じ"),

    /** レベルの合計が、出すモンスターのレベル以上。 */
    LEVEL_OR_MORE("レベルの合計が同じか大きい"),

    /** 体数だけを見る（[MaterialSummonAction.count] 体）。 */
    COUNT("決まった体数");

    companion object {
        val all: List<MaterialRequirement> get() = entries
    }
}

/**
 * 素材を送って特殊召喚する。遊戯王で言う儀式召喚や融合召喚にあたる。
 *
 * [material] のカードを [destination] へ送り、[summon] のモンスターを特殊召喚する。
 * 素材に求める条件は [requirement] で決める。
 */
@Serializable
@SerialName("ritualSummon")
data class MaterialSummonAction(
    /** 特殊召喚するモンスターの居場所と条件。 */
    val summon: CardScope = CardScope(
        who = PlayerRef.SELF,
        zone = ZoneType.HAND,
        filters = listOf(KindFilter(CardKind.MONSTER)),
        count = 1
    ),
    /** 素材として送るカードの居場所と条件。 */
    val material: CardScope = CardScope(
        who = PlayerRef.SELF,
        zone = ZoneType.FIELD,
        filters = listOf(KindFilter(CardKind.MONSTER)),
        selection = SelectionMode.CHOOSE
    ),
    /** 送った素材の行き先。 */
    val destination: MoveDestination = MoveDestination.GRAVEYARD,
    val requirement: MaterialRequirement = MaterialRequirement.LEVEL_OR_MORE,
    /** [MaterialRequirement.COUNT] のときに必要な体数。 */
    val count: Int = 1,
    /** 選べる表示形式。2つ以上あれば処理のときにプレイヤーが選ぶ。 */
    val positionChoices: List<Position> = listOf(Position.ATTACK)
) : Action {
    val choices: List<Position> get() = positionChoices.ifEmpty { listOf(Position.ATTACK) }
}

/** カードにカウンターを乗せる。 */
@Serializable
@SerialName("addCounter")
data class AddCounterAction(
    val scope: CardScope,
    val counterId: String? = null,
    val amount: Int = 1
) : Action

/** カードからカウンターを取り除く。 */
@Serializable
@SerialName("removeCounter")
data class RemoveCounterAction(
    val scope: CardScope,
    val counterId: String? = null,
    val amount: Int = 1
) : Action

/** トークンを特殊召喚する。 */
@Serializable
@SerialName("createToken")
data class CreateTokenAction(
    /** 出すトークンのカード ID。 */
    val tokenCardId: String? = null,
    val count: Int = 1,
    val controller: PlayerRef = PlayerRef.SELF,
    val positionChoices: List<Position> = listOf(Position.ATTACK)
) : Action {
    val choices: List<Position> get() = positionChoices.ifEmpty { listOf(Position.ATTACK) }
}

/**
 * 処理の行き先を差し替える。永続の効果に書いて使う。
 *
 * 「墓地へ送られる代わりに除外する」のように、墓地へ行く処理に割り込む。
 */
@Serializable
@SerialName("replaceDestination")
data class ReplaceDestinationAction(
    val scope: CardScope,
    val to: MoveDestination = MoveDestination.BANISHED
) : Action

/**
 * フェイズやターンの進み方を変える述語。
 * 効果の処理が全て終わってから適用される。
 */
@Serializable
@SerialName("advancePhase")
data class AdvancePhaseAction(val kind: PhaseAdvance = PhaseAdvance.SKIP_PHASE) : Action

/**
 * 「〜は次の効果を得る」という、効果そのものを与える述語。
 *
 * 【発動タイプ】を「永続」にした効果に書くと、このカードが【場所】にある間だけ
 * [scope] に当てはまるカードが [granted] の効果を持つようになる。
 *
 * 与えられた効果はそのカード自身の効果として扱われる（発動も、永続の適用も同じ）。
 * ただし「効果を与える効果」を与えることはできない。
 */
@Serializable
@SerialName("grantEffect")
data class GrantEffectAction(
    val scope: CardScope,
    val granted: EffectClause = EffectClause()
) : Action

/**
 * カードを相手に見せる。
 *
 * [RevealDuration.MOMENT] はその場で見せるだけ、それ以外は指定した長さのあいだ
 * 表にしたままにする（相手の画面にも中身が出る）。
 */
@Serializable
@SerialName("reveal")
data class RevealAction(
    val scope: CardScope = CardScope(
        who = PlayerRef.SELF,
        zone = ZoneType.HAND,
        selection = SelectionMode.ALL
    ),
    val duration: RevealDuration = RevealDuration.MOMENT
) : Action

@Serializable
@SerialName("draw")
data class DrawAction(
    val who: PlayerRef,
    val count: Int = 1,
    /** 「〜の数だけドローする」と書きたいときの枚数指定。 */
    val countValue: ValueSpec? = null
) : Action {
    val countSpec: ValueSpec get() = countValue ?: FixedValue(count)
}

@Serializable
@SerialName("damage")
data class DamageAction(
    val who: PlayerRef,
    val amount: Int = 0,
    val amountValue: ValueSpec? = null
) : Action {
    val amountSpec: ValueSpec get() = amountValue ?: FixedValue(amount)
}

@Serializable
@SerialName("recover")
data class RecoverAction(
    val who: PlayerRef,
    val amount: Int = 0,
    val amountValue: ValueSpec? = null
) : Action {
    val amountSpec: ValueSpec get() = amountValue ?: FixedValue(amount)
}

@Serializable
@SerialName("discard")
data class DiscardAction(
    val who: PlayerRef,
    val count: Int = 1,
    val random: Boolean = false,
    /** 「〜の数だけ捨てさせる」と書きたいときの枚数指定。 */
    val countValue: ValueSpec? = null
) : Action {
    val countSpec: ValueSpec get() = countValue ?: FixedValue(count)
}

@Serializable
@SerialName("mill")
data class MillAction(
    val who: PlayerRef,
    val count: Int = 1,
    /** 「〜の数だけ墓地へ送る」と書きたいときの枚数指定。 */
    val countValue: ValueSpec? = null
) : Action {
    val countSpec: ValueSpec get() = countValue ?: FixedValue(count)
}

/**
 * 耐性を与える。永続の効果に書けばその間ずっと、
 * 発動する効果に書けばそのターンの間だけ適用される。
 */
@Serializable
@SerialName("grantProtection")
data class GrantProtectionAction(
    val scope: CardScope,
    val kind: ProtectionKind = ProtectionKind.OPPONENT_EFFECTS,
    /** 誰の効果に対する耐性か。戦闘の耐性では見ない。 */
    val from: PlayerRef = PlayerRef.OPPONENT
) : Action

/** 攻撃できなくする。永続なら常時、発動ならそのターンの間。 */
@Serializable
@SerialName("preventAttack")
data class PreventAttackAction(val scope: CardScope) : Action

/** 魔法・罠ゾーンに裏側でセットする。 */
@Serializable
@SerialName("setSpellTrap")
data class SetSpellTrapAction(val scope: CardScope) : Action

/** 魔法・罠ゾーンに表側で置く。発動はしないので、永続の効果だけが働く。 */
@Serializable
@SerialName("placeSpellTrap")
data class PlaceSpellTrapAction(val scope: CardScope) : Action

/** そのカードを発動する。 */
@Serializable
@SerialName("activateCard")
data class ActivateCardAction(val scope: CardScope) : Action

/** 相手が発動した効果を無効にし破壊する（罠カード向け）。 */
@Serializable
@SerialName("negate")
data object NegateAction : Action

// ---------------------------------------------------------------------------
// 【条件】
// ---------------------------------------------------------------------------

@Serializable
sealed interface Condition

/** 指定した領域に条件を満たすカードが [atLeast] 枚以上存在する（[negate] で「存在しない」）。 */
@Serializable
@SerialName("exists")
data class CardExistsCondition(
    val scope: CardScope,
    val atLeast: Int = 1,
    val negate: Boolean = false
) : Condition

@Serializable
@SerialName("life")
data class LifeCondition(val who: PlayerRef, val cmp: Cmp, val value: Int) : Condition

/**
 * 「〜した場合」というイベントの条件。
 *
 * この条件を持つ効果は、その出来事が起きたときにだけ発動する誘発効果になる。
 * 持たない効果は、自分のメインフェイズに手動で発動する起動効果として扱う。
 */
@Serializable
@SerialName("event")
data class EventCondition(
    val event: GameEventType,
    /** 出来事を起こしたカード（またはプレイヤー）の持ち主。 */
    val who: PlayerRef = PlayerRef.OPPONENT,
    /** 「このカードが〜した場合」にする。true のとき [who] と [filters] は見ない。 */
    val selfOnly: Boolean = false,
    val filters: List<CardFilter> = emptyList(),
    /**
     * 「相手の効果によって」のように、その出来事の原因を限定する。
     * [selfOnly] が true でもこの指定は見る。
     */
    val cause: CauseFilter = CauseFilter.ANY,
    /**
     * 「〜した場合」か「〜したターン」か。
     * [EventWindow.THIS_TURN] にすると誘発効果ではなく、
     * 「このターンにそれが起きていれば発動できる」という条件になる。
     */
    val window: EventWindow = EventWindow.IMMEDIATE,
    /**
     * その出来事を起こしたカードを限定する。
     * 「罠カードの対象に取られた場合」のように書きたいときに使う。
     */
    val sourceFilters: List<CardFilter> = emptyList()
) : Condition

/** 並べた条件のうち、どれか1つを満たせばよい。 */
@Serializable
@SerialName("anyOf")
data class AnyOfCondition(val conditions: List<Condition> = emptyList()) : Condition

/** 「または」の入れ子を開いて、全ての条件を平らに並べる。 */
fun flatten(conditions: List<Condition>): List<Condition> =
    conditions.flatMap { condition ->
        if (condition is AnyOfCondition) flatten(condition.conditions) else listOf(condition)
    }

/**
 * この効果を持つカード自身が、指定した領域にある場合。
 * 「送られた場所によって」のような場合分けに使う。
 */
@Serializable
@SerialName("selfZone")
data class SelfZoneCondition(val zones: List<ZoneType> = emptyList()) : Condition

/** 「〇〇カウンターが n 個以上乗っている場合」という条件。 */
@Serializable
@SerialName("counter")
data class CounterCondition(
    val scope: CardScope = CardScope(selfOnly = true),
    val counterId: String? = null,
    val cmp: Cmp = Cmp.GE,
    val value: Int = 1
) : Condition

/** 指定したフェイズにだけ発動できる。 */
@Serializable
@SerialName("phase")
data class PhaseCondition(val phases: List<Phase> = emptyList()) : Condition

@Serializable
@SerialName("zoneCount")
data class ZoneCountCondition(
    val who: PlayerRef,
    val zone: ZoneType,
    val cmp: Cmp,
    val value: Int
) : Condition

// ---------------------------------------------------------------------------
// 【コスト】
// ---------------------------------------------------------------------------

@Serializable
sealed interface Cost

@Serializable
@SerialName("payLife")
data class PayLifeCost(val amount: Int) : Cost

@Serializable
@SerialName("discardCost")
data class DiscardCost(val count: Int, val filters: List<CardFilter> = emptyList()) : Cost

/** 自分フィールドのモンスターをリリースする。 */
@Serializable
@SerialName("tributeCost")
data class TributeCost(val count: Int, val filters: List<CardFilter> = emptyList()) : Cost

@Serializable
@SerialName("banishGraveCost")
data class BanishFromGraveCost(
    val count: Int,
    val filters: List<CardFilter> = emptyList()
) : Cost

@Serializable
@SerialName("millCost")
data class MillCost(val count: Int) : Cost

/** コストで動かす先。 */
@Serializable
enum class MoveDestination(val label: String) {
    GRAVEYARD("墓地へ送る"),
    BANISHED("除外する"),
    HAND("手札に戻す"),
    DECK_TOP("デッキの一番上に戻す"),
    DECK_BOTTOM("デッキの一番下に戻す");

    companion object {
        val all: List<MoveDestination> get() = entries
    }
}

/**
 * 対象指定で選んだカードを動かすコスト。
 *
 * 効果と同じ対象指定が使えるので、「墓地のカードをデッキに戻す」
 * 「自分フィールドの闇属性モンスターをリリースする」なども書ける。
 */
@Serializable
@SerialName("moveCost")
data class MoveCost(
    val scope: CardScope,
    val destination: MoveDestination = MoveDestination.GRAVEYARD
) : Cost

/**
 * 発動するこのカード自身をコストにする。
 * 手札で発動するモンスターを「コストとして墓地へ送る」形にしたいときに使う。
 */
@Serializable
@SerialName("selfCost")
data class DiscardSelfCost(val banish: Boolean = false) : Cost

/** コストとしてカウンターを取り除く。 */
@Serializable
@SerialName("counterCost")
data class CounterCost(
    val scope: CardScope = CardScope(selfOnly = true),
    val counterId: String? = null,
    val amount: Int = 1
) : Cost

/** コストとしてカードを見せる（公開する）。 */
@Serializable
@SerialName("revealCost")
data class RevealCost(
    val scope: CardScope = CardScope(
        who = PlayerRef.SELF,
        zone = ZoneType.HAND,
        selection = SelectionMode.ALL
    ),
    val duration: RevealDuration = RevealDuration.MOMENT
) : Cost

// ---------------------------------------------------------------------------
// 【制限】
// ---------------------------------------------------------------------------

/**
 * 発動回数の制限。「1ターンに[times]度まで」を [scope] の単位で数える。
 *
 * 効果番号より前に書いた制限はカードの全ての効果をまとめて数え、
 * 効果番号の直後に書いた制限はその番号の効果だけを数える。
 */
@Serializable
data class UsageLimit(
    val scope: LimitScope = LimitScope.THIS_CARD,
    val times: Int = 1,
    /** [LimitScope.CATEGORY] のとき数える対象のカテゴリ。null ならこのカードのカテゴリ。 */
    val categoryId: String? = null,
    /**
     * 掛ける効果の番号（0 始まり）。空なら全ての効果に掛かる。
     * 効果番号より前に書く制限でだけ意味を持つ。
     */
    val clauseIndices: List<Int> = emptyList(),
    /**
     * [LimitApplies.TOGETHER] なら対象の効果をまとめて数え、
     * [LimitApplies.EACH] なら効果ごとに別々に数える。
     */
    val applies: LimitApplies = LimitApplies.TOGETHER
) {
    /** [index] 番目の効果に掛かるか。 */
    fun coversClause(index: Int): Boolean =
        clauseIndices.isEmpty() || index in clauseIndices
}

// ---------------------------------------------------------------------------
// 効果テキスト本体。
//
// EffectText 直下の場所・条件・コストは効果番号より前に書かれたもので、
// カードの全ての効果に適用される。EffectClause 側のものは、その番号の
// 効果にのみ適用される。
// ---------------------------------------------------------------------------

/**
 * 場合分けの1項目。[conditions] を満たしたときに [actions] を処理する。
 * カードテキストでは「●条件：効果」として表示される。
 */
/**
 * 【制限】としての召喚の縛り。
 *
 * 効果ではなく発動そのものに付く制限なので、効果を無効にされても掛かったままになる。
 * 掛かるのは発動したターンの間だけ。
 *
 * [except] が true なら「[filters] に当てはまるもの**以外**を出せない」、
 * false なら「[filters] に当てはまるものを出せない」。
 */
@Serializable
data class SummonLock(
    val who: PlayerRef = PlayerRef.SELF,
    val summon: SummonKind = SummonKind.SPECIAL,
    val filters: List<CardFilter> = emptyList(),
    val except: Boolean = true
)

@Serializable
data class EffectBranch(
    val conditions: List<Condition> = emptyList(),
    val actions: List<Action> = emptyList(),
    /** 「〜することができる」と書く処理の番号（[actions] の位置）。 */
    val optionalSteps: List<Int> = emptyList(),
    /** 直前の処理と1つにまとめる処理の番号。「〜し、〜する」とつながる。 */
    val linkedSteps: List<Int> = emptyList()
) {
    fun isOptionalStep(index: Int): Boolean = index in optionalSteps
    fun isLinkedStep(index: Int): Boolean = index > 0 && index in linkedSteps
}

@Serializable
data class EffectClause(
    val timing: EffectTiming = EffectTiming.ON_ACTIVATE,
    val locations: List<ActivationLocation> = emptyList(),
    val conditions: List<Condition> = emptyList(),
    val costs: List<Cost> = emptyList(),
    val actions: List<Action> = emptyList(),
    /** 場合分け。共通の [actions] を処理したあとに評価する。 */
    val branches: List<EffectBranch> = emptyList(),
    val branchMode: BranchMode = BranchMode.FIRST_MATCH,
    /** 任意発動か強制発動か。 */
    val mode: ActivationMode = ActivationMode.OPTIONAL,
    /** この効果だけの制限。 */
    val limits: List<UsageLimit> = emptyList(),
    /** この効果だけの【発動後】。null ならカード共通の指定に従う。 */
    val afterActivation: AfterActivation? = null,
    /**
     * 誘発即時効果。相手のターンや、相手の行動への割り込みでも発動できる。
     * 【場所】がフィールド以外の効果は、書かなくても割り込める。
     */
    val quick: Boolean = false,
    /** 「〜することができる」と書く処理の番号（[actions] の位置）。 */
    val optionalSteps: List<Int> = emptyList(),
    /**
     * 直前の処理と1つにまとめる処理の番号。
     *
     * まとめた処理は「〜し、〜する」とつながり、**まとめて一度に**行うか行わないかを決める。
     * 「手札を相手に見せ、デッキから墓地へ送ることができる」のように、
     * 片方だけを行えないようにしたいときに使う。
     */
    val linkedSteps: List<Int> = emptyList(),
    /** この効果の発動に付く召喚の制限。 */
    val summonLocks: List<SummonLock> = emptyList(),
    /** 旧データ互換。制限欄が空でこれが true なら「1ターンに1度」として扱う。 */
    val oncePerTurn: Boolean = false
) {
    fun isOptionalStep(index: Int): Boolean = index in optionalSteps
    fun isLinkedStep(index: Int): Boolean = index > 0 && index in linkedSteps
}

/**
 * 処理のまとまり。[start] から [endInclusive] までが1つの手順として扱われる。
 * [optional] なら「〜することができる」。
 */
data class StepUnit(
    val start: Int,
    val endInclusive: Int,
    val optional: Boolean
) {
    val indices: IntRange get() = start..endInclusive
}

/**
 * 処理をまとまりに分ける。
 * 直前の処理とつながっている処理は、同じまとまりに入る。
 */
fun stepUnits(
    size: Int,
    optionalSteps: List<Int>,
    linkedSteps: List<Int>
): List<StepUnit> {
    val units = mutableListOf<StepUnit>()
    var start = 0
    for (index in 0 until size) {
        val linked = index > 0 && index in linkedSteps
        if (index > 0 && !linked) {
            units += StepUnit(start, index - 1, start in optionalSteps)
            start = index
        }
    }
    if (size > 0) units += StepUnit(start, size - 1, start in optionalSteps)
    return units
}

/** 効果に、処理すべき内容があるか。 */
val EffectClause.hasWork: Boolean
    get() = actions.isNotEmpty() || branches.any { it.actions.isNotEmpty() }

@Serializable
data class EffectText(
    val locations: List<ActivationLocation> = emptyList(),
    val conditions: List<Condition> = emptyList(),
    val costs: List<Cost> = emptyList(),
    /** 全ての効果をまとめて数える制限。 */
    val limits: List<UsageLimit> = emptyList(),
    /** どの効果を発動しても掛かる召喚の制限。 */
    val summonLocks: List<SummonLock> = emptyList(),
    /** 【発動後】。null なら [AfterActivation.DEFAULT]（墓地へ送る）。 */
    val afterActivation: AfterActivation? = null,
    val clauses: List<EffectClause> = emptyList()
) {
    val isEmpty: Boolean get() = clauses.none { it.hasWork }

    /** [index] 番目の効果に適用される場所（共通指定を含む）。 */
    fun locationsFor(index: Int): List<ActivationLocation> {
        val own = clauses.getOrNull(index)?.locations.orEmpty()
        return if (own.isNotEmpty()) own else locations
    }

    fun conditionsFor(index: Int): List<Condition> =
        conditions + clauses.getOrNull(index)?.conditions.orEmpty() + legacyTrigger(index)

    /** 旧データの timing を、同じ意味のイベント条件として読み替える。 */
    private fun legacyTrigger(index: Int): List<Condition> {
        val clause = clauses.getOrNull(index) ?: return emptyList()
        if (clause.conditions.any { it is EventCondition }) return emptyList()
        if (conditions.any { it is EventCondition }) return emptyList()
        val event = when (clause.timing) {
            EffectTiming.ON_SUMMON -> GameEventType.SUMMONED
            EffectTiming.ON_DESTROYED -> GameEventType.DESTROYED
            EffectTiming.ON_ATTACK -> GameEventType.ATTACK_DECLARED
            EffectTiming.IGNITION, EffectTiming.ON_ACTIVATE -> return emptyList()
        }
        return listOf(EventCondition(event = event, selfOnly = true))
    }

    fun costsFor(index: Int): List<Cost> =
        costs + clauses.getOrNull(index)?.costs.orEmpty()

    /** [index] 番目の効果にだけ掛かる制限（旧データの oncePerTurn を含む）。 */
    fun clauseLimitsFor(index: Int): List<UsageLimit> {
        val clause = clauses.getOrNull(index) ?: return emptyList()
        if (clause.limits.isNotEmpty()) return clause.limits
        return if (clause.oncePerTurn) listOf(UsageLimit(LimitScope.THIS_CARD, 1))
        else emptyList()
    }

    /** [index] 番目の効果を発動したときに掛かる召喚の制限（共通指定を含む）。 */
    fun summonLocksFor(index: Int): List<SummonLock> =
        summonLocks + clauses.getOrNull(index)?.summonLocks.orEmpty()

    /** [index] 番目の効果が誘発即時（相手ターンにも発動できる）か。 */
    fun isQuick(index: Int): Boolean = clauses.getOrNull(index)?.quick == true

    /**
     * [index] 番目の効果の【発動後】。
     * 指定が無い場合、魔法・罠は「墓地へ送る」、モンスターは「そのまま残す」。
     */
    fun afterActivationFor(index: Int, kind: CardKind): AfterActivation =
        clauses.getOrNull(index)?.afterActivation
            ?: afterActivation
            ?: defaultAfterActivation(kind)

    /** [index] 番目の効果が発動できるフェイズ。指定が無ければ空。 */
    fun phasesFor(index: Int): List<Phase> =
        flatten(conditionsFor(index)).filterIsInstance<PhaseCondition>().flatMap { it.phases }

    /**
     * [index] 番目の効果が持つ誘発条件。
     * 「〜したターン」は発動条件なので誘発条件には数えない。
     */
    fun triggersFor(index: Int): List<EventCondition> =
        flatten(conditionsFor(index))
            .filterIsInstance<EventCondition>()
            .filter { it.window == EventWindow.IMMEDIATE }

    /** イベント条件を持つ効果は誘発効果、持たない効果は起動効果。 */
    fun isTriggered(index: Int): Boolean =
        clauses.getOrNull(index)?.mode?.isStandalone == true && triggersFor(index).isNotEmpty()

    /** 永続の効果は発動せず、【場所】にある限り適用される。 */
    fun isContinuous(index: Int): Boolean =
        clauses.getOrNull(index)?.mode?.isContinuous == true

    /** そのカードの発動時にだけ処理される効果。 */
    fun isOnActivation(index: Int): Boolean =
        clauses.getOrNull(index)?.mode?.isOnActivation == true

    /** そのカードの発動時に処理する効果の番号。 */
    fun onActivationClauses(): List<Int> =
        clauses.indices.filter { isOnActivation(it) && clauses[it].hasWork }

    /** 発動時処理か永続の効果があれば、そのカード自体を「発動」できる。 */
    fun supportsCardActivation(): Boolean =
        clauses.indices.any { (isOnActivation(it) || isContinuous(it)) }

    /** カードの発動そのものに対する【発動後】。 */
    fun afterActivationForCard(kind: CardKind): AfterActivation =
        afterActivation ?: defaultAfterActivation(kind)

    companion object {
        fun defaultAfterActivation(kind: CardKind): AfterActivation =
            if (kind == CardKind.MONSTER) AfterActivation.STAY_ON_FIELD
            else AfterActivation.TO_GRAVE
    }
}
