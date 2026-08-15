package com.cardforge

import com.cardforge.game.*
import com.cardforge.model.*
import com.cardforge.text.EffectTextRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 選択肢のうち [pick] 番目を選ぶ操作者。 */
private class Picker(private val pick: Int = 0) : Interaction {
    val optionPrompts = mutableListOf<String>()

    override suspend fun chooseCards(
        playerIndex: Int, prompt: String,
        candidates: List<CardInstance>, min: Int, max: Int
    ): List<CardInstance> = candidates.take(maxOf(min, 1).coerceAtMost(maxOf(max, 1)))

    override suspend fun chooseZone(playerIndex: Int, prompt: String, freeZones: List<Int>) =
        freeZones.firstOrNull()

    override suspend fun confirm(playerIndex: Int, prompt: String) = true

    override suspend fun chooseOption(
        playerIndex: Int, prompt: String, options: List<String>
    ): Int {
        optionPrompts += prompt
        return pick.coerceIn(options.indices)
    }
}

class SummonAndLimitScopeTest {

    private fun game(interaction: Interaction): Pair<GameState, GameEngine> {
        val state = GameState(listOf(PlayerState(0, "A"), PlayerState(1, "B")))
        state.turnPlayerIndex = 0
        state.turn = 2
        state.phase = Phase.MAIN1
        return state to GameEngine(state, interaction)
    }

    private fun monster(name: String) = CardInstance(
        newId(),
        CardDef(id = newId(), name = name, kind = CardKind.MONSTER, atk = 1000, def = 1500)
    )

    private fun summonSpell(vararg positions: Position) = CardInstance(
        newId(),
        CardDef(
            id = newId(), name = "呼び声", kind = CardKind.SPELL,
            effect = EffectText(
                locations = listOf(ActivationLocation.HAND),
                clauses = listOf(
                    EffectClause(
                        actions = listOf(
                            SpecialSummonAction(
                                scope = CardScope(
                                    who = PlayerRef.SELF,
                                    zone = ZoneType.GRAVEYARD,
                                    filters = listOf(KindFilter(CardKind.MONSTER)),
                                    count = 1
                                ),
                                positionChoices = positions.toList()
                            )
                        )
                    )
                )
            )
        )
    )

    // --- 特殊召喚の表示形式 ------------------------------------------------

    @Test
    fun `a single position is used without asking`() = runBlocking {
        val picker = Picker()
        val (state, engine) = game(picker)
        val me = state.players[0]
        val target = monster("眠る者")
        me.graveyard.add(target)

        val card = summonSpell(Position.DEFENSE)
        me.hand.add(card)
        engine.activateCard(card, me)

        assertEquals(Position.DEFENSE, target.displayPosition)
        assertTrue("聞かれない", picker.optionPrompts.none { it.contains("表示形式") })
    }

    @Test
    fun `several positions are offered when the effect resolves`() = runBlocking {
        val picker = Picker(pick = 1)
        val (state, engine) = game(picker)
        val me = state.players[0]
        val target = monster("眠る者")
        me.graveyard.add(target)

        val card = summonSpell(Position.ATTACK, Position.DEFENSE)
        me.hand.add(card)
        engine.activateCard(card, me)

        assertTrue("表示形式を聞かれる", picker.optionPrompts.any { it.contains("表示形式") })
        assertEquals("2番目を選んだ", Position.DEFENSE, target.displayPosition)
    }

    @Test
    fun `the choices are written into the card text`() {
        val action = SpecialSummonAction(
            scope = CardScope(who = PlayerRef.SELF, zone = ZoneType.GRAVEYARD, count = 1),
            positionChoices = listOf(Position.ATTACK, Position.DEFENSE)
        )
        assertTrue(
            EffectTextRenderer.actionToText(action, MasterData())
                .contains("表側攻撃表示または表側守備表示で特殊召喚する")
        )
    }

    @Test
    fun `an old fixed position still works`() {
        val action = SpecialSummonAction(
            scope = CardScope(),
            position = Position.FACE_DOWN_DEFENSE
        )
        assertEquals(listOf(Position.FACE_DOWN_DEFENSE), action.choices)
    }

