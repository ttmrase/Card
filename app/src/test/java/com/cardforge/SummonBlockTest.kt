package com.cardforge

import com.cardforge.game.*
import com.cardforge.model.*
import com.cardforge.text.EffectTextRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class TakeMax : Interaction {
    override suspend fun chooseCards(
        playerIndex: Int, prompt: String,
        candidates: List<CardInstance>, min: Int, max: Int
    ): List<CardInstance> = candidates.take(max.coerceAtLeast(min).coerceAtLeast(1))

    override suspend fun chooseZone(playerIndex: Int, prompt: String, freeZones: List<Int>) =
        freeZones.firstOrNull()

    override suspend fun confirm(playerIndex: Int, prompt: String) = true
    override suspend fun chooseOption(playerIndex: Int, prompt: String, options: List<String>) = 0
}

/** 特殊召喚できない、原因のカード指定、割り込み封じ、枚数参照の召喚。 */
class SummonBlockTest {

    private val valis = "cat-valis"
    private val master = MasterData(categories = listOf(NamedEntry(valis, "VALIS")))

    private val tokenDef = CardDef(
        id = "token-1", name = "トークン", kind = CardKind.MONSTER,
        level = 1, atk = 500, def = 500, isToken = true
    )

    private fun game(turnPlayer: Int = 0): Pair<GameState, GameEngine> {
        val state = GameState(listOf(PlayerState(0, "A"), PlayerState(1, "B")), master)
        state.tokenDefs = listOf(tokenDef)
        state.turnPlayerIndex = turnPlayer
        state.turn = 3
        state.phase = Phase.MAIN1
        return state to GameEngine(state, TakeMax())
    }

    private fun monster(name: String, category: String? = null) = CardInstance(
        newId(),
        CardDef(
            id = newId(), name = name, kind = CardKind.MONSTER,
            level = 4, atk = 1000, def = 1000,
            categoryIds = listOfNotNull(category)
        )
    )

    private fun spellWith(
        name: String,
        clause: EffectClause,
        category: String? = null
    ) = CardInstance(
        newId(),
        CardDef(
            id = newId(), name = name, kind = CardKind.SPELL,
            categoryIds = listOfNotNull(category),
            effect = EffectText(
                locations = listOf(ActivationLocation.HAND),
                clauses = listOf(clause)
            )
        )
    )

    // -- 特殊召喚できない --------------------------------------------------

