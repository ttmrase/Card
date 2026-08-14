package com.cardforge.game

import com.cardforge.model.CardKind
import com.cardforge.model.Position

/** [inst] を持っているプレイヤーの番号。どこにも無ければ null。 */
fun GameState.ownerIndexOf(inst: CardInstance): Int? {
    for (player in players) {
        val found = player.monsterZones.any { it === inst } ||
            player.spellTrapZones.any { it === inst } ||
            player.hand.any { it === inst } ||
            player.deck.any { it === inst } ||
            player.graveyard.any { it === inst } ||
            player.banished.any { it === inst }
        if (found) return player.index
    }
    return null
}

/**
 * AI 側の選択を自動で行う [Interaction]。
 *
 * 「自分のカードなら弱いもの、相手のカードなら強いもの」を選ぶ、という
 * 単純だが大抵の効果で妥当に働くヒューリスティックで動く。
 */
class AiInteraction(
    private val state: GameState,
    private val aiIndex: Int
) : Interaction {

    override suspend fun chooseCards(
        playerIndex: Int,
        prompt: String,
        candidates: List<CardInstance>,
        min: Int,
        max: Int
    ): List<CardInstance> {
        if (candidates.isEmpty()) return emptyList()

        val ranked = candidates.sortedWith(
            compareByDescending { card ->
                val ownedByAi = state.ownerIndexOf(card) == aiIndex
                val power = maxOf(card.atkValue, card.defValue)
                // 相手のカードは強いものから、自分のカードは弱いものから選ぶ。
                if (ownedByAi) -power else power + 100_000
            }
        )
        val take = if (min > 0) min else 1
        return ranked.take(take.coerceAtMost(max.coerceAtLeast(1)))
    }

    override suspend fun chooseZone(
        playerIndex: Int,
        prompt: String,
        freeZones: List<Int>
    ): Int? = freeZones.firstOrNull()

    override suspend fun confirm(playerIndex: Int, prompt: String): Boolean = true

    override suspend fun chooseOption(
        playerIndex: Int,
        prompt: String,
        options: List<String>
    ): Int = 0
}

/**
 * 対戦相手の思考ルーチン。1ターン分の行動をまとめて実行する。
 */
class AiController(
    private val engine: GameEngine,
    private val aiIndex: Int
) {

    private val state get() = engine.state
    private val me get() = state.players[aiIndex]

    suspend fun playTurn() {
        if (state.finished || state.turnPlayerIndex != aiIndex) return

        // メインフェイズ1。
        if (state.phase == Phase.DRAW) engine.advancePhase()
        playMainPhase()
        if (state.finished) return

        // バトルフェイズ。
        if (state.turn > 1) {
            while (state.phase != Phase.BATTLE && !state.finished) {
                if (state.phase == Phase.MAIN2 || state.phase == Phase.END) break
                engine.advancePhase()
            }
            if (state.phase == Phase.BATTLE) playBattlePhase()
        }
        if (state.finished) return

        // 残りの伏せカードを置いてターンを終える。
        if (state.phase == Phase.BATTLE) engine.advancePhase()
        setSpellsAndTraps()
        if (!state.finished) engine.endTurnImmediately()
    }

    private suspend fun playMainPhase() {
        // 発動できる魔法・モンスター効果を順に使う（無限ループを避けるため回数を制限）。
        repeat(4) {
            if (state.finished) return
            val playable = engine.activatableCards(me)
                .filter { it.card.kind != CardKind.TRAP }
                .sortedByDescending { it.card.kind == CardKind.SPELL }
            val next = playable.firstOrNull() ?: return@repeat
            engine.activateCard(next, me)
        }
        if (state.finished) return

        summonBestMonster()
    }

    private suspend fun summonBestMonster() {
        val summonable = me.hand
            .filter { it.card.kind == CardKind.MONSTER && engine.canNormalSummon(it, me) }
            .sortedByDescending { it.card.atk }

        val best = summonable.firstOrNull() ?: return

        // リリースが必要な場合、盤面が薄くなりすぎるなら下級を優先する。
        val need = best.card.tributesRequired
        val choice = if (need > 0 && me.monsters.size <= need) {
            summonable.firstOrNull { it.card.tributesRequired == 0 } ?: best
        } else {
            best
        }

        // 攻撃力より守備力が高く、かつ攻撃力が低いモンスターはセットする。
        val shouldSet = choice.card.atk < 1500 && choice.card.def > choice.card.atk
        engine.normalSummon(choice, me, asSet = shouldSet)
    }

    private suspend fun playBattlePhase() {
        // 攻撃力の高い順に攻撃させる。盤面は攻撃ごとに変わるので毎回取り直す。
        var guard = 0
        while (!state.finished && guard++ < DeckRules_ZONE_LIMIT) {
            val attacker = me.monsters
                .filter { engine.canAttack(it) }
                .maxByOrNull { it.atkValue } ?: break

            val defenders = engine.attackTargets()
            if (defenders.isEmpty()) {
                engine.declareAttack(attacker, null)
                continue
            }

            val target = pickTarget(attacker, defenders)
            if (target == null) {
                // 勝てる相手がいないので、この打点での攻撃は見送る。
                attacker.hasAttacked = true
                continue
            }
            engine.declareAttack(attacker, target)
        }
    }

    /** 戦闘で損をしない相手を選ぶ。無ければ null。 */
    private fun pickTarget(
        attacker: CardInstance,
        defenders: List<CardInstance>
    ): CardInstance? {
        val attack = attacker.atkValue

        // 裏側のモンスターは中身が読めないので、そこそこの打点なら殴ってみる。
        val faceDown = defenders.filter { it.faceDown }
        val faceUp = defenders.filter { !it.faceDown }

        val beatable = faceUp.filter { defender ->
            if (defender.position == Position.ATTACK) attack > defender.atkValue
            else attack > defender.defValue
        }
        // 攻撃表示の相手を優先し、その中で一番打点が高いものを狙う。
        val best = beatable
            .sortedWith(
                compareByDescending<CardInstance> { it.position == Position.ATTACK }
                    .thenByDescending { it.atkValue }
            )
            .firstOrNull()
        if (best != null) return best

        if (faceDown.isNotEmpty() && attack >= 1800) return faceDown.first()
        return null
    }

    private fun setSpellsAndTraps() {
        val settable = me.hand.filter { it.card.kind != CardKind.MONSTER }
        for (card in settable) {
            if (!engine.canSetSpellTrap(card, me)) continue
            engine.setSpellTrap(card, me)
        }
    }

    private companion object {
        /** 攻撃の while ループが暴走しないための上限。 */
        const val DeckRules_ZONE_LIMIT = 10
    }
}
