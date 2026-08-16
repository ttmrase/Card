package com.cardforge

import com.cardforge.game.*
import com.cardforge.model.*
import com.cardforge.text.EffectTextRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class AutoAnswer : Interaction {
    override suspend fun chooseCards(
        playerIndex: Int, prompt: String,
        candidates: List<CardInstance>, min: Int, max: Int
    ): List<CardInstance> = candidates.take(maxOf(min, 1).coerceAtMost(maxOf(max, 1)))

    override suspend fun chooseZone(playerIndex: Int, prompt: String, freeZones: List<Int>) =
        freeZones.firstOrNull()

    override suspend fun confirm(playerIndex: Int, prompt: String) = true
    override suspend fun chooseOption(playerIndex: Int, prompt: String, options: List<String>) = 0
}

/** 「または」「〜したターン」「対象になった」「特殊召喚したカード」「フェイズ操作」。 */
class ConditionsAndPhaseTest {

    private val master = MasterData()

    private fun game(turnPlayer: Int = 0): Pair<GameState, GameEngine> {
        val state = GameState(listOf(PlayerState(0, "A"), PlayerState(1, "B")), master)
        state.turnPlayerIndex = turnPlayer
        state.turn = 3
        state.phase = Phase.MAIN1
        return state to GameEngine(state, AutoAnswer())
    }

    private fun monster(name: String, atk: Int = 1000) = CardInstance(
        newId(),
        CardDef(
            id = newId(), name = name, kind = CardKind.MONSTER,
            level = 4, atk = atk, def = 1000
        )
    )

    private fun spellWith(name: String, clause: EffectClause) = CardInstance(
        newId(),
        CardDef(
            id = newId(), name = name, kind = CardKind.SPELL,
            effect = EffectText(
                locations = listOf(ActivationLocation.HAND),
                clauses = listOf(clause)
            )
        )
    )

    // -- または ------------------------------------------------------------

    private fun eitherCard() = spellWith(
        "どちらかで足りる",
        EffectClause(
            conditions = listOf(
                AnyOfCondition(
                    listOf(
                        LifeCondition(PlayerRef.SELF, Cmp.LE, 2000),
                        ZoneCountCondition(PlayerRef.SELF, ZoneType.GRAVEYARD, Cmp.GE, 3)
                    )
                )
            ),
            actions = listOf(DrawAction(PlayerRef.SELF, 1))
        )
    )

    @Test
    fun `either side of an or-condition is enough`() {
        val (state, engine) = game()
        val me = state.players[0]
        me.deck.add(monster("山札"))

        val card = eitherCard()
        me.hand.add(card)
        assertFalse("どちらも満たしていない", engine.activatableCards(me).any { it === card })

        repeat(3) { me.graveyard.add(monster("墓地$it")) }
        assertTrue("片方を満たせば発動できる", engine.activatableCards(me).any { it === card })

        me.graveyard.clear()
        me.life = 1500
        assertTrue("もう片方でもよい", engine.activatableCards(me).any { it === card })
    }

    @Test
    fun `an or-condition reads with matawa`() {
        val text = EffectTextRenderer.render(eitherCard().card, master)
        assertTrue(text, text.contains("、または"))
    }

    // -- 〜したターン ------------------------------------------------------

    private fun afterSummonCard() = spellWith(
        "召喚したターンだけ",
        EffectClause(
            conditions = listOf(
                EventCondition(
                    event = GameEventType.SUMMONED,
                    who = PlayerRef.SELF,
                    window = EventWindow.THIS_TURN
                )
            ),
            actions = listOf(RecoverAction(PlayerRef.SELF, 300))
        )
    )

    @Test
    fun `a this-turn condition is met after the event happened`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        val card = afterSummonCard()
        me.hand.add(card)
        assertFalse("まだ召喚していない", engine.activatableCards(me).any { it === card })

        val summoned = monster("出てくる者")
        me.hand.add(summoned)
        engine.normalSummon(summoned, me, asSet = false)

