package com.cardforge

import com.cardforge.game.*
import com.cardforge.model.*
import com.cardforge.text.EffectTextRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class Agree : Interaction {
    override suspend fun chooseCards(
        playerIndex: Int, prompt: String,
        candidates: List<CardInstance>, min: Int, max: Int
    ): List<CardInstance> = candidates.take(max.coerceAtLeast(min).coerceAtLeast(1))

    override suspend fun chooseZone(playerIndex: Int, prompt: String, freeZones: List<Int>) =
        freeZones.firstOrNull()

    override suspend fun confirm(playerIndex: Int, prompt: String) = true
    override suspend fun chooseOption(playerIndex: Int, prompt: String, options: List<String>) = 0
}

/**
 * ターンの指定（自分／相手）、召喚権、攻撃対象の制限、
 * 「このカードの戦闘によって〜がダメージを受けた場合」をまとめて確かめる。
 */
class TurnSideAndAttackTest {

    private val master = MasterData()

    private fun game(turnPlayer: Int = 0): Pair<GameState, GameEngine> {
        val state = GameState(listOf(PlayerState(0, "A"), PlayerState(1, "B")), master)
        state.turnPlayerIndex = turnPlayer
        state.turn = 3
        state.phase = Phase.MAIN1
        return state to GameEngine(state, Agree())
    }

    private fun monster(
        name: String,
        atk: Int = 1500,
        def: Int = 1000,
        effect: EffectText? = null,
        restrictions: List<RestrictionKind> = emptyList(),
        permissions: List<PermissionKind> = emptyList()
    ) = CardInstance(
        newId(),
        CardDef(
            id = newId(), name = name, kind = CardKind.MONSTER,
            level = 4, atk = atk, def = def,
            effect = effect,
            selfRestrictions = restrictions,
            selfPermissions = permissions
        )
    )

    // -- 召喚権を増やす ----------------------------------------------------

