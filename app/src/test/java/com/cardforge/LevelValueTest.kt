package com.cardforge

import com.cardforge.game.*
import com.cardforge.model.*
import com.cardforge.text.EffectTextRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class LevelPicker : Interaction {
    override suspend fun chooseCards(
        playerIndex: Int, prompt: String,
        candidates: List<CardInstance>, min: Int, max: Int
    ): List<CardInstance> = candidates.take(max.coerceAtLeast(min).coerceAtLeast(1))

    override suspend fun chooseZone(playerIndex: Int, prompt: String, freeZones: List<Int>) =
        freeZones.firstOrNull()

    override suspend fun confirm(playerIndex: Int, prompt: String) = true
    override suspend fun chooseOption(playerIndex: Int, prompt: String, options: List<String>) = 0
}

/** 召喚・破壊などをしたモンスターのレベルを、数として使えることを確かめる。 */
class LevelValueTest {

    private val master = MasterData()

    private fun game(): Pair<GameState, GameEngine> {
        val state = GameState(listOf(PlayerState(0, "A"), PlayerState(1, "B")), master)
        state.turnPlayerIndex = 0
        state.turn = 3
        state.phase = Phase.MAIN1
        return state to GameEngine(state, LevelPicker())
    }

    private fun monster(name: String, level: Int, effect: EffectText? = null) = CardInstance(
        newId(),
        CardDef(
            id = newId(), name = name, kind = CardKind.MONSTER,
            level = level, atk = 1000, def = 1000, effect = effect
        )
    )

    private fun spell(clause: EffectClause) = CardInstance(
        newId(),
        CardDef(
            id = newId(), name = "呪文", kind = CardKind.SPELL,
            effect = EffectText(locations = listOf(ActivationLocation.HAND), clauses = listOf(clause))
        )
    )

    @Test
    fun `counters follow the level of the summoned monster`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        // 自分がモンスターを召喚するたび、そのレベルの数だけこのカードにカウンターを乗せる。
        val altar = monster(
            "祭壇", 1,
            EffectText(
                clauses = listOf(
                    EffectClause(
                        conditions = listOf(
                            EventCondition(event = GameEventType.NORMAL_SUMMONED, who = PlayerRef.SELF)
                        ),
                        mode = ActivationMode.MANDATORY,
                        actions = listOf(
                            AddCounterAction(
                                CardScope(selfOnly = true),
                                amountValue = LevelValue(LevelSource.EVENT_CARD)
                            )
                        )
                    )
                )
            )
        )
        me.monsterZones[0] = altar
        val four = monster("四つ星", 4)
        me.hand.add(four)

        engine.normalSummon(four, me, asSet = false)

        assertEquals(4, altar.counterCount(null))
    }

    @Test
    fun `damage can use the level of the destroyed monster`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        val opponent = state.players[1]
        opponent.monsterZones[0] = monster("七つ星", 7)
        val card = spell(
            EffectClause(
                actions = listOf(
                    DestroyAction(CardScope()),
                    DamageAction(
                        PlayerRef.OPPONENT,
                        amountValue = LevelValue(LevelSource.LAST_HANDLED, multiplier = 100)
                    )
                )
            )
        )
        me.hand.add(card)

        engine.activateCard(card, me)

        assertEquals(8000 - 700, opponent.life)
    }

    @Test
    fun `a tribute cost can depend on this card's level`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        // 「このカードのレベルの数だけリリースして、手札から特殊召喚する」。
        val lord = monster(
            "君主", 2,
            EffectText(
                locations = listOf(ActivationLocation.HAND),
                clauses = listOf(
                    EffectClause(
                        costs = listOf(TributeCost(1, countValue = LevelValue(LevelSource.SELF))),
                        actions = listOf(SpecialSummonAction(CardScope(selfOnly = true)))
                    )
                )
            )
        )
        me.hand.add(lord)
        me.monsterZones[0] = monster("兵A", 1)

        assertTrue("1体しかいないのでリリースが足りない", engine.activatableClauses(lord, me).isEmpty())

        me.monsterZones[1] = monster("兵B", 1)
        engine.activateCard(lord, me)

        assertEquals(listOf("君主"), me.monsters.map { it.card.name })
        assertEquals(2, me.graveyard.size)
    }

    @Test
    fun `counter costs and life costs can use levels`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        // 「このカードのレベル×100 のライフを払い、レベルの数だけカウンターを取り除く」。
        val sage = monster(
            "賢者", 3,
            EffectText(
                clauses = listOf(
                    EffectClause(
                        costs = listOf(
                            PayLifeCost(0, amountValue = LevelValue(LevelSource.SELF, multiplier = 100)),
                            CounterCost(amountValue = LevelValue(LevelSource.SELF))
                        ),
                        actions = listOf(DrawAction(PlayerRef.SELF, 1))
                    )
                )
            )
        )
        me.monsterZones[0] = sage
        me.deck.add(monster("山札", 1))
        sage.counters["counter"] = 2

        assertTrue("カウンターが2個では足りない", engine.activatableClauses(sage, me).isEmpty())

        sage.counters["counter"] = 5
        engine.activateCard(sage, me)

        assertEquals(8000 - 300, me.life)
        assertEquals(2, sage.counterCount(null))
        assertEquals(1, me.hand.size)
    }

    @Test
    fun `level values read plainly`() {
        assertEquals(
            "その出来事の対象になったモンスターのレベル",
            EffectTextRenderer.valueToText(LevelValue(LevelSource.EVENT_CARD), master)
        )
        assertEquals(
            "直前の処理で扱ったモンスターのレベルの合計×100",
            EffectTextRenderer.valueToText(LevelValue(LevelSource.LAST_HANDLED, multiplier = 100), master)
        )
        assertEquals(
            "自分フィールドのモンスターをこのカードのレベルだけリリースする",
            EffectTextRenderer.costToText(TributeCost(1, countValue = LevelValue(LevelSource.SELF)), master)
        )
    }
}