        assertTrue("召喚したターンなら発動できる", engine.activatableCards(me).any { it === card })
    }

    @Test
    fun `a this-turn condition is not a trigger`() {
        val card = afterSummonCard()
        val effect = card.card.effect!!
        assertFalse("誘発効果にはならない", effect.isTriggered(0))
        assertTrue(
            EffectTextRenderer.render(card.card, master),
            EffectTextRenderer.render(card.card, master).contains("召喚・特殊召喚されたターン")
        )
    }

    @Test
    fun `the record is cleared when the turn changes`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        val summoned = monster("出てくる者")
        me.hand.add(summoned)
        engine.normalSummon(summoned, me, asSet = false)

        val card = afterSummonCard()
        me.hand.add(card)
        assertTrue(engine.activatableCards(me).any { it === card })

        engine.endTurnImmediately()
        state.turnPlayerIndex = 0
        state.phase = Phase.MAIN1
        assertFalse("次のターンには満たさない", engine.activatableCards(me).any { it === card })
    }

    // -- 対象になった ------------------------------------------------------

    @Test
    fun `being targeted by a trap is an event`() = runBlocking {
        val (state, engine) = game(turnPlayer = 1)
        val me = state.players[0]
        val opponent = state.players[1]

        // 相手の効果の対象に取られたら回復するモンスター。
        val watched = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "狙われる者", kind = CardKind.MONSTER,
                level = 4, atk = 1000, def = 1000,
                effect = EffectText(
                    locations = listOf(ActivationLocation.FIELD),
                    clauses = listOf(
                        EffectClause(
                            conditions = listOf(
                                EventCondition(
                                    event = GameEventType.TARGETED,
                                    selfOnly = true,
                                    sourceFilters = listOf(KindFilter(CardKind.TRAP))
                                )
                            ),
                            mode = ActivationMode.MANDATORY,
                            actions = listOf(RecoverAction(PlayerRef.SELF, 900))
                        )
                    )
                )
            )
        )
        me.monsterZones[0] = watched

        val trap = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "狙う罠", kind = CardKind.TRAP,
                effect = EffectText(
                    locations = listOf(ActivationLocation.FIELD),
                    clauses = listOf(
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
                        )
                    )
                )
            )
        )
        opponent.spellTrapZones[0] = trap
        trap.faceDown = true
        trap.setOnTurn = state.turn - 1

        engine.activateCard(trap, opponent)
        assertEquals("罠の対象に取られたので誘発する", 8900, me.life)
    }

    @Test
    fun `a targeting condition mentions the source in the text`() {
        val condition = EventCondition(
            event = GameEventType.TARGETED,
            selfOnly = true,
            sourceFilters = listOf(KindFilter(CardKind.TRAP))
        )
        assertEquals(
            "このカードが罠カードによって効果の対象になった場合",
            EffectTextRenderer.eventConditionToText(condition, master)
        )
    }

    // -- このカードの効果で特殊召喚した ------------------------------------

    @Test
    fun `an effect is granted only to monsters this card summoned`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        val summonedByThis = monster("呼ばれた者")
        val other = monster("元からいた者")
        me.deck.add(summonedByThis)
        me.monsterZones[0] = other

        val summoner = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "呼び出す永続魔法", kind = CardKind.SPELL,
                effect = EffectText(
                    locations = listOf(ActivationLocation.FIELD),
                    afterActivation = AfterActivation.STAY_ON_FIELD,
                    clauses = listOf(
                        EffectClause(
                            mode = ActivationMode.ON_ACTIVATION,
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
                        ),
                        EffectClause(
                            mode = ActivationMode.CONTINUOUS,
                            actions = listOf(
                                GrantEffectAction(
                                    scope = CardScope(
                                        who = PlayerRef.SELF,
                                        zone = ZoneType.MONSTER_ZONE,
                                        filters = listOf(SummonedByThisFilter()),
                                        selection = SelectionMode.ALL
                                    ),
                                    granted = EffectClause(
                                        locations = listOf(ActivationLocation.FIELD),
                                        mode = ActivationMode.CONTINUOUS,
                                        actions = listOf(
                                            ModifyStatAction(
                                                CardScope(selfOnly = true),
                                                StatKind.ATK,
                                                deltaValue = FixedValue(600)
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )
        me.hand.add(summoner)
        engine.activateCard(summoner, me)

        assertTrue("デッキから出てきている", me.monsters.any { it === summonedByThis })
        assertEquals("このカードが出したモンスターだけ強化される", 1600, engine.atkOf(summonedByThis))
        assertEquals("元からいたモンスターは変わらない", 1000, engine.atkOf(other))
    }

    @Test
    fun `the summoned-by filter reads as expected`() {
        val scope = CardScope(
            who = PlayerRef.SELF,
            zone = ZoneType.MONSTER_ZONE,
            filters = listOf(SummonedByThisFilter(), KindFilter(CardKind.MONSTER)),
            selection = SelectionMode.ALL
        )
        assertEquals(
            "自分モンスターゾーンの全てのこのカードの効果によって特殊召喚されたモンスター",
            EffectTextRenderer.scopeToText(scope, master)
        )
    }

    // -- フェイズ操作 ------------------------------------------------------

    @Test
    fun `an effect can end the turn`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        me.deck.add(monster("山札"))
        state.players[1].deck.add(monster("相手の山札"))

        val card = spellWith(
            "ターンを終える",
            EffectClause(actions = listOf(AdvancePhaseAction(PhaseAdvance.END_TURN)))
        )
        me.hand.add(card)
        engine.activateCard(card, me)

        assertEquals("相手のターンになる", 1, state.turnPlayerIndex)
        assertEquals(4, state.turn)
    }

    @Test
    fun `an effect can skip the current phase`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        val card = spellWith(
            "このフェイズを飛ばす",
            EffectClause(actions = listOf(AdvancePhaseAction(PhaseAdvance.SKIP_PHASE)))
        )
        me.hand.add(card)
        engine.activateCard(card, me)

        assertEquals("メインフェイズ1の次へ進む", Phase.BATTLE, state.phase)
    }

    // -- 攻撃対象になった --------------------------------------------------

    @Test
    fun `being attacked is an event the defender can react to`() = runBlocking {
        val (state, engine) = game(turnPlayer = 1)
        val me = state.players[0]
        val opponent = state.players[1]
        state.phase = Phase.BATTLE

        val defender = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "反撃する者", kind = CardKind.MONSTER,
                level = 4, atk = 1000, def = 2000,
                effect = EffectText(
                    locations = listOf(ActivationLocation.FIELD),
                    clauses = listOf(
                        EffectClause(
                            conditions = listOf(
                                EventCondition(
                                    event = GameEventType.ATTACK_TARGETED,
                                    selfOnly = true
                                )
                            ),
                            mode = ActivationMode.MANDATORY,
                            actions = listOf(RecoverAction(PlayerRef.SELF, 700))
                        )
                    )
                )
            )
        )
        defender.position = Position.DEFENSE
        me.monsterZones[0] = defender

        val attacker = monster("殴る者", atk = 1500)
        opponent.monsterZones[0] = attacker
        attacker.summonedOnTurn = state.turn - 1

        engine.declareAttack(attacker, defender)
        assertEquals("攻撃対象になったので誘発する", 8700, me.life)
    }

    @Test
    fun `the attacker can be named in the condition`() {
        val condition = EventCondition(
            event = GameEventType.ATTACK_TARGETED,
            selfOnly = true,
            sourceFilters = listOf(KindFilter(CardKind.MONSTER))
        )
        assertEquals(
            "このカードがモンスターによって攻撃対象になった場合",
            EffectTextRenderer.eventConditionToText(condition, master)
        )
    }

    // -- 複数の領域 --------------------------------------------------------

    @Test
    fun `a scope can span several zones`() {
        val (state, engine) = game()
        val me = state.players[0]

        val inDeck = monster("デッキの子")
        val inGrave = monster("墓地の子")
        val inHand = monster("手札の子")
        me.deck.add(inDeck)
        me.graveyard.add(inGrave)
        me.hand.add(inHand)

        val scope = CardScope(
            who = PlayerRef.SELF,
            zones = listOf(ZoneType.DECK, ZoneType.GRAVEYARD),
            filters = listOf(KindFilter(CardKind.MONSTER)),
            selection = SelectionMode.ALL
        )
        val found = engine.candidates(scope, me)

        assertEquals(2, found.size)
        assertTrue(found.any { it === inDeck })
        assertTrue(found.any { it === inGrave })
        assertFalse("手札は含まれない", found.any { it === inHand })
    }

    @Test
    fun `several zones read with matawa`() {
        val scope = CardScope(
            who = PlayerRef.SELF,
            zones = listOf(ZoneType.DECK, ZoneType.GRAVEYARD),
            filters = listOf(KindFilter(CardKind.MONSTER)),
            count = 1
        )
        assertEquals(
            "自分のデッキまたは墓地のモンスター1体",
            EffectTextRenderer.scopeToText(scope, master)
        )
    }

    @Test
    fun `an effect can search across zones`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        val target = monster("墓地の子")
        me.graveyard.add(target)

        val card = spellWith(
            "デッキまたは墓地から",
            EffectClause(
                actions = listOf(
                    ToHandAction(
                        CardScope(
                            who = PlayerRef.SELF,
                            zones = listOf(ZoneType.DECK, ZoneType.GRAVEYARD),
                            filters = listOf(KindFilter(CardKind.MONSTER)),
                            count = 1
                        )
                    )
                )
            )
        )
        me.hand.add(card)
        assertTrue(engine.activatableCards(me).any { it === card })
        engine.activateCard(card, me)

        assertTrue("墓地からでも手札に加わる", me.hand.any { it === target })
    }

    @Test
    fun `the phase action is written plainly`() {
        val text = EffectTextRenderer.actionToText(
            AdvancePhaseAction(PhaseAdvance.TO_END_PHASE), master
        )
        assertEquals("エンドフェイズにする", text)
    }
}
