package com.cardforge.game

import com.cardforge.model.*
import com.cardforge.text.EffectTextRenderer
import kotlin.math.min

data class CardLocation(val player: PlayerState, val zone: ZoneType, val index: Int)

/**
 * 盤面で起きた出来事。【条件】のイベント条件（「〜した場合」）の判定に使う。
 * [card] はその出来事の対象になったカード、[playerIndex] はその持ち主
 * （プレイヤーに対する出来事ならそのプレイヤー）。
 */
data class GameEvent(
    val type: GameEventType,
    val playerIndex: Int,
    val card: CardInstance? = null,
    /** 何がこの出来事を起こしたか。[GameEngine.emit] が解決中の原因を書き込む。 */
    val cause: EventCause = EventCause.UNKNOWN,
    /** [EventCause.EFFECT] のとき、その効果を発動したプレイヤー。 */
    val causePlayer: Int? = null,
    /** その出来事を起こしたカード。「罠カードの対象に取られた」などの判定に使う。 */
    val sourceCard: CardInstance? = null
)

/**
 * デュエルのルール処理と効果解決を行うエンジン。
 *
 * プレイヤーへの問い合わせは全て [Interaction] 経由なので、同じコードで
 * 手動操作の対戦と AI の思考の両方を動かせる。
 */