    @Test
    fun `a card marked cannot-special-summon is refused`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        val locked = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "出せない者", kind = CardKind.MONSTER,
                level = 4, atk = 1000, def = 1000,
                cannotSpecialSummon = true
            )
        )
        me.deck.add(locked)

        val card = spellWith(
            "呼ぶ魔法",
            EffectClause(
                actions = listOf(
                    SpecialSummonAction(
                        CardScope(
                            who = PlayerRef.SELF,
                            zone = ZoneType.DECK,
                            filters = listOf(KindFilter(CardKind.MONSTER)),
                            count = 1
                        )
                    )
                )
            )
        )
        me.hand.add(card)
        engine.activateCard(card, me)

        assertTrue("特殊召喚できないので場に出ない", me.monsters.isEmpty())
        assertTrue(me.deck.any { it === locked })
    }

    // -- 原因のカードを名指しする -----------------------------------------

    @Test
    fun `a category can be named as the cause`() = runBlocking {
        val (state, engine) = game(turnPlayer = 1)
        val me = state.players[0]
        val opponent = state.players[1]

        // 「VALIS」カードの効果で破壊されたときだけ反応する。
        val watched = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "見張り", kind = CardKind.MONSTER,
                level = 4, atk = 1000, def = 1000,
                effect = EffectText(
                    locations = listOf(ActivationLocation.GRAVEYARD),
                    clauses = listOf(
                        EffectClause(
                            conditions = listOf(
                                EventCondition(
                                    event = GameEventType.DESTROYED,
                                    selfOnly = true,
                                    sourceFilters = listOf(CategoryFilter(valis))
                                )
                            ),
                            mode = ActivationMode.MANDATORY,
                            actions = listOf(RecoverAction(PlayerRef.SELF, 800))
                        )
                    )
                )
            )
        )

        fun removal(name: String, category: String?) = spellWith(
            name,
            EffectClause(
                actions = listOf(
                    DestroyAction(
                        CardScope(
                            who = PlayerRef.OPPONENT,
                            zone = ZoneType.MONSTER_ZONE,
                            count = 1
                        )
                    )
                )
            ),
            category = category
        )

        me.monsterZones[0] = watched
        val plain = removal("ただの魔法", null)
        opponent.hand.add(plain)
        engine.activateCard(plain, opponent)
        assertEquals("「VALIS」ではないので誘発しない", 8000, me.life)

        val watched2 = CardInstance(newId(), watched.card)
        me.monsterZones[0] = watched2
        val valisSpell = removal("VALISの魔法", valis)
        opponent.hand.add(valisSpell)
        engine.activateCard(valisSpell, opponent)
        assertEquals("「VALIS」カードの効果なら誘発する", 8800, me.life)
    }

    @Test
    fun `the cause card is written into the text`() {
        val condition = EventCondition(
            event = GameEventType.DESTROYED,
            selfOnly = true,
            sourceFilters = listOf(CategoryFilter(valis), KindFilter(CardKind.MONSTER))
        )
        assertEquals(
            "このカードが「VALIS」モンスターによって破壊された場合",
            EffectTextRenderer.eventConditionToText(condition, master)
        )
    }

    // -- 発動に対して効果を発動できない -----------------------------------

    @Test
    fun `a no-response activation cannot be answered`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        val opponent = state.players[1]

        val counter = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "無効にする罠", kind = CardKind.TRAP,
                effect = EffectText(
                    locations = listOf(ActivationLocation.FIELD),
                    clauses = listOf(EffectClause(actions = listOf(NegateAction)))
                )
            )
        )
        opponent.spellTrapZones[0] = counter
        counter.faceDown = true
        counter.setOnTurn = state.turn - 1

        val card = spellWith(
            "止められない魔法",
            EffectClause(
                noResponseFrom = PlayerRef.OPPONENT,
                actions = listOf(RecoverAction(PlayerRef.SELF, 500))
            )
        )
        me.hand.add(card)
        engine.activateCard(card, me)

        assertEquals("相手は割り込めないので通る", 8500, me.life)
        assertTrue("罠は伏せたまま", counter.faceDown)
    }

    @Test
    fun `a normal activation can still be answered`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        val opponent = state.players[1]

        val counter = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "無効にする罠", kind = CardKind.TRAP,
                effect = EffectText(
                    locations = listOf(ActivationLocation.FIELD),
                    clauses = listOf(EffectClause(actions = listOf(NegateAction)))
                )
            )
        )
        opponent.spellTrapZones[0] = counter
        counter.faceDown = true
        counter.setOnTurn = state.turn - 1

        val card = spellWith(
            "普通の魔法",
            EffectClause(actions = listOf(RecoverAction(PlayerRef.SELF, 500)))
        )
        me.hand.add(card)
        engine.activateCard(card, me)

        assertEquals("無効にされる", 8000, me.life)
    }

    @Test
    fun `the no-response limit is written in the limit section`() {
        val card = CardDef(
            id = "x", name = "止められない魔法", kind = CardKind.SPELL,
            effect = EffectText(
                noResponseFrom = PlayerRef.OPPONENT,
                clauses = listOf(
                    EffectClause(actions = listOf(RecoverAction(PlayerRef.SELF, 500)))
                )
            )
        )
        val text = EffectTextRenderer.render(card, master)
        assertTrue(
            text,
            text.lines().any {
                it == "【制限】この効果の発動に対して相手はカードの効果を発動できない"
            }
        )
    }

    // -- 枚数を参照した召喚 ------------------------------------------------

    @Test
    fun `tokens can follow the count of the previous step`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        val opponent = state.players[1]

        opponent.monsterZones[0] = monster("的A")
        opponent.monsterZones[1] = monster("的B")

        val card = spellWith(
            "破壊した数だけトークン",
            EffectClause(
                actions = listOf(
                    DestroyAction(
                        CardScope(
                            who = PlayerRef.OPPONENT,
                            zone = ZoneType.MONSTER_ZONE,
                            selection = SelectionMode.ALL
                        )
                    ),
                    CreateTokenAction("token-1", countValue = AffectedCountValue())
                )
            )
        )
        me.hand.add(card)
        engine.activateCard(card, me)

        assertEquals("2体破壊した", 0, opponent.monsters.size)
        assertEquals("その数だけトークンが出る", 2, me.monsters.size)
        assertTrue(me.monsters.all { it.card.isToken })
    }

    @Test
    fun `the token count reads from the previous step`() {
        val action = CreateTokenAction("token-1", countValue = AffectedCountValue())
        assertEquals(
            "自分のモンスターゾーンにトークンを直前の処理で扱ったカードの数だけ、表側攻撃表示で特殊召喚する",
            EffectTextRenderer.actionToText(action, master)
        )
    }
}