    // --- 制限の適用範囲 ----------------------------------------------------

    private fun twoEffectCard(limit: UsageLimit) = CardInstance(
        newId(),
        CardDef(
            id = newId(), name = "二つ持ち", kind = CardKind.SPELL,
            effect = EffectText(
                afterActivation = AfterActivation.STAY_ON_FIELD,
                limits = listOf(limit),
                clauses = listOf(
                    EffectClause(actions = listOf(RecoverAction(PlayerRef.SELF, 100))),
                    EffectClause(actions = listOf(RecoverAction(PlayerRef.SELF, 200))),
                    EffectClause(actions = listOf(RecoverAction(PlayerRef.SELF, 400)))
                )
            )
        )
    )

    @Test
    fun `counted together the whole card is limited`() = runBlocking {
        val (state, engine) = game(Picker())
        val me = state.players[0]
        val card = twoEffectCard(UsageLimit(LimitScope.THIS_CARD, 1))
        me.hand.add(card)

        assertEquals(listOf(0, 1, 2), engine.activatableClauses(card, me))
        engine.activateCard(card, me)
        // どれか1つを使ったら、他の効果も締め切られる。
        assertTrue(engine.activatableClauses(card, me).isEmpty())
    }

    @Test
    fun `counted each way every effect keeps its own allowance`() = runBlocking {
        val (state, engine) = game(Picker())
        val me = state.players[0]
        val card = twoEffectCard(
            UsageLimit(LimitScope.THIS_CARD, 1, applies = LimitApplies.EACH)
        )
        me.hand.add(card)

        engine.activateCard(card, me)
        // ①を使っても②③はまだ使える。
        assertEquals(listOf(1, 2), engine.activatableClauses(card, me))
        engine.activateCard(card, me)
        assertEquals(listOf(2), engine.activatableClauses(card, me))
    }

    @Test
    fun `a limit can name which effects it covers`() = runBlocking {
        val (state, engine) = game(Picker())
        val me = state.players[0]
        // ②③のうちいずれか1つだけ。①は自由。
        val card = twoEffectCard(
            UsageLimit(LimitScope.THIS_CARD, 1, clauseIndices = listOf(1, 2))
        )
        me.hand.add(card)

        assertEquals(listOf(0, 1, 2), engine.activatableClauses(card, me))

        // ②を使うと③も締め切られるが、①は残る。
        me.activationsThisTurn.clear()
        engine.activateClauseForTest(card, 1, me)
        assertEquals(listOf(0), engine.activatableClauses(card, me))
    }

    @Test
    fun `scoped limits are written with the effect numbers`() {
        val master = MasterData()
        assertEquals(
            "それぞれの効果は同名カードを含めて1ターンに1度しか発動できない",
            EffectTextRenderer.limitToText(
                UsageLimit(LimitScope.SAME_NAME, 1, applies = LimitApplies.EACH),
                master, cardWide = true
            )
        )
        assertEquals(
            "②③は合わせて同名カードを含めて1ターンに1度しか発動できない",
            EffectTextRenderer.limitToText(
                UsageLimit(LimitScope.SAME_NAME, 1, clauseIndices = listOf(1, 2)),
                master, cardWide = true
            )
        )
        assertEquals(
            "①②はそれぞれ1ターンに1度しか発動できない",
            EffectTextRenderer.limitToText(
                UsageLimit(
                    LimitScope.THIS_CARD, 1,
                    clauseIndices = listOf(0, 1), applies = LimitApplies.EACH
                ),
                master, cardWide = true
            )
        )
    }

    @Test
    fun `an unscoped limit keeps its old wording`() {
        assertEquals(
            "このカードは1ターンに1度しか発動できない",
            EffectTextRenderer.limitToText(
                UsageLimit(LimitScope.THIS_CARD, 1), MasterData(), cardWide = true
            )
        )
        assertFalse(
            EffectTextRenderer.limitToText(
                UsageLimit(LimitScope.SAME_NAME, 1), MasterData(), cardWide = true
            ).startsWith("このカード")
        )
    }
}