class GameEngine(
    val state: GameState,
    private val interaction: Interaction
) {

    // -----------------------------------------------------------------------
    // 出来事の原因
    // -----------------------------------------------------------------------

    /** いま処理している出来事の原因。[emit] が出来事に書き込む。 */
    private var causeKind = EventCause.UNKNOWN
    private var causePlayer: Int? = null
    private var causeSource: CardInstance? = null

    /** [block] の中で起きた出来事に、この原因を付ける。 */
    private inline fun <R> withCause(
        kind: EventCause,
        player: Int?,
        source: CardInstance?,
        block: () -> R
    ): R {
        val prevKind = causeKind
        val prevPlayer = causePlayer
        val prevSource = causeSource
        causeKind = kind
        causePlayer = player
        causeSource = source
        try {
            return block()
        } finally {
            causeKind = prevKind
            causePlayer = prevPlayer
            causeSource = prevSource
        }
    }

    /** 効果の処理が終わってから適用する、フェイズの進め方の変更。 */
    private var pendingAdvance: PhaseAdvance? = null

    /** 発動の入れ子の深さ。0 に戻ったときに [pendingAdvance] を適用する。 */
    private var activationDepth = 0

    /**
     * いま発動を処理している最中のカード。
     * 解決の途中で同じカードをもう一度発動できてしまうのを防ぐ。
     */
    private val resolvingCards = mutableSetOf<String>()

    /** 効果が効果を呼ぶ連鎖の暴走を防ぐための深さ制限。 */
    private var triggerDepth = 0
    private val MAX_TRIGGER_DEPTH = 4
    private var responseDepth = 0

    private fun log(message: String) = state.addLog(message)

    // =======================================================================
    // 位置の特定と移動
    // =======================================================================

    fun zoneCards(player: PlayerState, zone: ZoneType): List<CardInstance> = when (zone) {
        ZoneType.MONSTER_ZONE -> player.monsterZones.filterNotNull()
        ZoneType.SPELL_TRAP_ZONE -> player.spellTrapZones.filterNotNull()
        ZoneType.FIELD -> player.monsterZones.filterNotNull() + player.spellTrapZones.filterNotNull()
        ZoneType.HAND -> player.hand.toList()
        ZoneType.DECK -> player.deck.toList()
        ZoneType.GRAVEYARD -> player.graveyard.toList()
        ZoneType.BANISHED -> player.banished.toList()
    }

    fun locate(inst: CardInstance): CardLocation? {
        for (player in state.players) {
            val m = player.monsterZones.indexOfFirst { it === inst }
            if (m >= 0) return CardLocation(player, ZoneType.MONSTER_ZONE, m)

            val s = player.spellTrapZones.indexOfFirst { it === inst }
            if (s >= 0) return CardLocation(player, ZoneType.SPELL_TRAP_ZONE, s)

            val h = player.hand.indexOfFirst { it === inst }
            if (h >= 0) return CardLocation(player, ZoneType.HAND, h)

            val d = player.deck.indexOfFirst { it === inst }
            if (d >= 0) return CardLocation(player, ZoneType.DECK, d)

            val g = player.graveyard.indexOfFirst { it === inst }
            if (g >= 0) return CardLocation(player, ZoneType.GRAVEYARD, g)

            val b = player.banished.indexOfFirst { it === inst }
            if (b >= 0) return CardLocation(player, ZoneType.BANISHED, b)
        }
        return null
    }

    fun isOnField(inst: CardInstance): Boolean {
        val zone = locate(inst)?.zone ?: return false
        return zone == ZoneType.MONSTER_ZONE || zone == ZoneType.SPELL_TRAP_ZONE
    }

    private fun removeFromCurrent(inst: CardInstance): CardLocation? {
        val loc = locate(inst) ?: return null
        when (loc.zone) {
            ZoneType.MONSTER_ZONE -> loc.player.monsterZones[loc.index] = null
            ZoneType.SPELL_TRAP_ZONE -> loc.player.spellTrapZones[loc.index] = null
            ZoneType.HAND -> loc.player.hand.remove(inst)
            ZoneType.DECK -> loc.player.deck.remove(inst)
            ZoneType.GRAVEYARD -> loc.player.graveyard.remove(inst)
            ZoneType.BANISHED -> loc.player.banished.remove(inst)
            ZoneType.FIELD -> Unit
        }
        return loc
    }

    /** フィールドを離れたカードの一時的な状態をリセットする。 */
    private fun resetInstance(inst: CardInstance) {
        inst.atkMod = 0
        inst.defMod = 0
        inst.faceDown = false
        inst.position = Position.ATTACK
        inst.hasAttacked = false
        inst.positionChangedThisTurn = false
        inst.summonedOnTurn = -1
        inst.setOnTurn = -1
        inst.turnProtections.clear()
        inst.attackLockedThisTurn = false
        inst.revealedUntilTurn = -1
        inst.summonedByUid = null
    }

    fun applyPosition(inst: CardInstance, position: Position) {
        when (position) {
            Position.ATTACK -> {
                inst.faceDown = false
                inst.position = Position.ATTACK
            }

            Position.DEFENSE -> {
                inst.faceDown = false
                inst.position = Position.DEFENSE
            }

            Position.FACE_DOWN_DEFENSE -> {
                inst.faceDown = true
                inst.position = Position.DEFENSE
            }
        }
    }

    fun sendToGraveyard(inst: CardInstance) {
        val loc = removeFromCurrent(inst) ?: return
        resetInstance(inst)
        loc.player.graveyard.add(inst)
    }

    fun banish(inst: CardInstance) {
        val loc = removeFromCurrent(inst) ?: return
        resetInstance(inst)
        loc.player.banished.add(inst)
    }

    fun returnToHand(inst: CardInstance) {
        val loc = removeFromCurrent(inst) ?: return
        resetInstance(inst)
        loc.player.hand.add(inst)
    }

    fun returnToDeck(inst: CardInstance, toBottom: Boolean) {
        val loc = removeFromCurrent(inst) ?: return
        resetInstance(inst)
        if (toBottom) loc.player.deck.add(inst) else loc.player.deck.add(0, inst)
    }

    /** [byBattle] が true なら戦闘破壊。永続効果の破壊耐性はここで見る。 */
    suspend fun destroy(inst: CardInstance, byBattle: Boolean = false) {
        val loc = locate(inst) ?: return
        val wasOnField = loc.zone == ZoneType.MONSTER_ZONE || loc.zone == ZoneType.SPELL_TRAP_ZONE

        if (wasOnField) {
            val kind =
                if (byBattle) ProtectionKind.BATTLE_DESTRUCTION
                else ProtectionKind.EFFECT_DESTRUCTION
            if (hasProtection(inst, kind)) {
                log("「${inst.card.name}」は${kind.label}。")
                return
            }
        }

        log("${loc.player.name}の「${inst.card.name}」は破壊された。")
        sendToGraveyard(inst)
        if (wasOnField) {
            val cause = if (byBattle) EventCause.BATTLE else causeKind
            val by = if (byBattle) null else causePlayer
            val from = if (byBattle) null else causeSource
            emit(
                GameEvent(GameEventType.DESTROYED, loc.player.index, inst, cause, by, from),
                GameEvent(GameEventType.SENT_TO_GRAVEYARD, loc.player.index, inst, cause, by, from),
                GameEvent(GameEventType.LEFT_FIELD, loc.player.index, inst, cause, by, from)
            )
        }
    }

    // =======================================================================
    // 永続の効果（【発動タイプ】が「永続」の効果）
    // =======================================================================

    /**
     * 永続の効果を調べている最中かどうか。
     * 適用範囲の絞り込みがまた永続の効果を参照して堂々巡りになるのを防ぐ。
     */
    private var resolvingContinuous = false

    /**
     * いま適用されている永続の効果を、持ち主とセットで集める。
     *
     * 自前の効果を先に集めてから、そこで与えられた効果の中の永続効果を足す。
     * 与えられた効果はさらに効果を与えられないので、2段で打ち止めになる。
     */
    private fun activeContinuous(): List<Triple<CardInstance, PlayerState, EffectClause>> {
        val own = continuousFrom { it.card.effect }
        val granted = continuousFrom { inst -> grantsFor(own, inst) }
        return own + granted
    }

    private fun continuousFrom(
        effectFor: (CardInstance) -> EffectText?
    ): List<Triple<CardInstance, PlayerState, EffectClause>> {
        val result = mutableListOf<Triple<CardInstance, PlayerState, EffectClause>>()
        for (player in state.players) {
            val cards = player.monsters + player.spellsAndTraps + player.graveyard + player.hand
            for (inst in cards) {
                // 裏側のカードの効果は働かない。
                if (isOnField(inst) && inst.faceDown) continue
                val effect = effectFor(inst) ?: continue
                effect.clauses.forEachIndexed { index, clause ->
                    if (!effect.isContinuous(index)) return@forEachIndexed
                    if (!isInLocation(inst, effectiveLocations(effect, index))) return@forEachIndexed
                    // 【条件】は常に見張る。イベント条件は永続では成立しない。
                    if (!conditionsMet(effect.conditionsFor(index), player, inst)) return@forEachIndexed
                    result += Triple(inst, player, clause)
                }
            }
        }
        return result
    }

    /** [sources] の永続効果によって [target] に与えられている効果。 */
    private fun grantsFor(
        sources: List<Triple<CardInstance, PlayerState, EffectClause>>,
        target: CardInstance
    ): EffectText? {
        val clauses = sources.flatMap { (source, owner, clause) ->
            clause.actions
                .filterIsInstance<GrantEffectAction>()
                .filter { affects(it.scope, source, owner, target) }
                .map { it.granted }
                // 与えられた効果がさらに効果を与えることはできない。
                .filter { given -> given.hasWork && given.actions.none { it is GrantEffectAction } }
        }
        return if (clauses.isEmpty()) null else EffectText(clauses = clauses)
    }

    /**
     * [target] が今持っている効果。自前の効果に、与えられた効果を足したもの。
     *
     * 与えられた効果は自前の効果の後ろに並ぶので、自前の効果の番号はずれない。
     * 「効果を与える効果」を与えることはできない（無限に増えるのを避けるため）。
     */
    fun effectOf(target: CardInstance): EffectText? {
        val own = target.card.effect
        val granted = grantedClauses(target)
        if (granted.isEmpty()) return own
        val base = own ?: EffectText()
        return base.copy(clauses = base.clauses + granted)
    }

    /** [target] が今与えられている効果。 */
    private fun grantedClauses(target: CardInstance): List<EffectClause> =
        withoutRecursion(emptyList()) {
            grantsFor(continuousFrom { it.card.effect }, target)?.clauses.orEmpty()
        }

    /** 永続の効果の [scope] に [target] が入っているか。 */
    private fun affects(
        scope: CardScope,
        source: CardInstance,
        owner: PlayerState,
        target: CardInstance
    ): Boolean {
        if (scope.selfOnly) return target === source
        return candidates(scope, owner, source, respectProtection = false).any { it === target }
    }

    private fun <T> withoutRecursion(fallback: T, block: () -> T): T {
        if (resolvingContinuous) return fallback
        resolvingContinuous = true
        return try {
            block()
        } finally {
            resolvingContinuous = false
        }
    }

    /** 永続の効果による攻撃力・守備力の増減。 */
    private fun statBonus(target: CardInstance, stat: StatKind): Int =
        withoutRecursion(0) {
            activeContinuous().sumOf { (source, owner, clause) ->
                clause.actions
                    .filterIsInstance<ModifyStatAction>()
                    .filter { it.stat == stat && affects(it.scope, source, owner, target) }
                    .sumOf { resolveValue(it.amountSpec, owner, source) }
            }
        }

    /** 永続の効果込みの攻撃力。表示や戦闘の計算はこちらを使う。 */
    fun atkOf(inst: CardInstance): Int =
        (inst.atkValue + statBonus(inst, StatKind.ATK)).coerceAtLeast(0)

    /** 永続の効果込みの守備力。 */
    fun defOf(inst: CardInstance): Int =
        (inst.defValue + statBonus(inst, StatKind.DEF)).coerceAtLeast(0)

    /** [target] が [kind] の耐性を持っているか。そのターンだけの耐性も含む。 */
    fun hasProtection(target: CardInstance, kind: ProtectionKind): Boolean {
        if (kind in target.turnProtections) return true
        return withoutRecursion(false) {
            activeContinuous().any { (source, owner, clause) ->
                clause.actions
                    .filterIsInstance<GrantProtectionAction>()
                    .any { it.kind == kind && affects(it.scope, source, owner, target) }
            }
        }
    }

    /** 攻撃を封じられているか。 */
    fun isAttackLocked(target: CardInstance): Boolean {
        if (target.attackLockedThisTurn) return true
        return withoutRecursion(false) {
            activeContinuous().any { (source, owner, clause) ->
                clause.actions
                    .filterIsInstance<PreventAttackAction>()
                    .any { affects(it.scope, source, owner, target) }
            }
        }
    }

    /** 「相手の効果を受けない」カードは、相手の効果の対象に選べない。 */
    private fun isUntouchableBy(target: CardInstance, actingPlayer: PlayerState): Boolean {
        val owner = locate(target)?.player ?: return false
        if (owner === actingPlayer) return false
        return hasProtection(target, ProtectionKind.OPPONENT_EFFECTS)
    }

    // =======================================================================
    // 対象の絞り込み
    // =======================================================================

    private fun playersFor(ref: PlayerRef, controller: PlayerState): List<PlayerState> =
        when (ref) {
            PlayerRef.SELF -> listOf(controller)
            PlayerRef.OPPONENT -> listOf(state.opponentOf(controller))
            PlayerRef.BOTH -> state.players
        }

    private fun primaryPlayer(ref: PlayerRef, controller: PlayerState): PlayerState =
        playersFor(ref, controller).first()

    /** カードの中身を見ないと判定できないフィルタが含まれているか。 */
    private fun needsCardInfo(filters: List<CardFilter>): Boolean = filters.any {
        it is AttributeFilter || it is RaceFilter || it is CategoryFilter ||
            it is LevelFilter || it is AtkFilter || it is DefFilter || it is NameFilter
    }

    fun matches(inst: CardInstance, filter: CardFilter): Boolean = when (filter) {
        is KindFilter -> inst.card.kind == filter.kind
        is AttributeFilter -> inst.card.attributeId == filter.attributeId
        is RaceFilter -> inst.card.raceId == filter.raceId
        is CategoryFilter -> filter.categoryId in inst.card.categoryIds
        is LevelFilter ->
            inst.card.kind == CardKind.MONSTER && filter.cmp.test(inst.card.level, filter.value)

        is AtkFilter ->
            inst.card.kind == CardKind.MONSTER && filter.cmp.test(atkOf(inst), filter.value)

        is DefFilter ->
            inst.card.kind == CardKind.MONSTER && filter.cmp.test(defOf(inst), filter.value)

        is PositionFilter -> inst.displayPosition == filter.position
        is NameFilter -> inst.card.name.contains(filter.text, ignoreCase = true)
        // 「このカードの効果によって特殊召喚された」は、誰の効果かが要るので
        // matchesAll でしか判定できない。単独では常に満たすものとして扱う。
        is SummonedByThisFilter -> true
    }

    fun matchesAll(
        inst: CardInstance,
        filters: List<CardFilter>,
        source: CardInstance? = null
    ): Boolean = filters.all { filter ->
        if (filter is SummonedByThisFilter) {
            // 「このカードの効果によって特殊召喚された」かどうか。
            val matched = source != null && inst.summonedByUid == source.uid
            if (filter.enabled) matched else !matched
        } else {
            matches(inst, filter)
        }
    }

    /** [scope] が指す候補カードを列挙する。裏側のカードは中身を見るフィルタでは選べない。 */
    fun candidates(
        scope: CardScope,
        controller: PlayerState,
        source: CardInstance? = null,
        respectProtection: Boolean = true
    ): List<CardInstance> {
        // 「このカード自身」を指しているときは、他の指定を見ない。
        if (scope.selfOnly) return listOfNotNull(source)

        val hidesInfo = needsCardInfo(scope.filters)
        return playersFor(scope.who, controller)
            .flatMap { zoneCards(it, scope.zone) }
            .filter { !(hidesInfo && it.faceDown) }
            .filter { !(respectProtection && isUntouchableBy(it, controller)) }
            .filter { matchesAll(it, scope.filters, source) }
    }

    private suspend fun resolveTargets(
        scope: CardScope,
        controller: PlayerState,
        prompt: String,
        source: CardInstance? = null
    ): List<CardInstance> {
        val pool = candidates(scope, controller, source)
        if (scope.selfOnly) return pool
        if (pool.isEmpty()) return emptyList()
        val want = effectiveCount(scope, controller, source)
        val chosen = when (scope.selection) {
            SelectionMode.ALL -> pool
            SelectionMode.RANDOM -> pool.shuffled().take(want)
            SelectionMode.CHOOSE -> {
                val n = min(want, pool.size)
                if (n <= 0) return emptyList()
                // 「〜まで」なら選ばない選択も許す。
                val least = if (scope.upTo) 0 else n
                interaction.chooseCards(controller.index, prompt, pool, least, n)
            }
        }

        // 「選んで」＝対象を取る。選ばれたカードに知らせる。
        if (scope.selection == SelectionMode.CHOOSE && chosen.isNotEmpty()) {
            emit(
                *chosen.map { target ->
                    val owner = state.players.firstOrNull { locate(target)?.player === it }
                    GameEvent(GameEventType.TARGETED, owner?.index ?: controller.index, target)
                }.toTypedArray()
            )
        }
        return chosen
    }

    /** デッキから選んだ後はデッキをシャッフルする。 */
    private fun shuffleIfDeck(scope: CardScope, controller: PlayerState) {
        if (scope.zone != ZoneType.DECK) return
        playersFor(scope.who, controller).forEach { player ->
            val shuffled = player.deck.toList().shuffled()
            player.deck.clear()
            player.deck.addAll(shuffled)
        }
    }

    /**
     * 数値の指定を実際の値にする。
     * 「条件を満たすカードの枚数×100」のような書き方に対応する。
     */
    fun resolveValue(
        spec: ValueSpec,
        controller: PlayerState,
        source: CardInstance? = null
    ): Int = when (spec) {
        is FixedValue -> spec.value
        is CountValue ->
            spec.base + candidates(spec.scope, controller, source, respectProtection = false).size *
                spec.multiplier
    }

    // =======================================================================
    // 条件とコスト
    // =======================================================================

    /**
     * 状態の条件を判定する。イベント条件は [event] が与えられ、かつ一致した
     * ときだけ満たされる。[event] が null なら誘発効果は発動できない
     * （＝手動では発動できない）。
     */
    fun conditionsMet(
        conditions: List<Condition>,
        controller: PlayerState,
        holder: CardInstance? = null,
        event: GameEvent? = null
    ): Boolean =
        conditions.all { condition ->
            when (condition) {
                is AnyOfCondition -> condition.conditions.isEmpty() ||
                    condition.conditions.any {
                        conditionsMet(listOf(it), controller, holder, event)
                    }

                is EventCondition -> when {
                    holder == null -> false
                    // 「〜したターン」は、このターンに起きていれば満たす。
                    condition.window == EventWindow.THIS_TURN ->
                        state.eventsThisTurn.any {
                            matchesEvent(condition, it, holder, controller)
                        }

                    else -> event != null && matchesEvent(condition, event, holder, controller)
                }

                is CardExistsCondition -> {
                    val count = candidates(condition.scope, controller, holder).size
                    if (condition.negate) count == 0 else count >= condition.atLeast
                }

                is LifeCondition -> playersFor(condition.who, controller).all {
                    condition.cmp.test(it.life, condition.value)
                }

                is SelfZoneCondition -> {
                    val zone = holder?.let { locate(it)?.zone }
                    zone != null && (condition.zones.isEmpty() || zone in condition.zones)
                }

                is PhaseCondition ->
                    condition.phases.isEmpty() || state.phase in condition.phases

                is ZoneCountCondition -> playersFor(condition.who, controller).all {
                    condition.cmp.test(zoneCards(it, condition.zone).size, condition.value)
                }
            }
        }

    private fun matchesEvent(
        condition: EventCondition,
        event: GameEvent,
        holder: CardInstance,
        controller: PlayerState
    ): Boolean {
        if (condition.event != event.type) return false
        if (!matchesCause(condition.cause, event, controller)) return false
        // 「罠カードの対象に取られた」のように、出来事を起こしたカードを限定する。
        if (condition.sourceFilters.isNotEmpty()) {
            val from = event.sourceCard ?: return false
            if (!matchesAll(from, condition.sourceFilters)) return false
        }
        if (condition.selfOnly) return event.card === holder

        val ownerMatches = when (condition.who) {
            PlayerRef.SELF -> event.playerIndex == controller.index
            PlayerRef.OPPONENT -> event.playerIndex != controller.index
            PlayerRef.BOTH -> true
        }
        if (!ownerMatches) return false
        if (condition.event.isPlayerEvent) return true

        val card = event.card ?: return false
        return matchesAll(card, condition.filters)
    }

    /** 「相手の効果によって」のような原因の指定を判定する。 */
    private fun matchesCause(
        filter: CauseFilter,
        event: GameEvent,
        controller: PlayerState
    ): Boolean = when (filter) {
        CauseFilter.ANY -> true
        CauseFilter.BY_BATTLE -> event.cause == EventCause.BATTLE
        CauseFilter.BY_EFFECT -> event.cause == EventCause.EFFECT
        CauseFilter.BY_SELF_EFFECT ->
            event.cause == EventCause.EFFECT && event.causePlayer == controller.index

        CauseFilter.BY_OPPONENT_EFFECT ->
            event.cause == EventCause.EFFECT &&
                event.causePlayer != null && event.causePlayer != controller.index
    }

    fun canPayCosts(
        costs: List<Cost>,
        controller: PlayerState,
        excluding: CardInstance? = null
    ): Boolean = costs.all { cost ->
        when (cost) {
            is PayLifeCost -> controller.life > cost.amount
            is DiscardCost ->
                controller.hand.count { it !== excluding && matchesAll(it, cost.filters) } >= cost.count

            is TributeCost ->
                controller.monsters.count { matchesAll(it, cost.filters) } >= cost.count

            is BanishFromGraveCost ->
                controller.graveyard.count { matchesAll(it, cost.filters) } >= cost.count

            is MillCost -> controller.deck.size >= cost.count

            is DiscardSelfCost -> excluding != null

            is MoveCost ->
                candidates(cost.scope, controller, excluding)
                    .count { it !== excluding } >= requiredCount(cost.scope, controller, excluding)

            // 見せるだけなので、いつでも払える。
            is RevealCost -> true
        }
    }

    /** その対象指定で必要な枚数。「全て」なら最低1枚。 */
    /** その指定が実際に何枚を指すか。「〜の数だけ」はここで数える。 */
    fun effectiveCount(
        scope: CardScope,
        controller: PlayerState,
        source: CardInstance? = null
    ): Int = resolveValue(scope.countValue, controller, source).coerceAtLeast(0)

    /**
     * 発動できるかを見るときに、最低これだけ対象が要るという枚数。
     * 「〜まで」を付けた指定は足りなくてもよいので 0。
     */
    private fun requiredCount(
        scope: CardScope,
        controller: PlayerState,
        source: CardInstance? = null
    ): Int = when {
        scope.selfOnly -> 1
        scope.upTo -> 0
        scope.selection == SelectionMode.ALL -> 1
        else -> effectiveCount(scope, controller, source)
    }

    private suspend fun payCosts(
        costs: List<Cost>,
        controller: PlayerState,
        excluding: CardInstance?
    ): Boolean = withCause(EventCause.EFFECT, controller.index, excluding) {
        payCostsInner(costs, controller, excluding)
    }

    private suspend fun payCostsInner(
        costs: List<Cost>,
        controller: PlayerState,
        excluding: CardInstance?
    ): Boolean {
        for (cost in costs) {
            when (cost) {
                is PayLifeCost -> {
                    controller.life -= cost.amount
                    log("${controller.name}はライフを${cost.amount}払った。（残り${controller.life}）")
                }

                is DiscardCost -> {
                    val pool = controller.hand.filter {
                        it !== excluding && matchesAll(it, cost.filters)
                    }
                    val chosen = interaction.chooseCards(
                        controller.index, "コスト：捨てる手札を${cost.count}枚選択",
                        pool, cost.count, cost.count
                    )
                    if (chosen.size < cost.count) return false
                    chosen.forEach { sendToGraveyard(it) }
                    log("${controller.name}は手札を${cost.count}枚捨てた。")
                }

                is TributeCost -> {
                    val pool = controller.monsters.filter { matchesAll(it, cost.filters) }
                    val chosen = interaction.chooseCards(
                        controller.index, "コスト：リリースするモンスターを${cost.count}体選択",
                        pool, cost.count, cost.count
                    )
                    if (chosen.size < cost.count) return false
                    chosen.forEach { sendToGraveyard(it) }
                    log("${controller.name}はモンスター${cost.count}体をリリースした。")
                }

                is BanishFromGraveCost -> {
                    val pool = controller.graveyard.filter { matchesAll(it, cost.filters) }
                    val chosen = interaction.chooseCards(
                        controller.index, "コスト：墓地から除外するカードを${cost.count}枚選択",
                        pool, cost.count, cost.count
                    )
                    if (chosen.size < cost.count) return false
                    chosen.forEach { banish(it) }
                    log("${controller.name}は墓地のカード${cost.count}枚を除外した。")
                }

                is MillCost -> {
                    repeat(min(cost.count, controller.deck.size)) {
                        sendToGraveyard(controller.deck.first())
                    }
                    log("${controller.name}はデッキの上から${cost.count}枚を墓地へ送った。")
                }

                is MoveCost -> {
                    val needed = effectiveCount(cost.scope, controller, excluding)
                    if (needed > 0) {
                        val pool = candidates(cost.scope, controller, excluding)
                            .filter { it !== excluding }
                        if (pool.isEmpty()) return false
                        val chosen = when {
                            cost.scope.selection == SelectionMode.ALL -> pool
                            cost.scope.selection == SelectionMode.RANDOM ->
                                pool.shuffled().take(needed)

                            else -> interaction.chooseCards(
                                controller.index,
                                "コスト：${cost.destination.label}カードを${needed}枚選択",
                                pool, needed, needed
                            )
                        }
                        if (chosen.size < needed) return false
                        chosen.forEach { moveForCost(it, cost.destination) }
                        log(
                            "${controller.name}はコストとして" +
                                "${chosen.size}枚を${cost.destination.label}。"
                        )
                    }
                }

                is DiscardSelfCost -> {
                    val self = excluding ?: return false
                    if (cost.banish) {
                        banish(self)
                        log("${controller.name}はコストとして「${self.card.name}」を除外した。")
                    } else {
                        sendToGraveyard(self)
                        log("${controller.name}はコストとして「${self.card.name}」を墓地へ送った。")
                    }
                }

                is RevealCost -> reveal(cost.scope, cost.duration, controller, excluding)
            }
        }
        return true
    }

    /** カードを相手に見せる。指定した長さのあいだ公開したままにもできる。 */
    private suspend fun reveal(
        scope: CardScope,
        duration: RevealDuration,
        controller: PlayerState,
        source: CardInstance?
    ) {
        val shown = candidates(scope, controller, source, respectProtection = false)
        if (shown.isEmpty()) {
            log("${controller.name}は見せるカードを持っていない。")
            return
        }
        val names = shown.joinToString("、") { "「${it.card.name}」" }
        log("${controller.name}は${names}を相手に見せた。")
        when (duration) {
            RevealDuration.MOMENT -> Unit
            RevealDuration.TURN -> shown.forEach { it.revealedUntilTurn = state.turn }
            RevealDuration.PERMANENT ->
                shown.forEach { it.revealedUntilTurn = CardInstance.PERMANENT_REVEAL }
        }
        interaction.notify(
            state.opponentOf(controller).index,
            "${controller.name}が公開：$names"
        )
    }

    private fun moveForCost(inst: CardInstance, destination: MoveDestination) {
        when (destination) {
            MoveDestination.GRAVEYARD -> sendToGraveyard(inst)
            MoveDestination.BANISHED -> banish(inst)
            MoveDestination.HAND -> returnToHand(inst)
            MoveDestination.DECK_TOP -> returnToDeck(inst, toBottom = false)
            MoveDestination.DECK_BOTTOM -> returnToDeck(inst, toBottom = true)
        }
    }

    // =======================================================================
    // ライフとドロー
    // =======================================================================

    suspend fun dealDamage(player: PlayerState, amount: Int) {
        if (amount <= 0) return
        player.life -= amount
        log("${player.name}は${amount}ダメージを受けた。（残り${player.life.coerceAtLeast(0)}）")
        if (player.life <= 0) {
            player.life = 0
            finish(state.opponentOf(player).index, "${player.name}のライフが0になった")
            return
        }
        emit(GameEvent(GameEventType.DAMAGE_TAKEN, player.index))
    }

    suspend fun recoverLife(player: PlayerState, amount: Int) {
        if (amount <= 0) return
        player.life += amount
        log("${player.name}はライフを${amount}回復した。（${player.life}）")
        emit(GameEvent(GameEventType.LIFE_RECOVERED, player.index))
    }

    /** カードをドローする。デッキが尽きていたらそのプレイヤーの負け。 */
    suspend fun draw(player: PlayerState, count: Int) {
        repeat(count) {
            if (state.finished) return
            if (player.deck.isEmpty()) {
                finish(state.opponentOf(player).index, "${player.name}のデッキが尽きた")
                return
            }
            player.hand.add(player.deck.removeAt(0))
        }
        log("${player.name}はカードを${count}枚ドローした。")
        emit(GameEvent(GameEventType.CARD_DRAWN, player.index))
    }

    fun finish(winnerIndex: Int?, reason: String) {
        if (state.finished) return
        state.finished = true
        state.winnerIndex = winnerIndex
        val winner = winnerIndex?.let { state.players[it].name } ?: "引き分け"
        log("$reason 。 勝者: $winner")
    }

    // =======================================================================
    // 効果（述語）の実行
    // =======================================================================

    private suspend fun applyAction(
        action: Action,
        controller: PlayerState,
        source: CardInstance? = null
    ) {
        if (state.finished) return
        when (action) {
            is DestroyAction -> {
                val targets = resolveTargets(action.scope, controller, "破壊するカードを選択", source)
                targets.forEach { destroy(it) }
                shuffleIfDeck(action.scope, controller)
            }

            is BanishAction -> {
                val targets = resolveTargets(action.scope, controller, "除外するカードを選択", source)
                targets.forEach {
                    val owner = locate(it)?.player?.index ?: controller.index
                    val leftField = isOnField(it)
                    log("「${it.card.name}」を除外した。")
                    banish(it)
                    emit(GameEvent(GameEventType.BANISHED, owner, it))
                    if (leftField) emit(GameEvent(GameEventType.LEFT_FIELD, owner, it))
                }
                shuffleIfDeck(action.scope, controller)
            }

            is ToHandAction -> {
                val targets = resolveTargets(action.scope, controller, "手札に加えるカードを選択", source)
                targets.forEach {
                    val owner = locate(it)?.player?.index ?: controller.index
                    val leftField = isOnField(it)
                    log("「${it.card.name}」を手札に加えた。")
                    returnToHand(it)
                    if (leftField) emit(GameEvent(GameEventType.LEFT_FIELD, owner, it))
                }
                shuffleIfDeck(action.scope, controller)
            }

            is ToGraveAction -> {
                val targets = resolveTargets(action.scope, controller, "墓地へ送るカードを選択", source)
                targets.forEach {
                    val owner = locate(it)?.player?.index ?: controller.index
                    val leftField = isOnField(it)
                    log("「${it.card.name}」を墓地へ送った。")
                    sendToGraveyard(it)
                    emit(GameEvent(GameEventType.SENT_TO_GRAVEYARD, owner, it))
                    if (leftField) emit(GameEvent(GameEventType.LEFT_FIELD, owner, it))
                }
                shuffleIfDeck(action.scope, controller)
            }

            is ToDeckAction -> {
                val targets = resolveTargets(action.scope, controller, "デッキに戻すカードを選択", source)
                targets.forEach {
                    log("「${it.card.name}」をデッキに戻した。")
                    returnToDeck(it, action.toBottom)
                }
                shuffleIfDeck(action.scope, controller)
            }

            is SpecialSummonAction -> {
                val destination = primaryPlayer(action.controller, controller)
                val targets = resolveTargets(action.scope, controller, "特殊召喚するモンスターを選択", source)
                    .filter { it.card.kind == CardKind.MONSTER }
                for (target in targets) {
                    if (!summonAllowed(target, destination, SummonKind.SPECIAL)) {
                        log("召喚の制限により「${target.card.name}」は特殊召喚できない。")
                        continue
                    }
                    val zone = destination.freeMonsterZones().firstOrNull()
                    if (zone == null) {
                        log("モンスターゾーンに空きが無いため特殊召喚できない。")
                        break
                    }
                    val choices = action.choices
                    val position = if (choices.size <= 1) {
                        choices.first()
                    } else {
                        val picked = interaction.chooseOption(
                            controller.index,
                            "「${target.card.name}」を出す表示形式を選択",
                            choices.map { it.label }
                        )
                        choices[picked.coerceIn(choices.indices)]
                    }
                    removeFromCurrent(target)
                    resetInstance(target)
                    destination.monsterZones[zone] = target
                    applyPosition(target, position)
                    target.summonedOnTurn = state.turn
                    // 「このカードの効果によって特殊召喚された」の判定に使う。
                    target.summonedByUid = source?.uid
                    log("${destination.name}は「${target.card.name}」を特殊召喚した。")
                    emit(
                        GameEvent(GameEventType.SPECIAL_SUMMONED, destination.index, target),
                        GameEvent(GameEventType.SUMMONED, destination.index, target)
                    )
                }
                shuffleIfDeck(action.scope, controller)
            }

            is ModifyStatAction -> {
                val targets = resolveTargets(action.scope, controller, "効果の対象を選択", source)
                val delta = resolveValue(action.amountSpec, controller, source)
                targets.forEach { target ->
                    when (action.stat) {
                        StatKind.ATK -> target.atkMod += delta
                        StatKind.DEF -> target.defMod += delta
                    }
                    log("「${target.card.name}」の${action.stat.label}が${delta}変化した。")
                }
            }

            is ChangePositionAction -> {
                val targets = resolveTargets(action.scope, controller, "表示形式を変えるカードを選択", source)
                targets.filter { it.card.kind == CardKind.MONSTER }.forEach {
                    applyPosition(it, action.position)
                    log("「${it.card.name}」を${action.position.label}にした。")
                }
            }

            is DrawAction -> {
                val count = resolveValue(action.countSpec, controller, source).coerceAtLeast(0)
                playersFor(action.who, controller).forEach { draw(it, count) }
            }

            is DamageAction -> {
                val amount = resolveValue(action.amountSpec, controller, source)
                playersFor(action.who, controller).forEach { dealDamage(it, amount) }
            }

            is RecoverAction -> {
                val amount = resolveValue(action.amountSpec, controller, source)
                playersFor(action.who, controller).forEach { recoverLife(it, amount) }
            }

            is DiscardAction -> {
                val want = resolveValue(action.countSpec, controller, source).coerceAtLeast(0)
                for (player in playersFor(action.who, controller)) {
                    val n = min(want, player.hand.size)
                    if (n == 0) continue
                    val discarded = if (action.random) {
                        player.hand.toList().shuffled().take(n)
                    } else {
                        interaction.chooseCards(
                            player.index, "捨てる手札を${n}枚選択", player.hand.toList(), n, n
                        )
                    }
                    discarded.forEach { sendToGraveyard(it) }
                    log("${player.name}は手札を${discarded.size}枚捨てた。")
                }
            }

            is MillAction -> {
                val want = resolveValue(action.countSpec, controller, source).coerceAtLeast(0)
                for (player in playersFor(action.who, controller)) {
                    val n = min(want, player.deck.size)
                    repeat(n) { sendToGraveyard(player.deck.first()) }
                    log("${player.name}はデッキの上から${n}枚を墓地へ送った。")
                }
            }

            is SetSpellTrapAction -> {
                val targets = resolveTargets(action.scope, controller, "セットするカードを選択", source)
                placeIntoSpellTrapZone(targets, controller, faceDown = true)
                shuffleIfDeck(action.scope, controller)
            }

            is PlaceSpellTrapAction -> {
                val targets = resolveTargets(action.scope, controller, "置くカードを選択", source)
                placeIntoSpellTrapZone(targets, controller, faceDown = false)
                shuffleIfDeck(action.scope, controller)
            }

            is ActivateCardAction -> {
                val targets = resolveTargets(action.scope, controller, "発動するカードを選択", source)
                for (target in targets) {
                    if (state.finished) break
                    // 手札やデッキからでも、一度ゾーンに置いてから発動する。
                    if (!isOnField(target) && target.card.kind != CardKind.MONSTER) {
                        if (!placeIntoSpellTrapZone(listOf(target), controller, faceDown = false)) {
                            break
                        }
                    }
                    activateCard(target, controller)
                }
                shuffleIfDeck(action.scope, controller)
            }

            is GrantProtectionAction -> {
                // 発動して与えた耐性は、そのターンの間だけ続く。
                val targets = resolveTargets(action.scope, controller, "耐性を与える対象を選択", source)
                targets.forEach {
                    if (action.kind !in it.turnProtections) it.turnProtections.add(action.kind)
                    log("「${it.card.name}」はこのターン${action.kind.label}。")
                }
            }

            is PreventAttackAction -> {
                val targets = resolveTargets(action.scope, controller, "攻撃を封じる対象を選択", source)
                targets.forEach {
                    it.attackLockedThisTurn = true
                    log("「${it.card.name}」はこのターン攻撃できない。")
                }
            }

            is RestrictSummonAction -> Unit // 【制限】へ移した。旧データのために残してある。

            // 効果の付与は【発動タイプ】が「永続」のときだけ働く。
            is GrantEffectAction -> log("効果の付与は「永続」の効果に書いてください。")

            is AdvancePhaseAction -> {
                // 効果の処理の途中でフェイズを動かすと壊れるので、
                // 全ての処理が終わってから適用する。
                pendingAdvance = action.kind
                log("この効果の処理のあと、${action.kind.label}。")
            }

            is RevealAction -> reveal(action.scope, action.duration, controller, source)

            NegateAction -> {
                // 実際の無効化は発動宣言時の応答処理で行うため、ここでは何もしない。
                log("発動を無効にした。")
            }
        }
    }

    // =======================================================================
    // 効果の発動
    // =======================================================================

    /** 【場所】が省略されている場合は「フィールドで発動」。 */
    private fun effectiveLocations(effect: EffectText, index: Int): List<ActivationLocation> =
        effect.locationsFor(index).ifEmpty { listOf(ActivationLocation.FIELD) }

    /**
     * いまカードがある領域から、[locations] の指定で発動できるか。
     *
     * 魔法・罠の「フィールドで発動」は、手札から魔法・罠ゾーンに出して発動する
     * 場合と、伏せてあるカードを表にして発動する場合の両方を含む。
     * 【場所】に『手札』と書いた場合だけ、手札に置いたまま発動する。
     */
    private fun canActivateFrom(
        inst: CardInstance,
        locations: List<ActivationLocation>
    ): Boolean = when (locate(inst)?.zone) {
        // 罠は必ずセットしてから発動する。手札から直接出せるのは魔法だけ。
        ZoneType.HAND ->
            ActivationLocation.HAND in locations ||
                (inst.card.kind == CardKind.SPELL && ActivationLocation.FIELD in locations)

        ZoneType.MONSTER_ZONE, ZoneType.SPELL_TRAP_ZONE ->
            ActivationLocation.FIELD in locations

        ZoneType.GRAVEYARD -> ActivationLocation.GRAVEYARD in locations

        ZoneType.BANISHED -> ActivationLocation.BANISHED in locations

        else -> false
    }

    /**
     * カードが実際にその場所にあるか。
     * 永続の効果は「そこから発動できるか」ではなく、そこに置かれていることが条件。
     */
    private fun isInLocation(
        inst: CardInstance,
        locations: List<ActivationLocation>
    ): Boolean = when (locate(inst)?.zone) {
        ZoneType.HAND -> ActivationLocation.HAND in locations
        ZoneType.MONSTER_ZONE, ZoneType.SPELL_TRAP_ZONE -> ActivationLocation.FIELD in locations
        ZoneType.GRAVEYARD -> ActivationLocation.GRAVEYARD in locations
        ZoneType.BANISHED -> ActivationLocation.BANISHED in locations
        else -> false
    }

    /** 手札から発動するとき、魔法・罠ゾーンに置いてから発動する必要があるか。 */
    private fun needsFieldPlacement(
        inst: CardInstance,
        locations: List<ActivationLocation>
    ): Boolean =
        inst.card.kind == CardKind.SPELL &&
            locate(inst)?.zone == ZoneType.HAND &&
            ActivationLocation.HAND !in locations

    /**
     * いま発動できる効果番号の一覧。
     * 【場所】【条件】【コスト】は共通指定と番号ごとの指定を合成して判定する。
     */
    fun activatableClauses(inst: CardInstance, controller: PlayerState): List<Int> {
        val effect = effectOf(inst) ?: return emptyList()
        // 発動の処理中のカードは、その解決が終わるまで発動し直せない。
        if (inst.uid in resolvingCards) return emptyList()

        return effect.clauses.indices.filter { index ->
            val clause = effect.clauses[index]
            if (!clause.hasWork) return@filter false

            // 永続と発動時処理はそれ自体を発動できない。
            // イベント条件を持つ効果は誘発効果なので手動では発動できない。
            if (!clause.mode.isStandalone) return@filter false
            if (effect.isTriggered(index)) return@filter false

            if (!canActivateFrom(inst, effectiveLocations(effect, index))) return@filter false
            if (!phaseAllows(inst, effect, index, controller)) return@filter false
            if (!limitsAllow(inst, index, controller)) return@filter false

            // 手札から場に出して発動するなら、置ける空きが要る。
            if (needsFieldPlacement(inst, effectiveLocations(effect, index)) &&
                controller.freeSpellTrapZones().isEmpty()
            ) {
                return@filter false
            }

            conditionsMet(effect.conditionsFor(index), controller, inst) &&
                canPayCosts(effect.costsFor(index), controller, excluding = inst) &&
                canResolveActions(clause, controller, inst)
        }
    }

    /** その述語が対象にするカードの範囲。プレイヤーが対象なら null。 */
    private fun scopeOf(action: Action): CardScope? = when (action) {
        is DestroyAction -> action.scope
        is BanishAction -> action.scope
        is ToHandAction -> action.scope
        is ToGraveAction -> action.scope
        is ToDeckAction -> action.scope
        is SpecialSummonAction -> action.scope
        is ModifyStatAction -> action.scope
        is ChangePositionAction -> action.scope
        is SetSpellTrapAction -> action.scope
        is PlaceSpellTrapAction -> action.scope
        is ActivateCardAction -> action.scope
        is GrantProtectionAction -> action.scope
        is PreventAttackAction -> action.scope
        // 見せる対象や召喚制限は、対象が無くても発動できてよいので数えない。
        else -> null
    }

    /** その述語がカードを別の場所へ動かすか。 */
    private fun movesCards(action: Action): Boolean = when (action) {
        is DestroyAction, is BanishAction, is ToHandAction, is ToGraveAction,
        is ToDeckAction, is SpecialSummonAction, is SetSpellTrapAction,
        is PlaceSpellTrapAction -> true

        else -> false
    }

    /** 場合分けを含めて、効果をひととおり処理する。 */
    private suspend fun runClause(
        clause: EffectClause,
        controller: PlayerState,
        source: CardInstance?
    ) = withCause(EventCause.EFFECT, controller.index, source) {
        runClauseInner(clause, controller, source)
    }

    private suspend fun runClauseInner(
        clause: EffectClause,
        controller: PlayerState,
        source: CardInstance?
    ) {
        if (!runSteps(clause.actions, clause.optionalSteps, clause.linkedSteps, controller, source)) {
            return
        }
        if (clause.branches.isEmpty() || state.finished) return

        val matching = clause.branches.filter {
            conditionsMet(it.conditions, controller, source) && it.actions.isNotEmpty()
        }
        if (matching.isEmpty()) {
            log("当てはまる場合が無かった。")
            return
        }

        val applied = when (clause.branchMode) {
            BranchMode.FIRST_MATCH -> listOf(matching.first())
            BranchMode.ALL_MATCHING -> matching
            BranchMode.CHOOSE -> {
                val labels = matching.map { branch ->
                    branch.actions.joinToString("。") { "…" } .ifBlank { "この効果" }
                }
                val index = interaction.chooseOption(
                    controller.index, "適用する効果を選択",
                    matching.indices.map { "${it + 1}つ目" + labels.getOrElse(it) { "" } }
                )
                listOf(matching[index.coerceIn(matching.indices)])
            }
        }

        for (branch in applied) {
            if (!runSteps(
                    branch.actions, branch.optionalSteps, branch.linkedSteps, controller, source
                )
            ) {
                return
            }
        }
    }

    /**
     * 処理をまとまりごとに行う。決着がついたら false を返して打ち切る。
     *
     * まとまりが「任意」なら、まとめて行うかどうかを一度だけ確認する。
     * 「手札を見せ、デッキから墓地へ送ることができる」のような、
     * 片方だけを行えない処理をひとまとまりで扱うための仕組み。
     */
    private suspend fun runSteps(
        actions: List<Action>,
        optionalSteps: List<Int>,
        linkedSteps: List<Int>,
        controller: PlayerState,
        source: CardInstance?
    ): Boolean {
        for (unit in stepUnits(actions.size, optionalSteps, linkedSteps)) {
            if (state.finished) return false
            if (unit.optional && !askOptionalStep(actions, unit, controller)) continue
            for (position in unit.indices) {
                if (state.finished) return false
                applyAction(actions[position], controller, source)
            }
        }
        return true
    }

    /** 「〜することができる」と書かれたまとまりを行うか確認する。 */
    private suspend fun askOptionalStep(
        actions: List<Action>,
        unit: StepUnit,
        controller: PlayerState
    ): Boolean {
        val what = EffectTextRenderer.joinLinked(
            unit.indices.map { EffectTextRenderer.actionToText(actions[it], state.master) }
        )
        return interaction.confirm(controller.index, "${what}か？（任意）")
    }

    /**
     * その効果を、書いてある順に最後まで処理できるか。
     *
     * 途中で対象が足りなくなる効果は発動できない。前の処理でカードが
     * 動くぶんは差し引いて数える。
     */
    fun canResolveActions(
        clause: EffectClause,
        controller: PlayerState,
        source: CardInstance?
    ): Boolean {
        val consumed = mutableListOf<CardInstance>()
        if (!canResolveActionList(
                clause.actions, controller, source, consumed,
                clause.optionalSteps, clause.linkedSteps
            )
        ) {
            return false
        }
        if (clause.branches.isEmpty()) return true

        // 場合分けは、当てはまるもののうち処理しきれるものが1つでもあればよい。
        return clause.branches.any { branch ->
            branch.actions.isNotEmpty() &&
                conditionsMet(branch.conditions, controller, source) &&
                canResolveActionList(
                    branch.actions, controller, source,
                    consumed.toMutableList(), branch.optionalSteps, branch.linkedSteps
                )
        }
    }

    private fun canResolveActionList(
        actions: List<Action>,
        controller: PlayerState,
        source: CardInstance?,
        consumed: MutableList<CardInstance>,
        /** 「〜することができる」と書かれた処理の番号。足りなくても発動できる。 */
        optionalSteps: List<Int> = emptyList(),
        linkedSteps: List<Int> = emptyList()
    ): Boolean {
        // 任意のまとまりは、対象が足りなくても発動を止めない。
        val skipped = stepUnits(actions.size, optionalSteps, linkedSteps)
            .filter { it.optional }
            .flatMap { it.indices }
            .toSet()

        for ((position, action) in actions.withIndex()) {
            if (position in skipped) continue
            val scope = scopeOf(action) ?: continue
            val pool = candidates(scope, controller, source).filter { card ->
                consumed.none { it === card }
            }
            val needed = requiredCount(scope, controller, source)
            if (pool.size < needed) return false
            if (movesCards(action)) consumed += pool.take(needed)
        }
        return true
    }

    /**
     * いまのフェイズで発動できるか。
     *
     * 【条件】でフェイズを指定していればそれに従う。指定が無い場合、
     * 魔法とモンスターの起動効果はメインフェイズのみ。罠はフェイズを問わない。
     */
    private fun phaseAllows(
        inst: CardInstance,
        effect: EffectText,
        index: Int,
        controller: PlayerState
    ): Boolean {
        val phases = effect.phasesFor(index)
        if (phases.isNotEmpty()) {
            if (state.phase !in phases) return false
            if (isQuickEffect(inst, effect, index)) return true
            // 魔法とモンスターの効果は、フェイズを指定しても自分のターンのまま。
            if (inst.card.kind != CardKind.TRAP && state.turnPlayer !== controller) return false
            return true
        }
        if (inst.card.kind == CardKind.TRAP) return true
        // 誘発即時効果は、どのフェイズでも、相手のターンでも発動できる。
        if (isQuickEffect(inst, effect, index)) return true
        return state.phase.isMain && state.turnPlayer === controller
    }

    /**
     * 誘発即時効果（相手のターンや、相手の行動への割り込みでも発動できる効果）か。
     *
     * 【誘発即時】に印を付けた効果のほか、【場所】にフィールド以外
     * （手札・墓地・除外ゾーン）を書いた効果も、書かなくてもそう扱う。
     * いわゆる手札誘発がこれにあたる。
     */
    fun isQuickEffect(inst: CardInstance, effect: EffectText, index: Int): Boolean {
        if (inst.card.kind == CardKind.TRAP) return true
        if (effect.isQuick(index)) return true
        // 手札誘発の形（手札・墓地・除外ゾーンから発動するモンスター効果）。
        // 魔法カードは「自分のターンだけ」のままなので、印を付けたときだけ割り込める。
        return inst.card.kind == CardKind.MONSTER &&
            effectiveLocations(effect, index).any { it != ActivationLocation.FIELD }
    }

    // -----------------------------------------------------------------------
    // 【制限】発動回数
    // -----------------------------------------------------------------------

    /**
     * 制限の「枠」を表す文字列。同じ枠を持つ発動どうしが回数を数え合う。
     *
     * 効果番号より前に書いた制限はカード全体で1つの枠、番号の直後に書いた
     * 制限はその番号ごとに別の枠になる。
     */
    private fun limitKey(limit: UsageLimit, inst: CardInstance, clauseIndex: Int?): String {
        val scopePart = when (limit.scope) {
            LimitScope.THIS_CARD -> "card:${inst.uid}"
            LimitScope.SAME_NAME -> "name:${inst.card.name}"
            LimitScope.CATEGORY ->
                "category:" + (
                    limit.categoryId
                        ?: inst.card.categoryIds.sorted().joinToString("+")
                    )
        }
        return if (clauseIndex == null) scopePart else "$scopePart#$clauseIndex"
    }

    /** 効果番号より前に書いた制限の枠。適用範囲の指定によって分かれる。 */
    private fun cardWideLimitKey(
        limit: UsageLimit,
        inst: CardInstance,
        clauseIndex: Int
    ): String {
        val group =
            if (limit.clauseIndices.isEmpty()) "all"
            else limit.clauseIndices.sorted().joinToString(",")
        return when (limit.applies) {
            LimitApplies.EACH -> limitKey(limit, inst, clauseIndex) + "@$group"
            LimitApplies.TOGETHER -> limitKey(limit, inst, null) + "@$group"
        }
    }

    /** この発動が消費する制限の枠と、その上限の一覧。 */
    private fun applicableLimits(
        inst: CardInstance,
        clauseIndex: Int
    ): List<Pair<String, Int>> {
        val effect = effectOf(inst) ?: return emptyList()
        val cardWide = effect.limits
            .filter { it.coversClause(clauseIndex) }
            .map { cardWideLimitKey(it, inst, clauseIndex) to it.times.coerceAtLeast(1) }
        val perClause = effect.clauseLimitsFor(clauseIndex)
            .map { limitKey(it, inst, clauseIndex) to it.times.coerceAtLeast(1) }
        return cardWide + perClause
    }

    /** 【制限】の残り発動回数。制限が無ければ null。 */
    fun remainingActivations(
        inst: CardInstance,
        clauseIndex: Int,
        controller: PlayerState
    ): Int? {
        val limits = applicableLimits(inst, clauseIndex)
        if (limits.isEmpty()) return null
        return limits.minOf { (key, times) ->
            times - controller.activationsThisTurn.count { key in it.limitKeys }
        }.coerceAtLeast(0)
    }

    fun limitsAllow(inst: CardInstance, clauseIndex: Int, controller: PlayerState): Boolean =
        applicableLimits(inst, clauseIndex).all { (key, times) ->
            controller.activationsThisTurn.count { key in it.limitKeys } < times
        }

    private fun recordActivation(
        inst: CardInstance,
        clauseIndex: Int,
        controller: PlayerState
    ) {
        controller.activationsThisTurn.add(
            ActivationRecord(
                instanceUid = inst.uid,
                cardName = inst.card.name,
                categoryIds = inst.card.categoryIds,
                clauseIndex = clauseIndex,
                limitKeys = applicableLimits(inst, clauseIndex).map { it.first }
            )
        )
    }

    /**
     * 発動できない理由を日本語で返す。発動できるなら null。
     * 画面や不具合報告で「なぜ出来ないのか」が分かるようにするためのもの。
     */
    fun whyCannotActivate(inst: CardInstance, controller: PlayerState): String? {
        val effect = effectOf(inst)
        if (effect == null || effect.isEmpty) return "このカードは効果を持っていない。"
        if (activatableClauses(inst, controller).isNotEmpty()) return null

        val isOwnTurn = state.turnPlayer === controller
        val inMainPhase = state.phase == Phase.MAIN1 || state.phase == Phase.MAIN2

        if (inst.card.kind == CardKind.SPELL && !isOwnTurn) {
            return "魔法カードは自分のターンにしか発動できない。"
        }
        if (inst.card.kind != CardKind.TRAP && !inMainPhase) {
            return "メインフェイズにしか発動できない。"
        }
        if (inst.card.kind == CardKind.TRAP && locate(inst)?.zone == ZoneType.SPELL_TRAP_ZONE &&
            inst.setOnTurn >= state.turn
        ) {
            return "伏せたターンには発動できない。"
        }

        val reasons = effect.clauses.indices.mapNotNull { index ->
            when {
                !effect.clauses[index].hasWork -> null
                !canActivateFrom(inst, effectiveLocations(effect, index)) ->
                    "この場所からは発動できない。"

                !limitsAllow(inst, index, controller) ->
                    "【制限】により、このターンはもう発動できない。"

                // holder を渡さないと「このカードが〜」の条件が常に不成立になる。
                !conditionsMet(effect.conditionsFor(index), controller, inst) ->
                    "【条件】を満たしていない。"

                !canPayCosts(effect.costsFor(index), controller, excluding = inst) ->
                    "【コスト】を支払えない。"

                !canResolveActions(effect.clauses[index], controller, inst) ->
                    "効果を最後まで処理できる対象がそろっていない。"

                needsFieldPlacement(inst, effectiveLocations(effect, index)) &&
                    controller.freeSpellTrapZones().isEmpty() ->
                    "魔法・罠ゾーンに空きが無い。"

                else -> null
            }
        }
        return reasons.firstOrNull() ?: "今はこのカードを発動できない。"
    }

    /** いまこのプレイヤーが発動できるカードを、手札・フィールド・墓地から集める。 */
    fun activatableCards(controller: PlayerState): List<CardInstance> {
        val result = mutableListOf<CardInstance>()
        val isOwnTurn = state.turnPlayer === controller

        if (isOwnTurn) {
            // 魔法カードは自分のターンのみ。手札からも、伏せた状態からも発動できる。
            result += controller.hand.filter {
                it.card.kind == CardKind.SPELL && activatableClauses(it, controller).isNotEmpty()
            }
            result += controller.spellsAndTraps.filter {
                it.card.kind == CardKind.SPELL && activatableClauses(it, controller).isNotEmpty()
            }
            // 永続魔法のように、発動時処理か永続の効果しか持たないカードの発動。
            result += (controller.hand + controller.spellsAndTraps).filter {
                canActivateCardItself(it, controller)
            }
            // モンスターの起動効果。【場所】次第で手札・墓地からも発動できる。
            result += controller.monsters.filter {
                !it.faceDown && activatableClauses(it, controller).isNotEmpty()
            }
            result += controller.hand.filter {
                it.card.kind == CardKind.MONSTER && activatableClauses(it, controller).isNotEmpty()
            }
            result += (controller.graveyard + controller.banished).filter {
                activatableClauses(it, controller).isNotEmpty()
            }
        }

        // 誘発即時効果は、相手のターンでも発動できる。
        if (!isOwnTurn) {
            val pool = controller.monsters.filter { !it.faceDown } +
                controller.spellsAndTraps.filter { it.card.kind != CardKind.TRAP } +
                controller.hand + controller.graveyard + controller.banished
            result += pool.filter { inst ->
                val effect = effectOf(inst) ?: return@filter false
                activatableClauses(inst, controller).any { isQuickEffect(inst, effect, it) }
            }
        }

        // 罠は伏せた次のターン以降なら、どちらのターンでも発動できる。
        result += controller.spellsAndTraps.filter { canActivateTrap(it, controller) }
        result += controller.spellsAndTraps.filter {
            it.card.kind == CardKind.TRAP && canActivateCardItself(it, controller)
        }

        // 【場所】に『手札』と書いた罠だけは、手札から直接発動できる。
        result += controller.hand.filter {
            it.card.kind == CardKind.TRAP && activatableClauses(it, controller).isNotEmpty()
        }

        return result.distinct()
    }

    fun canActivateTrap(inst: CardInstance, controller: PlayerState): Boolean =
        inst.card.kind == CardKind.TRAP &&
            inst.faceDown &&
            inst.setOnTurn in 0 until state.turn &&
            (activatableClauses(inst, controller).isNotEmpty() ||
                canActivateCardItself(inst, controller))

    /**
     * 「カードの発動」ができるか。
     *
     * 永続魔法のように、発動時処理か永続の効果しか持たない魔法・罠を
     * フィールドに出すための操作。
     */
    fun canActivateCardItself(inst: CardInstance, controller: PlayerState): Boolean {
        if (inst.card.kind == CardKind.MONSTER) return false
        if (inst.uid in resolvingCards) return false
        val effect = inst.card.effect ?: return false
        if (!effect.supportsCardActivation()) return false
        // 表側で場に出ているカードは、既に発動を終えている。
        if (isOnField(inst) && !inst.faceDown) return false

        val locations = effect.locations.ifEmpty { listOf(ActivationLocation.FIELD) }
        if (!canActivateFrom(inst, locations)) return false
        if (needsFieldPlacement(inst, locations) && controller.freeSpellTrapZones().isEmpty()) {
            return false
        }

        val isOwnTurn = state.turnPlayer === controller
        val declaredPhases = effect.clauses.indices
            .filter { effect.isOnActivation(it) || effect.isContinuous(it) }
            .flatMap { effect.phasesFor(it) }
        val phaseOk =
            if (declaredPhases.isEmpty()) state.phase.isMain else state.phase in declaredPhases
        when (inst.card.kind) {
            CardKind.SPELL -> if (!isOwnTurn || !phaseOk) return false
            CardKind.TRAP ->
                // 罠は伏せた次のターン以降。
                if (!inst.faceDown || inst.setOnTurn !in 0 until state.turn) return false

            CardKind.MONSTER -> return false
        }

        if (!limitsAllow(inst, CARD_ACTIVATION, controller)) return false
        if (!conditionsMet(effect.conditions, controller, inst)) return false
        if (!canPayCosts(effect.costs, controller, excluding = inst)) return false
        // 発動時処理があるなら、それを最後まで通せることを確かめる。
        return effect.onActivationClauses().all { index ->
            canResolveActions(effect.clauses[index], controller, inst)
        }
    }

    /**
     * カードそのものを発動する。魔法・罠ゾーンに出し、【発動タイプ】が
     * 「発動時」の効果をまとめて処理してから【発動後】の処理を行う。
     */
    private suspend fun activateCardItself(
        inst: CardInstance,
        controller: PlayerState
    ): Boolean {
        activationDepth++
        val fresh = resolvingCards.add(inst.uid)
        try {
            return activateCardItselfInner(inst, controller)
        } finally {
            if (fresh) resolvingCards.remove(inst.uid)
            activationDepth--
            if (activationDepth == 0) flushPendingAdvance()
        }
    }

    private suspend fun activateCardItselfInner(
        inst: CardInstance,
        controller: PlayerState
    ): Boolean {
        val effect = inst.card.effect ?: return false
        val locations = effect.locations.ifEmpty { listOf(ActivationLocation.FIELD) }
        val placeOnField = needsFieldPlacement(inst, locations)

        if (placeOnField) {
            val zone = controller.freeSpellTrapZones().firstOrNull() ?: return false
            controller.hand.remove(inst)
            controller.spellTrapZones[zone] = inst
        }
        if (isOnField(inst)) inst.faceDown = false

        log("${controller.name}は「${inst.card.name}」を発動。")

        if (!payCosts(effect.costs, controller, excluding = inst)) {
            log("「${inst.card.name}」はコストを支払えなかったため発動を取り消した。")
            if (placeOnField) {
                controller.spellTrapZones.indexOfFirst { it === inst }
                    .takeIf { it >= 0 }
                    ?.let { controller.spellTrapZones[it] = null }
                controller.hand.add(inst)
            }
            return false
        }

        recordActivation(inst, CARD_ACTIVATION, controller)
        applySummonLocks(effect.summonLocks, controller)
        emit(GameEvent(GameEventType.ACTIVATED, controller.index, inst))

        if (offerResponse(controller, "「${inst.card.name}」の発動")) {
            log("「${inst.card.name}」の発動は無効になった。")
            disposeAfterActivation(inst, AfterActivation.TO_GRAVE)
            return true
        }

        // 発動時の効果処理。
        val zoneBefore = locate(inst)?.zone
        runOnActivationClauses(effect, controller, inst)

        // 効果自身がカードを動かしていたら、そのままにしておく。
        if (locate(inst)?.zone == zoneBefore) {
            disposeAfterActivation(inst, effect.afterActivationForCard(inst.card.kind))
        }
        return true
    }

    /** 【発動タイプ】が「発動時」の効果をまとめて処理する。 */
    private suspend fun runOnActivationClauses(
        effect: EffectText,
        controller: PlayerState,
        inst: CardInstance
    ) {
        for (index in effect.onActivationClauses()) {
            if (state.finished) break
            val clause = effect.clauses[index]
            if (!conditionsMet(effect.conditionsFor(index), controller, inst)) continue
            if (!canPayCosts(effect.costsFor(index), controller, excluding = inst)) continue
            if (!payCosts(clause.costs, controller, excluding = inst)) continue
            runClause(clause, controller, inst)
        }
    }

    /**
     * カードを発動する。効果が複数ある場合はどの番号を使うか選ばせる。
     */
    suspend fun activateCard(inst: CardInstance, controller: PlayerState): Boolean {
        val clauses = activatableClauses(inst, controller)
        // 番号の効果を発動できるなら、それがそのまま「カードの発動」になるので、
        // 「このカードを発動する」を別に並べない。
        val cardActivation = canActivateCardItself(inst, controller) && clauses.isEmpty()

        if (clauses.isEmpty() && !cardActivation) {
            val reason = whyCannotActivate(inst, controller) ?: "今は発動できない。"
            log("「${inst.card.name}」は発動できなかった：$reason")
            interaction.notify(controller.index, "「${inst.card.name}」：$reason")
            return false
        }

        // 「カードの発動」と、個々の起動効果を並べて選ばせる。
        val choices = buildList {
            if (cardActivation) add(CARD_ACTIVATION)
            addAll(clauses)
        }
        val picked = if (choices.size == 1) {
            choices.first()
        } else {
            val labels = choices.map {
                if (it == CARD_ACTIVATION) "このカードを発動する"
                else "${EffectNumbers.circled(it)} の効果"
            }
            choices[interaction.chooseOption(controller.index, "発動する内容を選択", labels)]
        }

        return if (picked == CARD_ACTIVATION) activateCardItself(inst, controller)
        else activateClause(inst, picked, controller)
    }

    /** 効果番号を指定して発動する。選択を挟まずに試したいときに使う。 */
    suspend fun activateClauseForTest(
        inst: CardInstance,
        clauseIndex: Int,
        controller: PlayerState
    ): Boolean = activateClause(inst, clauseIndex, controller)

    private suspend fun activateClause(
        inst: CardInstance,
        clauseIndex: Int,
        controller: PlayerState
    ): Boolean {
        activationDepth++
        val fresh = resolvingCards.add(inst.uid)
        try {
            return activateClauseInner(inst, clauseIndex, controller)
        } finally {
            if (fresh) resolvingCards.remove(inst.uid)
            activationDepth--
            if (activationDepth == 0) flushPendingAdvance()
        }
    }

    /**
     * 溜めておいたフェイズの進め方の変更を適用する。
     * 効果の処理の途中で盤面の進行を動かさないための仕組み。
     */
    private suspend fun flushPendingAdvance() {
        val kind = pendingAdvance ?: return
        pendingAdvance = null
        if (state.finished) return
        when (kind) {
            PhaseAdvance.SKIP_PHASE -> {
                log("${state.phase.label}をスキップした。")
                advancePhase()
            }

            PhaseAdvance.TO_END_PHASE -> {
                if (state.phase != Phase.END) {
                    log("エンドフェイズになった。")
                    var guard = 0
                    while (state.phase != Phase.END && !state.finished && guard++ < 5) {
                        advancePhase()
                    }
                }
            }

            PhaseAdvance.END_TURN -> {
                log("ターンを終了した。")
                endTurnImmediately()
            }
        }
    }

    private suspend fun activateClauseInner(
        inst: CardInstance,
        clauseIndex: Int,
        controller: PlayerState
    ): Boolean {
        val effect = effectOf(inst) ?: return false
        val clause = effect.clauses.getOrNull(clauseIndex) ?: return false
        val isSpellOrTrap = inst.card.kind != CardKind.MONSTER
        val locations = effectiveLocations(effect, clauseIndex)

        // 手札の魔法・罠を「フィールドで発動」する場合は、魔法・罠ゾーンへ置く。
        val placeOnField = needsFieldPlacement(inst, locations)
        if (placeOnField) {
            val zone = controller.freeSpellTrapZones().firstOrNull()
            if (zone == null) {
                interaction.notify(controller.index, "魔法・罠ゾーンに空きが無い。")
                return false
            }
            controller.hand.remove(inst)
            controller.spellTrapZones[zone] = inst
        }
        // 魔法・罠は、番号の効果を発動することがそのまま「カードの発動」になる。
        // まだ表になっていなければ、これがそのカードの発動。
        val isCardActivation = isSpellOrTrap && (placeOnField || inst.faceDown ||
            locate(inst)?.zone == ZoneType.HAND)
        if (isOnField(inst)) inst.faceDown = false

        log("${controller.name}は「${inst.card.name}」の${EffectNumbers.circled(clauseIndex)}を発動。")

        // コストは発動宣言時に支払う。払えなければ発動そのものを取り消す。
        if (!payCosts(effect.costsFor(clauseIndex), controller, excluding = inst)) {
            log("「${inst.card.name}」はコストを支払えなかったため発動を取り消した。")
            if (placeOnField) {
                // 手札から出したところだったので手札に戻す。
                controller.spellTrapZones.indexOfFirst { it === inst }
                    .takeIf { it >= 0 }
                    ?.let { controller.spellTrapZones[it] = null }
                controller.hand.add(inst)
            }
            return false
        }

        recordActivation(inst, clauseIndex, controller)
        // 【制限】の召喚制限は、発動した時点で掛かる（無効にされても残る）。
        applySummonLocks(effect.summonLocksFor(clauseIndex), controller)
        emit(GameEvent(GameEventType.ACTIVATED, controller.index, inst))

        // 相手に応答（罠）の機会を与える。
        val negated = offerResponse(controller, "「${inst.card.name}」の発動")
        if (negated) {
            log("「${inst.card.name}」の発動は無効になった。")
            if (isSpellOrTrap) disposeAfterActivation(inst, AfterActivation.TO_GRAVE) else destroy(inst)
            return true
        }

        val zoneBefore = locate(inst)?.zone
        // 「このカードの発動時に」処理する効果は、番号の効果より先に処理する。
        if (isCardActivation) runOnActivationClauses(effect, controller, inst)
        runClause(clause, controller, inst)

        // 【発動後】の処理。省略時は魔法・罠なら墓地へ、モンスターならそのまま。
        // 効果自身がカードを動かしていたら、そのままにしておく。
        if (locate(inst)?.zone == zoneBefore) {
            disposeAfterActivation(inst, effect.afterActivationFor(clauseIndex, inst.card.kind))
        }
        return true
    }

    /**
     * 魔法・罠ゾーンにカードを置く。置けた枚数分だけ true を返す。
     */
    private fun placeIntoSpellTrapZone(
        targets: List<CardInstance>,
        controller: PlayerState,
        faceDown: Boolean
    ): Boolean {
        var placedAny = false
        for (target in targets) {
            if (target.card.kind == CardKind.MONSTER) continue
            val zone = controller.freeSpellTrapZones().firstOrNull()
            if (zone == null) {
                log("魔法・罠ゾーンに空きが無い。")
                break
            }
            removeFromCurrent(target)
            resetInstance(target)
            controller.spellTrapZones[zone] = target
            target.faceDown = faceDown
            if (faceDown) target.setOnTurn = state.turn
            log(
                "${controller.name}は「${target.card.name}」を魔法・罠ゾーンに" +
                    if (faceDown) "セットした。" else "表側で置いた。"
            )
            placedAny = true
        }
        return placedAny
    }

    /** 【発動後】の指定に従って、発動し終えたカードを片付ける。 */
    private fun disposeAfterActivation(inst: CardInstance, after: AfterActivation) {
        val zone = locate(inst)?.zone ?: return
        val onFieldOrHand =
            zone == ZoneType.SPELL_TRAP_ZONE || zone == ZoneType.MONSTER_ZONE || zone == ZoneType.HAND
        if (!onFieldOrHand) return

        when (after) {
            AfterActivation.TO_GRAVE -> sendToGraveyard(inst)
            AfterActivation.BANISH -> banish(inst)
            AfterActivation.TO_HAND -> returnToHand(inst)
            AfterActivation.TO_DECK -> returnToDeck(inst, toBottom = false)
            AfterActivation.STAY_ON_FIELD ->
                // 手札で発動したカードは場に残れないので、その場合だけ墓地へ送る。
                if (zone == ZoneType.HAND) sendToGraveyard(inst)
        }
    }

    /**
     * 相手に伏せ罠での応答を許す。無効化する罠が発動されたら true を返す。
     */
    private suspend fun offerResponse(activator: PlayerState, description: String): Boolean {
        if (responseDepth > 0 || state.finished) return false
        val responder = state.opponentOf(activator)
        val cards = respondableCards(responder)
        if (cards.isEmpty()) return false

        val chosen = interaction.chooseCards(
            responder.index,
            "$description に対してカードを発動しますか？（発動しない場合はそのまま決定）",
            cards, 0, 1
        )
        val card = chosen.firstOrNull() ?: return false

        val negates = card.card.effect?.clauses.orEmpty()
            .any { clause -> clause.actions.any { it is NegateAction } }

        responseDepth++
        try {
            activateCard(card, responder)
        } finally {
            responseDepth--
        }
        return negates
    }

    /**
     * 相手の行動に割り込んで発動できるカードの一覧。
     *
     * セットしてある罠と、【場所】にフィールド以外を書いた効果
     * （手札誘発や、墓地・除外ゾーンから発動する効果）が対象になる。
     */
    fun respondableCards(responder: PlayerState): List<CardInstance> {
        val pool = responder.spellsAndTraps + responder.hand +
            responder.monsters + responder.graveyard + responder.banished

        return pool.filter { inst ->
            // 裏側のカードは、セットした次のターン以降の罠だけが応答できる。
            if (isOnField(inst) && inst.faceDown) {
                if (inst.card.kind != CardKind.TRAP) return@filter false
                if (inst.setOnTurn !in 0 until state.turn) return@filter false
            }
            val effect = effectOf(inst) ?: return@filter false
            val quick = activatableClauses(inst, responder).any {
                isQuickEffect(inst, effect, it)
            }
            quick || canActivateCardItself(inst, responder)
        }
    }

    // =======================================================================
    // 誘発効果（イベント条件を持つ効果）
    // =======================================================================

    /**
     * 出来事を盤面に知らせ、条件が一致した誘発効果を発動させる。
     *
     * 任意（発動できる）の効果は確認を取り、強制（発動する）の効果はそのまま
     * 発動する。効果が効果を呼ぶ連鎖は [triggerDepth] で打ち切る。
     */
    private suspend fun emit(vararg events: GameEvent) {
        for (raw in events) {
            if (state.finished || triggerDepth >= MAX_TRIGGER_DEPTH) return
            // 出来事の側で原因を指定していなければ、いま処理中の原因を付ける。
            val event =
                if (raw.cause != EventCause.UNKNOWN) raw
                else raw.copy(
                    cause = causeKind,
                    causePlayer = causePlayer,
                    sourceCard = causeSource
                )
            state.eventsThisTurn += event
            triggerDepth++
            try {
                dispatch(event)
            } finally {
                triggerDepth--
            }
        }
    }

    private suspend fun dispatch(event: GameEvent) {
        // ターンプレイヤー側から順に見る。解決中に盤面が変わるので控えを取る。
        val order = listOf(state.turnPlayer, state.nonTurnPlayer)
        for (player in order) {
            val candidates = (
                player.monsters + player.spellsAndTraps + player.hand + player.graveyard
                ).toList()

            for (inst in candidates) {
                val effect = effectOf(inst) ?: continue
                for (index in effect.clauses.indices) {
                    if (state.finished) return
                    if (!isTriggeredBy(inst, index, player, event)) continue

                    val clause = effect.clauses[index]
                    if (clause.mode == ActivationMode.OPTIONAL) {
                        val label = "「${inst.card.name}」の" +
                            "${EffectNumbers.circled(index)}を発動しますか？"
                        if (!interaction.confirm(player.index, label)) continue
                    }
                    activateClause(inst, index, player)
                }
            }
        }
    }

    /** [inst] の [index] 番目の効果が、この出来事で発動できる状態かどうか。 */
    private fun isTriggeredBy(
        inst: CardInstance,
        index: Int,
        controller: PlayerState,
        event: GameEvent
    ): Boolean {
        val effect = effectOf(inst) ?: return false
        val clause = effect.clauses.getOrNull(index) ?: return false
        if (!clause.hasWork) return false
        if (!clause.mode.isStandalone) return false
        if (!effect.isTriggered(index)) return false
        if (!canActivateFrom(inst, effectiveLocations(effect, index))) return false

        // 伏せてあるカードの制約は誘発効果にも掛かる。
        if (isOnField(inst) && inst.faceDown) {
            // 罠は伏せた次のターン以降。裏側のモンスターは効果を発動しない。
            if (inst.card.kind != CardKind.TRAP) return false
            if (inst.setOnTurn !in 0 until state.turn) return false
        }

        if (!limitsAllow(inst, index, controller)) return false
        if (!conditionsMet(effect.conditionsFor(index), controller, inst, event)) return false
        if (!canPayCosts(effect.costsFor(index), controller, excluding = inst)) return false
        return canResolveActions(clause, controller, inst)
    }

    // =======================================================================
    // 召喚とセット
    // =======================================================================

    /**
     * 【制限】に書かれた召喚の制限を掛ける。
     *
     * 効果ではなく発動そのものに付く制限なので、効果を無効にされても掛かったまま。
     */
    private fun applySummonLocks(locks: List<SummonLock>, controller: PlayerState) {
        for (lock in locks) {
            val restriction = SummonRestriction(lock.summon, lock.filters, lock.except)
            playersFor(lock.who, controller).forEach { player ->
                player.summonRestrictions.add(restriction)
                log("${player.name}はこのターン、${restrictionText(restriction)}。")
            }
        }
    }

    /** ログや通知に出す、召喚制限の一文。 */
    private fun restrictionText(restriction: SummonRestriction): String {
        val noun = if (restriction.filters.isEmpty()) "モンスター"
        else EffectTextRenderer.filtersToNoun(
            restriction.filters, state.master, ZoneType.MONSTER_ZONE
        )
        val head = if (restriction.except) "${noun}以外のモンスター" else noun
        return "${head}を${restriction.summon.label}できない"
    }

    /** [inst] を [kind] の方法で出せるか。掛かっている召喚制限を見る。 */
    fun summonAllowed(
        inst: CardInstance,
        controller: PlayerState,
        kind: SummonKind
    ): Boolean = controller.summonRestrictions.none { restriction ->
        val applies = restriction.summon == SummonKind.ANY || restriction.summon == kind
        if (!applies) return@none false
        val matches = matchesAll(inst, restriction.filters)
        // 「〜以外を出せない」なら、当てはまらないカードが禁止される。
        if (restriction.except) !matches else matches
    }

    fun canNormalSummon(inst: CardInstance, controller: PlayerState): Boolean {
        if (inst.card.kind != CardKind.MONSTER) return false
        if (state.turnPlayer !== controller) return false
        if (!summonAllowed(inst, controller, SummonKind.NORMAL)) return false
        if (state.phase != Phase.MAIN1 && state.phase != Phase.MAIN2) return false
        if (controller.normalSummonUsed) return false
        if (locate(inst)?.zone != ZoneType.HAND) return false

        val need = inst.card.tributesRequired
        if (controller.monsters.size < need) return false
        // リリース後に空くゾーンも数に入れる。
        return controller.freeMonsterZones().size + need > 0
    }

    /**
     * 通常召喚。[asSet] が true なら裏側守備表示でセットする。
     * レベル5〜6は1体、7以上は2体のリリースが必要。
     */
    suspend fun normalSummon(
        inst: CardInstance,
        controller: PlayerState,
        asSet: Boolean
    ): Boolean {
        if (!canNormalSummon(inst, controller)) {
            interaction.notify(controller.index, "そのモンスターは今は通常召喚できない。")
            return false
        }

        val need = inst.card.tributesRequired
        if (need > 0) {
            val chosen = interaction.chooseCards(
                controller.index,
                "リリースするモンスターを${need}体選択",
                controller.monsters, need, need
            )
            if (chosen.size < need) return false
            chosen.forEach {
                log("${controller.name}は「${it.card.name}」をリリースした。")
                sendToGraveyard(it)
            }
        }

        val zone = controller.freeMonsterZones().firstOrNull()
        if (zone == null) {
            interaction.notify(controller.index, "モンスターゾーンに空きが無い。")
            return false
        }

        controller.hand.remove(inst)
        controller.monsterZones[zone] = inst
        controller.normalSummonUsed = true
        inst.summonedOnTurn = state.turn

        if (asSet) {
            applyPosition(inst, Position.FACE_DOWN_DEFENSE)
            inst.setOnTurn = state.turn
            log("${controller.name}はモンスターを1体セットした。")
            return true
        }

        applyPosition(inst, Position.ATTACK)
        log("${controller.name}は「${inst.card.name}」を召喚した。")

        if (offerResponse(controller, "「${inst.card.name}」の召喚")) {
            log("召喚は無効になった。")
            destroy(inst)
            return true
        }
        emit(
            GameEvent(GameEventType.NORMAL_SUMMONED, controller.index, inst),
            GameEvent(GameEventType.SUMMONED, controller.index, inst)
        )
        return true
    }

    fun canSetSpellTrap(inst: CardInstance, controller: PlayerState): Boolean =
        inst.card.kind != CardKind.MONSTER &&
            state.turnPlayer === controller &&
            (state.phase == Phase.MAIN1 || state.phase == Phase.MAIN2) &&
            locate(inst)?.zone == ZoneType.HAND &&
            controller.freeSpellTrapZones().isNotEmpty()

    fun setSpellTrap(inst: CardInstance, controller: PlayerState): Boolean {
        if (!canSetSpellTrap(inst, controller)) return false
        val zone = controller.freeSpellTrapZones().first()
        controller.hand.remove(inst)
        controller.spellTrapZones[zone] = inst
        inst.faceDown = true
        inst.setOnTurn = state.turn
        log("${controller.name}はカードを1枚セットした。")
        return true
    }

    /** 表側攻撃表示 ⇔ 表側守備表示の変更。1ターンに1度、召喚したターンは不可。 */
    fun canChangePosition(inst: CardInstance, controller: PlayerState): Boolean =
        state.turnPlayer === controller &&
            (state.phase == Phase.MAIN1 || state.phase == Phase.MAIN2) &&
            locate(inst)?.zone == ZoneType.MONSTER_ZONE &&
            !inst.positionChangedThisTurn &&
            !inst.hasAttacked &&
            inst.summonedOnTurn != state.turn

    fun changePosition(inst: CardInstance, controller: PlayerState): Boolean {
        if (!canChangePosition(inst, controller)) return false
        applyPosition(
            inst,
            if (inst.faceDown || inst.position == Position.DEFENSE) Position.ATTACK
            else Position.DEFENSE
        )
        inst.positionChangedThisTurn = true
        log("${controller.name}は「${inst.card.name}」を${inst.displayPosition.label}にした。")
        return true
    }

    // =======================================================================
    // バトル
    // =======================================================================

    fun canAttack(inst: CardInstance): Boolean {
        if (state.phase != Phase.BATTLE || state.finished) return false
        val loc = locate(inst) ?: return false
        if (loc.zone != ZoneType.MONSTER_ZONE) return false
        if (loc.player !== state.turnPlayer) return false
        if (isAttackLocked(inst)) return false
        return !inst.faceDown && inst.position == Position.ATTACK && !inst.hasAttacked
    }

    /**
     * 攻撃対象の一覧。相手フィールドにモンスターがいる限り
     * プレイヤーへの直接攻撃はできない（空リスト＝直接攻撃のみ）。
     */
    fun attackTargets(): List<CardInstance> = state.nonTurnPlayer.monsters

    fun canAttackDirectly(): Boolean = !state.nonTurnPlayer.hasMonsters

    suspend fun declareAttack(attacker: CardInstance, target: CardInstance?) {
        if (!canAttack(attacker)) return
        val attackingPlayer = state.turnPlayer
        val defendingPlayer = state.nonTurnPlayer

        attacker.hasAttacked = true
        log("${attackingPlayer.name}の「${attacker.card.name}」が攻撃宣言。")

        emit(GameEvent(GameEventType.ATTACK_DECLARED, attackingPlayer.index, attacker))
        if (state.finished) return

        if (offerResponse(attackingPlayer, "「${attacker.card.name}」の攻撃")) {
            log("攻撃は無効になった。")
            return
        }
        if (state.finished) return
        // 応答で攻撃モンスターが場を離れた場合は攻撃が消える。
        if (locate(attacker)?.zone != ZoneType.MONSTER_ZONE) return

        if (target == null) {
            if (defendingPlayer.hasMonsters) {
                log("相手フィールドにモンスターがいるため直接攻撃はできない。")
                return
            }
            log("${defendingPlayer.name}への直接攻撃！")
            dealDamage(defendingPlayer, atkOf(attacker))
            return
        }

        if (locate(target)?.zone != ZoneType.MONSTER_ZONE) return

        if (target.faceDown) {
            target.faceDown = false
            target.position = Position.DEFENSE
            log("「${target.card.name}」が反転した。")
        }

        val attack = atkOf(attacker)
        if (target.position == Position.ATTACK) {
            val defenderAttack = atkOf(target)
            when {
                attack > defenderAttack -> {
                    dealDamage(defendingPlayer, attack - defenderAttack)
                    destroy(target, byBattle = true)
                }

                attack < defenderAttack -> {
                    dealDamage(attackingPlayer, defenderAttack - attack)
                    destroy(attacker, byBattle = true)
                }

                else -> {
                    destroy(target, byBattle = true)
                    destroy(attacker, byBattle = true)
                }
            }
        } else {
            val defense = defOf(target)
            when {
                attack > defense -> destroy(target, byBattle = true)
                attack < defense -> dealDamage(attackingPlayer, defense - attack)
                else -> log("戦闘は相殺された。")
            }
        }
    }

    // =======================================================================
    // ターン進行
    // =======================================================================

    suspend fun advancePhase() {
        if (state.finished) return
        when (state.phase) {
            Phase.DRAW -> {
                state.phase = Phase.MAIN1
                offerPhaseActivations()
            }

            // 最初のターンにバトルフェイズは無い。
            Phase.MAIN1 -> {
                state.phase = if (state.turn == 1) Phase.MAIN2 else Phase.BATTLE
                offerPhaseActivations()
            }

            Phase.BATTLE -> {
                state.phase = Phase.MAIN2
                offerPhaseActivations()
            }

            Phase.MAIN2 -> {
                state.phase = Phase.END
                offerPhaseActivations()
                runEndPhase()
            }

            Phase.END -> startNextTurn()
        }
    }

    /**
     * そのフェイズを指定した効果があれば、発動するか確認する。
     *
     * エンドフェイズのように手が出せないフェイズでも、
     * 「エンドフェイズに発動できる」効果を使えるようにするための窓口。
     */
    private suspend fun offerPhaseActivations() {
        if (state.finished) return
        for (player in listOf(state.turnPlayer, state.nonTurnPlayer)) {
            var guard = 0
            while (guard++ < 5 && !state.finished) {
                val candidates = activatableCards(player).filter { declaresCurrentPhase(it) }
                if (candidates.isEmpty()) break

                val chosen = interaction.chooseCards(
                    player.index,
                    "${state.phase.label}に発動できるカードがあります",
                    candidates, 0, 1
                )
                val card = chosen.firstOrNull() ?: break
                activateCard(card, player)
            }
        }
    }

    /** いまのフェイズを【条件】で名指ししている効果を持つか。 */
    private fun declaresCurrentPhase(inst: CardInstance): Boolean {
        val effect = effectOf(inst) ?: return false
        return effect.clauses.indices.any { state.phase in effect.phasesFor(it) }
    }

    /** エンドフェイズの手札上限（6枚）処理。 */
    private suspend fun runEndPhase() {
        val player = state.turnPlayer
        val excess = player.hand.size - 6
        if (excess > 0) {
            val chosen = interaction.chooseCards(
                player.index, "手札が上限を超えています。捨てるカードを${excess}枚選択",
                player.hand.toList(), excess, excess
            )
            chosen.forEach { sendToGraveyard(it) }
            log("${player.name}は手札上限のためカードを${excess}枚捨てた。")
        }
    }

    private suspend fun startNextTurn() {
        if (state.finished) return
        state.turnPlayerIndex = 1 - state.turnPlayerIndex
        state.turn += 1

        state.eventsThisTurn.clear()
        state.players.forEach { player ->
            player.normalSummonUsed = false
            player.activationsThisTurn.clear()
            player.summonRestrictions.clear()
            (player.monsters + player.spellsAndTraps).forEach { it.resetForNewTurn() }
        }

        val player = state.turnPlayer
        state.phase = Phase.DRAW
        log("── ターン${state.turn}：${player.name}のターン ──")
        offerPhaseActivations()
        draw(player, 1)
        if (!state.finished) {
            state.phase = Phase.MAIN1
            offerPhaseActivations()
        }
    }

    /** バトルフェイズを飛ばしてターンを終える。 */
    suspend fun endTurnImmediately() {
        if (state.finished) return
        state.phase = Phase.END
        offerPhaseActivations()
        runEndPhase()
        startNextTurn()
    }

    fun surrender(playerIndex: Int) {
        finish(1 - playerIndex, "${state.players[playerIndex].name}が降参した")
    }
}

/** 効果番号のかわりに「カードの発動そのもの」を表す番号。 */
const val CARD_ACTIVATION = -1

object EffectNumbers {
    private val CIRCLED = listOf("①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩")
    fun circled(index: Int): String = CIRCLED.getOrElse(index) { "(${index + 1})" }
}
