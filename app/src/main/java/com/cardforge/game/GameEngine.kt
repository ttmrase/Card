package com.cardforge.game

import com.cardforge.model.*
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
    val card: CardInstance? = null
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
            emit(
                GameEvent(GameEventType.DESTROYED, loc.player.index, inst),
                GameEvent(GameEventType.SENT_TO_GRAVEYARD, loc.player.index, inst)
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

    /** いま適用されている永続の効果を、持ち主とセットで集める。 */
    private fun activeContinuous(): List<Triple<CardInstance, PlayerState, EffectClause>> {
        val result = mutableListOf<Triple<CardInstance, PlayerState, EffectClause>>()
        for (player in state.players) {
            val cards = player.monsters + player.spellsAndTraps + player.graveyard + player.hand
            for (inst in cards) {
                // 裏側のカードの効果は働かない。
                if (isOnField(inst) && inst.faceDown) continue
                val effect = inst.card.effect ?: continue
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
                    .sumOf { it.delta }
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
    }

    fun matchesAll(inst: CardInstance, filters: List<CardFilter>): Boolean =
        filters.all { matches(inst, it) }

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
            .filter { matchesAll(it, scope.filters) }
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
        return when (scope.selection) {
            SelectionMode.ALL -> pool
            SelectionMode.RANDOM -> pool.shuffled().take(scope.count)
            SelectionMode.CHOOSE -> {
                val n = min(scope.count, pool.size)
                interaction.chooseCards(controller.index, prompt, pool, n, n)
            }
        }
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
                is EventCondition -> event != null && holder != null &&
                    matchesEvent(condition, event, holder, controller)

                is CardExistsCondition -> {
                    val count = candidates(condition.scope, controller, holder).size
                    if (condition.negate) count == 0 else count >= condition.atLeast
                }

                is LifeCondition -> playersFor(condition.who, controller).all {
                    condition.cmp.test(it.life, condition.value)
                }

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
        }
    }

    private suspend fun payCosts(
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
            }
        }
        return true
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
                    log("「${it.card.name}」を除外した。")
                    banish(it)
                    emit(GameEvent(GameEventType.BANISHED, owner, it))
                }
                shuffleIfDeck(action.scope, controller)
            }

            is ToHandAction -> {
                val targets = resolveTargets(action.scope, controller, "手札に加えるカードを選択", source)
                targets.forEach {
                    log("「${it.card.name}」を手札に加えた。")
                    returnToHand(it)
                }
                shuffleIfDeck(action.scope, controller)
            }

            is ToGraveAction -> {
                val targets = resolveTargets(action.scope, controller, "墓地へ送るカードを選択", source)
                targets.forEach {
                    val owner = locate(it)?.player?.index ?: controller.index
                    log("「${it.card.name}」を墓地へ送った。")
                    sendToGraveyard(it)
                    emit(GameEvent(GameEventType.SENT_TO_GRAVEYARD, owner, it))
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
                    val zone = destination.freeMonsterZones().firstOrNull()
                    if (zone == null) {
                        log("モンスターゾーンに空きが無いため特殊召喚できない。")
                        break
                    }
                    removeFromCurrent(target)
                    resetInstance(target)
                    destination.monsterZones[zone] = target
                    applyPosition(target, action.position)
                    target.summonedOnTurn = state.turn
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
                targets.forEach { target ->
                    when (action.stat) {
                        StatKind.ATK -> target.atkMod += action.delta
                        StatKind.DEF -> target.defMod += action.delta
                    }
                    log("「${target.card.name}」の${action.stat.label}が${action.delta}変化した。")
                }
            }

            is ChangePositionAction -> {
                val targets = resolveTargets(action.scope, controller, "表示形式を変えるカードを選択", source)
                targets.filter { it.card.kind == CardKind.MONSTER }.forEach {
                    applyPosition(it, action.position)
                    log("「${it.card.name}」を${action.position.label}にした。")
                }
            }

            is DrawAction -> playersFor(action.who, controller).forEach { draw(it, action.count) }

            is DamageAction ->
                playersFor(action.who, controller).forEach { dealDamage(it, action.amount) }

            is RecoverAction ->
                playersFor(action.who, controller).forEach { recoverLife(it, action.amount) }

            is DiscardAction -> {
                for (player in playersFor(action.who, controller)) {
                    val n = min(action.count, player.hand.size)
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
                for (player in playersFor(action.who, controller)) {
                    repeat(min(action.count, player.deck.size)) {
                        sendToGraveyard(player.deck.first())
                    }
                    log("${player.name}はデッキの上から${action.count}枚を墓地へ送った。")
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
        val effect = inst.card.effect ?: return emptyList()

        return effect.clauses.indices.filter { index ->
            val clause = effect.clauses[index]
            if (clause.actions.isEmpty()) return@filter false

            // 永続と発動時処理はそれ自体を発動できない。
            // イベント条件を持つ効果は誘発効果なので手動では発動できない。
            if (!clause.mode.isStandalone) return@filter false
            if (effect.isTriggered(index)) return@filter false

            if (!canActivateFrom(inst, effectiveLocations(effect, index))) return@filter false
            if (!limitsAllow(inst, index, controller)) return@filter false

            // 手札から場に出して発動するなら、置ける空きが要る。
            if (needsFieldPlacement(inst, effectiveLocations(effect, index)) &&
                controller.freeSpellTrapZones().isEmpty()
            ) {
                return@filter false
            }

            conditionsMet(effect.conditionsFor(index), controller, inst) &&
                canPayCosts(effect.costsFor(index), controller, excluding = inst)
        }
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

    /** この発動が消費する制限の枠と、その上限の一覧。 */
    private fun applicableLimits(
        inst: CardInstance,
        clauseIndex: Int
    ): List<Pair<String, Int>> {
        val effect = inst.card.effect ?: return emptyList()
        val cardWide = effect.limits.map { limitKey(it, inst, null) to it.times.coerceAtLeast(1) }
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
        val effect = inst.card.effect
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
                effect.clauses[index].actions.isEmpty() -> null
                !canActivateFrom(inst, effectiveLocations(effect, index)) ->
                    "この場所からは発動できない。"

                !limitsAllow(inst, index, controller) ->
                    "【制限】により、このターンはもう発動できない。"

                !conditionsMet(effect.conditionsFor(index), controller) ->
                    "【条件】を満たしていない。"

                !canPayCosts(effect.costsFor(index), controller, excluding = inst) ->
                    "【コスト】を支払えない。"

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
        val inMainPhase = state.phase == Phase.MAIN1 || state.phase == Phase.MAIN2

        if (isOwnTurn && inMainPhase) {
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
            result += controller.graveyard.filter {
                activatableClauses(it, controller).isNotEmpty()
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
        val inMainPhase = state.phase == Phase.MAIN1 || state.phase == Phase.MAIN2
        when (inst.card.kind) {
            CardKind.SPELL -> if (!isOwnTurn || !inMainPhase) return false
            CardKind.TRAP ->
                // 罠は伏せた次のターン以降。
                if (!inst.faceDown || inst.setOnTurn !in 0 until state.turn) return false

            CardKind.MONSTER -> return false
        }

        if (!limitsAllow(inst, CARD_ACTIVATION, controller)) return false
        if (!conditionsMet(effect.conditions, controller, inst)) return false
        return canPayCosts(effect.costs, controller, excluding = inst)
    }

    /**
     * カードそのものを発動する。魔法・罠ゾーンに出し、【発動タイプ】が
     * 「発動時」の効果をまとめて処理してから【発動後】の処理を行う。
     */
    private suspend fun activateCardItself(
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
        emit(GameEvent(GameEventType.ACTIVATED, controller.index, inst))

        if (offerResponse(controller, "「${inst.card.name}」の発動")) {
            log("「${inst.card.name}」の発動は無効になった。")
            disposeAfterActivation(inst, AfterActivation.TO_GRAVE)
            return true
        }

        // 発動時の効果処理。
        for (index in effect.onActivationClauses()) {
            if (state.finished) break
            val clause = effect.clauses[index]
            if (!conditionsMet(effect.conditionsFor(index), controller, inst)) continue
            if (!canPayCosts(effect.costsFor(index), controller, excluding = inst)) continue
            if (!payCosts(clause.costs, controller, excluding = inst)) continue
            clause.actions.forEach { action ->
                if (!state.finished) applyAction(action, controller, source = inst)
            }
        }

        disposeAfterActivation(inst, effect.afterActivationForCard(inst.card.kind))
        return true
    }

    /**
     * カードを発動する。効果が複数ある場合はどの番号を使うか選ばせる。
     */
    suspend fun activateCard(inst: CardInstance, controller: PlayerState): Boolean {
        val clauses = activatableClauses(inst, controller)
        val cardActivation = canActivateCardItself(inst, controller)

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

    private suspend fun activateClause(
        inst: CardInstance,
        clauseIndex: Int,
        controller: PlayerState
    ): Boolean {
        val effect = inst.card.effect ?: return false
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
        emit(GameEvent(GameEventType.ACTIVATED, controller.index, inst))

        // 相手に応答（罠）の機会を与える。
        val negated = offerResponse(controller, "「${inst.card.name}」の発動")
        if (negated) {
            log("「${inst.card.name}」の発動は無効になった。")
            if (isSpellOrTrap) disposeAfterActivation(inst, AfterActivation.TO_GRAVE) else destroy(inst)
            return true
        }

        for (action in clause.actions) {
            if (state.finished) break
            applyAction(action, controller, source = inst)
        }

        // 【発動後】の処理。省略時は魔法・罠なら墓地へ、モンスターならそのまま。
        disposeAfterActivation(inst, effect.afterActivationFor(clauseIndex, inst.card.kind))
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
        val traps = responder.spellsAndTraps.filter { canActivateTrap(it, responder) }
        if (traps.isEmpty()) return false

        val chosen = interaction.chooseCards(
            responder.index,
            "$description に対して罠カードを発動しますか？（発動しない場合はそのまま決定）",
            traps, 0, 1
        )
        val trap = chosen.firstOrNull() ?: return false

        val negates = trap.card.effect?.clauses.orEmpty()
            .any { clause -> clause.actions.any { it is NegateAction } }

        responseDepth++
        try {
            activateCard(trap, responder)
        } finally {
            responseDepth--
        }
        return negates
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
        for (event in events) {
            if (state.finished || triggerDepth >= MAX_TRIGGER_DEPTH) return
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
                val effect = inst.card.effect ?: continue
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
        val effect = inst.card.effect ?: return false
        val clause = effect.clauses.getOrNull(index) ?: return false
        if (clause.actions.isEmpty()) return false
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
        return canPayCosts(effect.costsFor(index), controller, excluding = inst)
    }

    // =======================================================================
    // 召喚とセット
    // =======================================================================

    fun canNormalSummon(inst: CardInstance, controller: PlayerState): Boolean {
        if (inst.card.kind != CardKind.MONSTER) return false
        if (state.turnPlayer !== controller) return false
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
            Phase.DRAW -> state.phase = Phase.MAIN1

            // 最初のターンにバトルフェイズは無い。
            Phase.MAIN1 -> state.phase = if (state.turn == 1) Phase.MAIN2 else Phase.BATTLE

            Phase.BATTLE -> state.phase = Phase.MAIN2

            Phase.MAIN2 -> {
                state.phase = Phase.END
                runEndPhase()
            }

            Phase.END -> startNextTurn()
        }
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

        state.players.forEach { player ->
            player.normalSummonUsed = false
            player.activationsThisTurn.clear()
            (player.monsters + player.spellsAndTraps).forEach { it.resetForNewTurn() }
        }

        val player = state.turnPlayer
        state.phase = Phase.DRAW
        log("── ターン${state.turn}：${player.name}のターン ──")
        draw(player, 1)
        if (!state.finished) state.phase = Phase.MAIN1
    }

    /** バトルフェイズを飛ばしてターンを終える。 */
    suspend fun endTurnImmediately() {
        if (state.finished) return
        state.phase = Phase.END
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
