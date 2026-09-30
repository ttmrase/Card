package com.cardforge

import com.cardforge.game.*
import com.cardforge.model.*
import com.cardforge.text.EffectTextRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class UniquePicker : Interaction {
    override suspend fun chooseCards(
        playerIndex: Int, prompt: String,
        candidates: List<CardInstance>, min: Int, max: Int
    ): List<CardInstance> = candidates.take(max.coerceAtLeast(min).coerceAtLeast(1))

    override suspend fun chooseZone(playerIndex: Int, prompt: String, freeZones: List<Int>) =
        freeZones.firstOrNull()

    override suspend fun confirm(playerIndex: Int, prompt: String) = true
    override suspend fun chooseOption(playerIndex: Int, prompt: String, options: List<String>) = 0
}

/** 「フィールドに1枚しか存在できない」と、コストが払えない理由の説明。 */
class UniqueAndCostReasonTest {

    private val counterId = "k"
    private val master = MasterData(counters = listOf(NamedEntry(counterId, "神邑カウンター")))

    private fun game(): Pair<GameState, GameEngine> {
        val state = GameState(listOf(PlayerState(0, "A"), PlayerState(1, "B")), master)
        state.turnPlayerIndex = 0
        state.turn = 3
        state.phase = Phase.MAIN1
        return state to GameEngine(state, UniquePicker())
    }

    private val lordDef = CardDef(
        id = "lord", name = "唯一の王", kind = CardKind.MONSTER, level = 4,
        atk = 1000, def = 1000, uniqueOnField = UniqueScope.WHOLE_FIELD
    )

    @Test
    fun `a unique monster cannot be summoned while one is face-up`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        val opponent = state.players[1]
        val first = CardInstance(newId(), lordDef)
        val second = CardInstance(newId(), lordDef)
        me.hand.add(second)

        opponent.monsterZones[0] = first
        assertFalse("相手のフィールドにも同名がいる", engine.canNormalSummon(second, me))
        assertTrue("裏側守備のセットはできる", engine.canNormalSummon(second, me, asSet = true))
        assertFalse(engine.canBeSpecialSummoned(second, me, null))

        first.faceDown = true
        assertTrue("裏側なら数えない", engine.canNormalSummon(second, me))
    }

    @Test
    fun `own-field uniqueness ignores the opponent's copy`() {
        val (state, engine) = game()
        val me = state.players[0]
        val def = lordDef.copy(uniqueOnField = UniqueScope.OWN_FIELD)
        state.players[1].monsterZones[0] = CardInstance(newId(), def)
        val mine = CardInstance(newId(), def)
        me.hand.add(mine)
        assertTrue(engine.canNormalSummon(mine, me))
        me.monsterZones[0] = CardInstance(newId(), def)
        assertFalse(engine.canNormalSummon(mine, me))
    }

    @Test
    fun `a unique spell cannot be activated while one is face-up`() {
        val (state, engine) = game()
        val me = state.players[0]
        val def = CardDef(
            id = "s", name = "唯一の結界", kind = CardKind.SPELL,
            uniqueOnField = UniqueScope.OWN_FIELD,
            effect = EffectText(clauses = listOf(EffectClause(actions = listOf(DrawAction(PlayerRef.SELF, 1)))))
        )
        me.deck.add(CardInstance(newId(), lordDef))
        val inHand = CardInstance(newId(), def)
        me.hand.add(inHand)
        assertTrue(engine.activatableCards(me).any { it === inHand })

        me.spellTrapZones[0] = CardInstance(newId(), def)
        assertFalse(engine.activatableCards(me).any { it === inHand })
        assertTrue(engine.whyCannotActivate(inHand, me)!!.contains("1枚しか"))
        assertTrue(
            EffectTextRenderer.render(def, master).contains("自分フィールドに1枚しか表側表示で存在できない")
        )
    }

    @Test
    fun `the reason names counters of a different kind`() {
        val (state, engine) = game()
        val me = state.players[0]
        val shrine = CardInstance(
            newId(),
            CardDef(
                id = "shrine", name = "社", kind = CardKind.MONSTER, level = 1,
                effect = EffectText(
                    clauses = listOf(
                        EffectClause(
                            costs = listOf(
                                CounterCost(
                                    CardScope(selfOnly = true), counterId,
                                    amountValue = LevelValue(LevelSource.SUMMON_TARGET)
                                )
                            ),
                            actions = listOf(
                                SpecialSummonAction(
                                    CardScope(who = PlayerRef.SELF, zone = ZoneType.HAND, count = 1)
                                )
                            )
                        )
                    )
                )
            )
        )
        me.monsterZones[0] = shrine
        me.hand.add(CardInstance(newId(), lordDef.copy(id = "five", name = "五つ星", level = 5, uniqueOnField = null)))
        // 種類を指定せずに乗せたカウンター（「カウンター」）が5個。
        shrine.counters["counter"] = 5

        val reason = engine.whyCannotActivate(shrine, me)!!
        assertTrue(reason, reason.contains("神邑カウンターが0個しかない（5個必要）"))
        assertTrue(reason, reason.contains("別の種類：カウンター×5"))
    }
}
