package com.cardforge.game

import com.cardforge.model.*
import kotlin.math.min

data class CardLocation(val player: PlayerState, val zone: ZoneType, val index: Int)

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
        inst.usedClausesThisTurn.clear()
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

    suspend fun destroy(inst: CardInstance) {
        val loc = locate(inst) ?: return
        val wasOnField = loc.zone == ZoneType.MONSTER_ZONE || loc.zone == ZoneType.SPELL_TRAP_ZONE
        log("${loc.player.name}の「${inst.card.name}」は破壊された。")
        sendToGraveyard(inst)
        if (wasOnField) triggerOnDestroyed(inst, loc.player)
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
            inst.card.kind == CardKind.MONSTER && filter.cmp.test(inst.atkValue, filter.value)

        is DefFilter ->
            inst.card.kind == CardKind.MONSTER && filter.cmp.test(inst.defValue, filter.value)

        is PositionFilter -> inst.displayPosition == filter.position
        is NameFilter -> inst.card.name.contains(filter.text, ignoreCase = true)
    }

    fun matchesAll(inst: CardInstance, filters: List<CardFilter>): Boolean =
        filters.all { matches(inst, it) }

    /** [scope] が指す候補カードを列挙する。裏側のカードは中身を見るフィルタでは選べない。 */
    fun candidates(scope: CardScope, controller: PlayerState): List<CardInstance> {
        val hidesInfo = needsCardInfo(scope.filters)
        return playersFor(scope.who, controller)
            .flatMap { zoneCards(it, scope.zone) }
            .filter { !(hidesInfo && it.faceDown) }
            .filter { matchesAll(it, scope.filters) }
    }

    private suspend fun resolveTargets(
        scope: CardScope,
        controller: PlayerState,
        prompt: String
    ): List<CardInstance> {
        val pool = candidates(scope, controller)
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

    fun conditionsMet(conditions: List<Condition>, controller: PlayerState): Boolean =
        conditions.all { condition ->
            when (condition) {
                is CardExistsCondition -> {
                    val count = candidates(condition.scope, controller).size
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
            }
        }
        return true
    }

    // =======================================================================
    // ライフとドロー
    // =======================================================================

    fun dealDamage(player: PlayerState, amount: Int) {
        if (amount <= 0) return
        player.life -= amount
        log("${player.name}は${amount}ダメージを受けた。（残り${player.life.coerceAtLeast(0)}）")
        if (player.life <= 0) {
            player.life = 0
            finish(state.opponentOf(player).index, "${player.name}のライフが0になった")
        }
    }

    fun recoverLife(player: PlayerState, amount: Int) {
        if (amount <= 0) return
        player.life += amount
        log("${player.name}はライフを${amount}回復した。（${player.life}）")
    }

    /** カードをドローする。デッキが尽きていたらそのプレイヤーの負け。 */
    fun draw(player: PlayerState, count: Int) {
        repeat(count) {
            if (state.finished) return
            if (player.deck.isEmpty()) {
                finish(state.opponentOf(player).index, "${player.name}のデッキが尽きた")
                return
            }
            player.hand.add(player.deck.removeAt(0))
        }
        log("${player.name}はカードを${count}枚ドローした。")
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

    private suspend fun applyAction(action: Action, controller: PlayerState) {
        if (state.finished) return
        when (action) {
            is DestroyAction -> {
                val targets = resolveTargets(action.scope, controller, "破壊するカードを選択")
                targets.forEach { destroy(it) }
                shuffleIfDeck(action.scope, controller)
            }

            is BanishAction -> {
                val targets = resolveTargets(action.scope, controller, "除外するカードを選択")
                targets.forEach {
                    log("「${it.card.name}」を除外した。")
                    banish(it)
                }
                shuffleIfDeck(action.scope, controller)
            }

            is ToHandAction -> {
                val targets = resolveTargets(action.scope, controller, "手札に加えるカードを選択")
                targets.forEach {
                    log("「${it.card.name}」を手札に加えた。")
                    returnToHand(it)
                }
                shuffleIfDeck(action.scope, controller)
            }

            is ToGraveAction -> {
                val targets = resolveTargets(action.scope, controller, "墓地へ送るカードを選択")
                targets.forEach {
                    log("「${it.card.name}」を墓地へ送った。")
                    sendToGraveyard(it)
                }
                shuffleIfDeck(action.scope, controller)
            }

            is ToDeckAction -> {
                val targets = resolveTargets(action.scope, controller, "デッキに戻すカードを選択")
                targets.forEach {
                    log("「${it.card.name}」をデッキに戻した。")
                    returnToDeck(it, action.toBottom)
                }
                shuffleIfDeck(action.scope, controller)
            }

            is SpecialSummonAction -> {
                val destination = primaryPlayer(action.controller, controller)
                val targets = resolveTargets(action.scope, controller, "特殊召喚するモンスターを選択")
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
                    triggerOnSummon(target, destination)
                }
                shuffleIfDeck(action.scope, controller)
            }

            is ModifyStatAction -> {
                val targets = resolveTargets(action.scope, controller, "効果の対象を選択")
                targets.forEach { target ->
                    when (action.stat) {
                        StatKind.ATK -> target.atkMod += action.delta
                        StatKind.DEF -> target.defMod += action.delta
                    }
                    log("「${target.card.name}」の${action.stat.label}が${action.delta}変化した。")
                }
            }

            is ChangePositionAction -> {
                val targets = resolveTargets(action.scope, controller, "表示形式を変えるカードを選択")
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

            NegateAction -> {
                // 実際の無効化は発動宣言時の応答処理で行うため、ここでは何もしない。
                log("発動を無効にした。")
            }
        }
    }

    // =======================================================================
    // 効果の発動
    // =======================================================================

    private fun defaultLocations(kind: CardKind): List<ActivationLocation> =
        listOf(ActivationLocation.FIELD)

    private fun locationOf(inst: CardInstance): ActivationLocation? =
        when (locate(inst)?.zone) {
            ZoneType.HAND -> ActivationLocation.HAND
            ZoneType.MONSTER_ZONE, ZoneType.SPELL_TRAP_ZONE -> ActivationLocation.FIELD
            ZoneType.GRAVEYARD -> ActivationLocation.GRAVEYARD
            else -> null
        }

    /**
     * いま発動できる効果番号の一覧。
     * 【場所】【条件】【コスト】は共通指定と番号ごとの指定を合成して判定する。
     */
    fun activatableClauses(inst: CardInstance, controller: PlayerState): List<Int> {
        val effect = inst.card.effect ?: return emptyList()
        val here = locationOf(inst) ?: return emptyList()

        return effect.clauses.indices.filter { index ->
            val clause = effect.clauses[index]
            if (clause.actions.isEmpty()) return@filter false
            if (clause.oncePerTurn && index in inst.usedClausesThisTurn) return@filter false

            val timingOk = when (inst.card.kind) {
                CardKind.MONSTER -> clause.timing == EffectTiming.IGNITION
                else -> clause.timing == EffectTiming.ON_ACTIVATE
            }
            if (!timingOk) return@filter false

            val locations = effect.locationsFor(index).ifEmpty { defaultLocations(inst.card.kind) }
            if (here !in locations) return@filter false

            conditionsMet(effect.conditionsFor(index), controller) &&
                canPayCosts(effect.costsFor(index), controller, excluding = inst)
        }
    }

    /** 手札・フィールドから、いまこのプレイヤーが発動できるカードを集める。 */
    fun activatableCards(controller: PlayerState): List<CardInstance> {
        val result = mutableListOf<CardInstance>()
        val isOwnTurn = state.turnPlayer === controller
        val inMainPhase = state.phase == Phase.MAIN1 || state.phase == Phase.MAIN2

        if (isOwnTurn && inMainPhase) {
            // 魔法カードは自分のターンのみ。手札からもセット済みからも発動できる。
            result += controller.hand.filter {
                it.card.kind == CardKind.SPELL && activatableClauses(it, controller).isNotEmpty()
            }
            result += controller.spellsAndTraps.filter {
                it.card.kind == CardKind.SPELL && activatableClauses(it, controller).isNotEmpty()
            }
            // モンスターの起動効果。
            result += controller.monsters.filter {
                !it.faceDown && activatableClauses(it, controller).isNotEmpty()
            }
        }

        // 罠は伏せた次のターン以降なら、どちらのターンでも発動できる。
        result += controller.spellsAndTraps.filter { canActivateTrap(it, controller) }

        return result.distinct()
    }

    fun canActivateTrap(inst: CardInstance, controller: PlayerState): Boolean =
        inst.card.kind == CardKind.TRAP &&
            inst.faceDown &&
            inst.setOnTurn in 0 until state.turn &&
            activatableClauses(inst, controller).isNotEmpty()

    /**
     * カードを発動する。効果が複数ある場合はどの番号を使うか選ばせる。
     */
    suspend fun activateCard(inst: CardInstance, controller: PlayerState): Boolean {
        val clauses = activatableClauses(inst, controller)
        if (clauses.isEmpty()) {
            interaction.notify(controller.index, "「${inst.card.name}」は今は発動できない。")
            return false
        }

        val clauseIndex = if (clauses.size == 1) {
            clauses.first()
        } else {
            val labels = clauses.map { "${EffectNumbers.circled(it)} の効果" }
            clauses[interaction.chooseOption(controller.index, "発動する効果を選択", labels)]
        }
        return activateClause(inst, clauseIndex, controller)
    }

    private suspend fun activateClause(
        inst: CardInstance,
        clauseIndex: Int,
        controller: PlayerState
    ): Boolean {
        val effect = inst.card.effect ?: return false
        val clause = effect.clauses.getOrNull(clauseIndex) ?: return false
        val wasInHand = locate(inst)?.zone == ZoneType.HAND
        val isSpellOrTrap = inst.card.kind != CardKind.MONSTER

        // 手札の魔法・罠は、発動時に魔法・罠ゾーンへ置く。
        if (isSpellOrTrap && wasInHand) {
            val zone = controller.freeSpellTrapZones().firstOrNull()
            if (zone == null) {
                interaction.notify(controller.index, "魔法・罠ゾーンに空きが無い。")
                return false
            }
            controller.hand.remove(inst)
            controller.spellTrapZones[zone] = inst
        }
        inst.faceDown = false

        log("${controller.name}は「${inst.card.name}」の${EffectNumbers.circled(clauseIndex)}を発動。")

        // コストは発動宣言時に支払う。
        if (!payCosts(effect.costsFor(clauseIndex), controller, excluding = inst)) {
            log("コストを支払えなかったため発動は不発になった。")
            if (isSpellOrTrap) sendToGraveyard(inst)
            return false
        }

        inst.usedClausesThisTurn.add(clauseIndex)

        // 相手に応答（罠）の機会を与える。
        val negated = offerResponse(controller, "「${inst.card.name}」の発動")
        if (negated) {
            log("「${inst.card.name}」の発動は無効になった。")
            if (isSpellOrTrap) sendToGraveyard(inst) else destroy(inst)
            return true
        }

        for (action in clause.actions) {
            if (state.finished) break
            applyAction(action, controller)
        }

        // 魔法・罠は解決後に墓地へ送る。
        if (isSpellOrTrap && isOnField(inst)) sendToGraveyard(inst)
        return true
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
    // 誘発効果
    // =======================================================================

    private suspend fun runTriggeredClauses(
        inst: CardInstance,
        controller: PlayerState,
        timing: EffectTiming
    ) {
        if (state.finished || triggerDepth >= 4) return
        val effect = inst.card.effect ?: return

        triggerDepth++
        try {
            effect.clauses.forEachIndexed { index, clause ->
                if (clause.timing != timing || clause.actions.isEmpty()) return@forEachIndexed
                if (clause.oncePerTurn && index in inst.usedClausesThisTurn) return@forEachIndexed
                if (!conditionsMet(effect.conditionsFor(index), controller)) return@forEachIndexed
                if (!canPayCosts(effect.costsFor(index), controller, excluding = inst)) {
                    return@forEachIndexed
                }

                val label = "「${inst.card.name}」の${EffectNumbers.circled(index)}を発動しますか？"
                if (!interaction.confirm(controller.index, label)) return@forEachIndexed

                if (!payCosts(effect.costsFor(index), controller, excluding = inst)) {
                    return@forEachIndexed
                }
                inst.usedClausesThisTurn.add(index)
                log("${controller.name}の「${inst.card.name}」の効果が発動。")
                clause.actions.forEach { action ->
                    if (!state.finished) applyAction(action, controller)
                }
            }
        } finally {
            triggerDepth--
        }
    }

    private suspend fun triggerOnSummon(inst: CardInstance, controller: PlayerState) =
        runTriggeredClauses(inst, controller, EffectTiming.ON_SUMMON)

    private suspend fun triggerOnDestroyed(inst: CardInstance, controller: PlayerState) =
        runTriggeredClauses(inst, controller, EffectTiming.ON_DESTROYED)

    private suspend fun triggerOnAttack(inst: CardInstance, controller: PlayerState) =
        runTriggeredClauses(inst, controller, EffectTiming.ON_ATTACK)

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
        triggerOnSummon(inst, controller)
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

        triggerOnAttack(attacker, attackingPlayer)
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
            dealDamage(defendingPlayer, attacker.atkValue)
            return
        }

        if (locate(target)?.zone != ZoneType.MONSTER_ZONE) return

        if (target.faceDown) {
            target.faceDown = false
            target.position = Position.DEFENSE
            log("「${target.card.name}」が反転した。")
        }

        val attack = attacker.atkValue
        if (target.position == Position.ATTACK) {
            val defenderAttack = target.atkValue
            when {
                attack > defenderAttack -> {
                    dealDamage(defendingPlayer, attack - defenderAttack)
                    destroy(target)
                }

                attack < defenderAttack -> {
                    dealDamage(attackingPlayer, defenderAttack - attack)
                    destroy(attacker)
                }

                else -> {
                    destroy(target)
                    destroy(attacker)
                }
            }
        } else {
            val defense = target.defValue
            when {
                attack > defense -> destroy(target)
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

    private fun startNextTurn() {
        if (state.finished) return
        state.turnPlayerIndex = 1 - state.turnPlayerIndex
        state.turn += 1

        state.players.forEach { player ->
            player.normalSummonUsed = false
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

object EffectNumbers {
    private val CIRCLED = listOf("①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩")
    fun circled(index: Int): String = CIRCLED.getOrElse(index) { "(${index + 1})" }
}