    @Test
    fun `an extra summon effect allows a second normal summon`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        val spell = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "二重召喚", kind = CardKind.SPELL,
                effect = EffectText(
                    locations = listOf(ActivationLocation.HAND),
                    clauses = listOf(
                        EffectClause(actions = listOf(ExtraSummonAction(PlayerRef.SELF, 1)))
                    )
                )
            )
        )
        val first = monster("一体目")
        val second = monster("二体目")
        me.hand.add(spell)
        me.hand.add(first)
        me.hand.add(second)

        assertTrue(engine.normalSummon(first, me, asSet = false))
        assertFalse("1回目を使ったので通常召喚できない", engine.canNormalSummon(second, me))

        engine.activateCard(spell, me)
        assertTrue("召喚権が増えた", engine.canNormalSummon(second, me))
        assertTrue(engine.normalSummon(second, me, asSet = false))
        assertEquals(2, me.monsters.size)

        // ターンが変わればもとの1回に戻る。
        engine.endTurnImmediately()
        engine.endTurnImmediately()
        assertEquals(1, me.normalSummonLimit)
        assertEquals(0, me.normalSummonsUsed)
    }

    @Test
    fun `an extra summon reads plainly`() {
        assertEquals(
            "自分はこのターン、通常召喚をもう1回できる",
            EffectTextRenderer.actionToText(ExtraSummonAction(PlayerRef.SELF, 1), master)
        )
    }

    // -- 効果を持たないカード ----------------------------------------------

    @Test
    fun `a no-effect filter picks only vanilla monsters`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        val vanilla = monster("素朴な戦士")
        val fancy = monster(
            "からくり兵",
            effect = EffectText(
                clauses = listOf(EffectClause(actions = listOf(DrawAction(PlayerRef.SELF, 1))))
            )
        )
        me.deck.add(fancy)
        me.deck.add(vanilla)

        val spell = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "素朴を呼ぶ", kind = CardKind.SPELL,
                effect = EffectText(
                    locations = listOf(ActivationLocation.HAND),
                    clauses = listOf(
                        EffectClause(
                            actions = listOf(
                                SpecialSummonAction(
                                    CardScope(
                                        who = PlayerRef.SELF,
                                        zone = ZoneType.DECK,
                                        filters = listOf(HasEffectFilter(hasEffect = false)),
                                        count = 1
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )
        me.hand.add(spell)
        engine.activateCard(spell, me)

        assertEquals(1, me.monsters.size)
        assertEquals("素朴な戦士", me.monsters.first().card.name)
        assertEquals(
            "効果を持たないモンスター",
            EffectTextRenderer.filtersToNoun(
                listOf(HasEffectFilter(hasEffect = false), KindFilter(CardKind.MONSTER)),
                master,
                ZoneType.DECK
            )
        )
    }

    // -- フェイズの「自分／相手」 ------------------------------------------

    @Test
    fun `a phase condition can name the opponent turn`() = runBlocking {
        val (state, engine) = game(turnPlayer = 1)
        val me = state.players[0]
        state.phase = Phase.END

        fun watcher(who: PlayerRef?) = monster(
            "見張り",
            effect = EffectText(
                clauses = listOf(
                    EffectClause(
                        conditions = listOf(PhaseCondition(listOf(Phase.END), who)),
                        actions = listOf(RecoverAction(PlayerRef.SELF, 500))
                    )
                )
            )
        )

        val plain = watcher(null)
        me.monsterZones[0] = plain
        assertTrue(
            "指定なしのモンスター効果は自分のターンだけ",
            engine.activatableClauses(plain, me).isEmpty()
        )

        val onOpponentTurn = watcher(PlayerRef.OPPONENT)
        me.monsterZones[0] = onOpponentTurn
        assertEquals(
            "相手のターンと書いてあれば発動できる",
            listOf(0), engine.activatableClauses(onOpponentTurn, me)
        )
    }

    // -- 特殊召喚できないカードは候補に出さない ----------------------------

    @Test
    fun `cards that cannot be special summoned are not offered`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]

        val locked = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "出せない者", kind = CardKind.MONSTER,
                level = 4, atk = 2000, def = 2000, cannotSpecialSummon = true
            )
        )
        val free = monster("出せる者")
        // 先に出せない方を置く。候補から外れていなければこちらが選ばれてしまう。
        me.deck.add(locked)
        me.deck.add(free)

        val spell = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "呼ぶ魔法", kind = CardKind.SPELL,
                effect = EffectText(
                    locations = listOf(ActivationLocation.HAND),
                    clauses = listOf(
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
                )
            )
        )
        me.hand.add(spell)
        engine.activateCard(spell, me)

        assertEquals(1, me.monsters.size)
        assertEquals("出せる者", me.monsters.first().card.name)
    }

    // -- このカードの戦闘によるダメージ ------------------------------------

    @Test
    fun `battle damage through this card triggers by the damaged side`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        val opponent = state.players[1]
        state.phase = Phase.BATTLE

        val attacker = monster(
            "突撃兵", atk = 2000,
            effect = EffectText(
                clauses = listOf(
                    EffectClause(
                        conditions = listOf(
                            EventCondition(
                                event = GameEventType.DAMAGE_TAKEN,
                                who = PlayerRef.OPPONENT,
                                selfOnly = true,
                                cause = CauseFilter.BY_BATTLE
                            )
                        ),
                        mode = ActivationMode.MANDATORY,
                        actions = listOf(DrawAction(PlayerRef.SELF, 1))
                    )
                )
            )
        )
        me.monsterZones[0] = attacker
        attacker.summonedOnTurn = 1
        me.deck.add(monster("山札の1枚目"))

        engine.declareAttack(attacker, null)

        assertEquals("相手が戦闘ダメージを受けた", 6000, opponent.life)
        assertEquals("その戦闘に反応してドローした", 1, me.hand.size)
    }

    @Test
    fun `battle damage to the controller does not fire an opponent-side trigger`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        state.phase = Phase.BATTLE

        val weak = monster(
            "小さき者", atk = 500,
            effect = EffectText(
                clauses = listOf(
                    EffectClause(
                        conditions = listOf(
                            EventCondition(
                                event = GameEventType.DAMAGE_TAKEN,
                                who = PlayerRef.OPPONENT,
                                selfOnly = true,
                                cause = CauseFilter.BY_BATTLE
                            )
                        ),
                        mode = ActivationMode.MANDATORY,
                        actions = listOf(DrawAction(PlayerRef.SELF, 1))
                    )
                )
            )
        )
        me.monsterZones[0] = weak
        weak.summonedOnTurn = 1
        me.deck.add(monster("山札の1枚目"))
        state.players[1].monsterZones[0] = monster("大きき者", atk = 2000)

        engine.declareAttack(weak, state.players[1].monsters.first())

        assertEquals("自分が戦闘ダメージを受けた", 6500, me.life)
        assertTrue("相手が受けた場合の効果なので起きない", me.hand.isEmpty())
    }

    @Test
    fun `battle damage conditions read with the damaged side`() {
        assertEquals(
            "このカードの戦闘によって相手がダメージを受けた場合",
            EffectTextRenderer.conditionToText(
                EventCondition(
                    event = GameEventType.DAMAGE_TAKEN,
                    who = PlayerRef.OPPONENT,
                    selfOnly = true,
                    cause = CauseFilter.BY_BATTLE
                ),
                master
            )
        )
    }

    // -- 攻撃対象の制限 ----------------------------------------------------

    @Test
    fun `a monster that cannot be attacked is skipped as a target`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        val opponent = state.players[1]
        state.phase = Phase.BATTLE

        val attacker = monster("突撃兵", atk = 2000)
        me.monsterZones[0] = attacker
        attacker.summonedOnTurn = 1

        val hidden = monster("隠れる者", restrictions = listOf(RestrictionKind.BE_ATTACKED))
        val plain = monster("普通の者")
        opponent.monsterZones[0] = hidden
        opponent.monsterZones[1] = plain

        assertEquals(listOf(plain), engine.attackTargets())

        engine.declareAttack(attacker, hidden)
        assertTrue("攻撃対象にできないので何も起きない", opponent.monsters.contains(hidden))
        assertEquals(8000, opponent.life)
    }

    @Test
    fun `a monster that must be attacked forces the target`() {
        val (state, engine) = game()
        val opponent = state.players[1]
        state.phase = Phase.BATTLE

        val lure = monster("挑発する者", permissions = listOf(PermissionKind.MUST_BE_ATTACKED))
        val other = monster("普通の者")
        opponent.monsterZones[0] = other
        opponent.monsterZones[1] = lure

        assertEquals(listOf(lure), engine.attackTargets())
    }

    @Test
    fun `attack restrictions read plainly`() {
        val card = CardDef(
            id = "x", name = "隠れる者", kind = CardKind.MONSTER,
            level = 4, atk = 1000, def = 1000,
            selfRestrictions = listOf(RestrictionKind.BE_ATTACKED),
            selfPermissions = listOf(PermissionKind.MUST_BE_ATTACKED)
        )
        val text = EffectTextRenderer.render(card, master)
        assertTrue(text, text.contains("攻撃対象にできない"))
        assertTrue(text, text.contains("他のモンスターを攻撃できない"))
    }

    // -- 【強制】は相手ターンでも起きる ------------------------------------

    @Test
    fun `a mandatory clause is treated as a quick effect`() {
        val effect = EffectText(
            clauses = listOf(
                EffectClause(
                    conditions = listOf(PhaseCondition(listOf(Phase.MAIN1))),
                    mode = ActivationMode.MANDATORY,
                    actions = listOf(DrawAction(PlayerRef.SELF, 1))
                ),
                EffectClause(
                    conditions = listOf(PhaseCondition(listOf(Phase.MAIN1))),
                    mode = ActivationMode.OPTIONAL,
                    actions = listOf(DrawAction(PlayerRef.SELF, 1))
                )
            )
        )
        val (state, engine) = game()
        val inst = monster("試験体", effect = effect)
        assertTrue(engine.isQuickEffect(inst, effect, 0))
        assertFalse(engine.isQuickEffect(inst, effect, 1))
        assertEquals(state.players.size, 2)
    }
}
