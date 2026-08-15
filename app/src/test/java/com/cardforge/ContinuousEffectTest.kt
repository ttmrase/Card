package com.cardforge

import com.cardforge.game.*
import com.cardforge.model.*
import com.cardforge.text.EffectTextRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class Auto : Interaction {
    override suspend fun chooseCards(
        playerIndex: Int, prompt: String,
        candidates: List<CardInstance>, min: Int, max: Int
    ): List<CardInstance> =
        if (min == 0) emptyList() else candidates.take(maxOf(min, 1).coerceAtMost(max))

    override suspend fun chooseZone(playerIndex: Int, prompt: String, freeZones: List<Int>) =
        freeZones.firstOrNull()

    override suspend fun confirm(playerIndex: Int, prompt: String) = true
    override suspend fun chooseOption(playerIndex: Int, prompt: String, options: List<String>) = 0
}

class ContinuousEffectTest {

    private fun game(): Pair<GameState, GameEngine> {
        val state = GameState(listOf(PlayerState(0, "A"), PlayerState(1, "B")))
        state.turnPlayerIndex = 0
        state.turn = 2
        state.phase = Phase.MAIN1
        return state to GameEngine(state, Auto())
    }

    /** 永続の効果は、【発動タイプ】を「永続」にした効果として書く。 */
    private fun continuous(vararg actions: Action) = EffectText(
        clauses = listOf(
            EffectClause(mode = ActivationMode.CONTINUOUS, actions = actions.toList())
        )
    )

    private fun monster(
        name: String,
        atk: Int = 1000,
        def: Int = 1000,
        effect: EffectText? = null
    ) = CardInstance(
        newId(),
        CardDef(
            id = newId(), name = name, kind = CardKind.MONSTER,
            level = 4, atk = atk, def = def, effect = effect
        )
    )

    @Test
    fun `a stat buff applies while the source is face-up on the field`() {
        val (state, engine) = game()
        val me = state.players[0]

        val lord = monster(
            "指揮官", atk = 1000,
            effect = continuous(
                ModifyStatAction(
                    scope = CardScope(
                        who = PlayerRef.SELF,
                        zone = ZoneType.MONSTER_ZONE,
                        selection = SelectionMode.ALL
                    ),
                    stat = StatKind.ATK,
                    delta = 500
                )
            )
        )
        val ally = monster("味方", atk = 1200)
        me.monsterZones[0] = lord
        me.monsterZones[1] = ally

        assertEquals(1700, engine.atkOf(ally))
        // 適用範囲に自分も入るので、指揮官自身も上がる。
        assertEquals(1500, engine.atkOf(lord))

        // 場を離れれば元に戻る。
        me.monsterZones[0] = null
        assertEquals(1200, engine.atkOf(ally))
    }

    @Test
    fun `a face-down source grants nothing`() {
        val (state, engine) = game()
        val me = state.players[0]
        val lord = monster(
            "伏せた指揮官",
            effect = continuous(
                ModifyStatAction(CardScope(selfOnly = true), StatKind.ATK, 500)
            )
        )
        lord.faceDown = true
        me.monsterZones[0] = lord
        assertEquals(1000, engine.atkOf(lord))

        lord.faceDown = false
        assertEquals(1500, engine.atkOf(lord))
    }

    @Test
    fun `a card immune to opponent effects cannot be chosen by them`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        val opponent = state.players[1]

        val warded = monster(
            "守られた者",
            effect = continuous(
                GrantProtectionAction(CardScope(selfOnly = true), ProtectionKind.OPPONENT_EFFECTS)
            )
        )
        val plain = monster("普通の者")
        me.monsterZones[0] = warded
        me.monsterZones[1] = plain

        val scope = CardScope(
            who = PlayerRef.OPPONENT,
            zone = ZoneType.MONSTER_ZONE,
            selection = SelectionMode.ALL
        )
        // 相手から見ると、耐性持ちは対象に含まれない。
        val fromOpponent = engine.candidates(scope, opponent)
        assertFalse(fromOpponent.any { it === warded })
        assertTrue(fromOpponent.any { it === plain })

        // 自分の効果では選べる。
        val ownScope = scope.copy(who = PlayerRef.SELF)
        assertTrue(engine.candidates(ownScope, me).any { it === warded })
    }

    @Test
    fun `battle destruction protection keeps the monster on the field`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        val opponent = state.players[1]
        state.phase = Phase.BATTLE

        val attacker = monster("攻撃側", atk = 2500)
        me.monsterZones[0] = attacker
        val tough = monster(
            "不滅の壁", atk = 1000,
            effect = continuous(
                GrantProtectionAction(CardScope(selfOnly = true), ProtectionKind.BATTLE_DESTRUCTION)
            )
        )
        opponent.monsterZones[0] = tough

        engine.declareAttack(attacker, tough)
        // ダメージは通るが、破壊はされない。
        assertEquals(8000 - 1500, opponent.life)
        assertTrue(opponent.monsters.any { it === tough })
    }

    @Test
    fun `a locked monster cannot declare an attack`() {
        val (state, engine) = game()
        val me = state.players[0]
        state.phase = Phase.BATTLE

        val locked = monster(
            "縛られた者",
            effect = continuous(PreventAttackAction(CardScope(selfOnly = true)))
        )
        me.monsterZones[0] = locked
        assertFalse(engine.canAttack(locked))

        val free = monster("自由な者")
        me.monsterZones[1] = free
        assertTrue(engine.canAttack(free))
    }

    @Test
    fun `a continuous clause reads as lasting while the card is there`() {
        val master = MasterData(categories = listOf(NamedEntry("cat-1", "アララギ")))
        val card = CardDef(
            id = "x", name = "旗印", kind = CardKind.SPELL,
            effect = EffectText(
                clauses = listOf(
                    EffectClause(
                        mode = ActivationMode.CONTINUOUS,
                        actions = listOf(
                            ModifyStatAction(
                                scope = CardScope(
                                    who = PlayerRef.SELF,
                                    zone = ZoneType.MONSTER_ZONE,
                                    filters = listOf(
                                        CategoryFilter("cat-1"),
                                        KindFilter(CardKind.MONSTER)
                                    ),
                                    selection = SelectionMode.ALL
                                ),
                                stat = StatKind.ATK,
                                delta = 500
                            )
                        )
                    )
                )
            )
        )
        val lines = EffectTextRenderer.render(card, master).lines()
        assertEquals(
            "①：このカードがフィールドに存在する限り、" +
                "自分モンスターゾーンの全ての「アララギ」モンスターの攻撃力は500アップする。",
            lines.last()
        )
        assertTrue(card.hasContinuous)
    }

    @Test
    fun `a continuous clause is never activated by hand or by an event`() {
        val (state, engine) = game()
        val me = state.players[0]
        val card = monster(
            "永続持ち",
            effect = continuous(
                GrantProtectionAction(CardScope(selfOnly = true), ProtectionKind.OPPONENT_EFFECTS)
            )
        )
        me.monsterZones[0] = card
        assertTrue(engine.activatableClauses(card, me).isEmpty())
        assertFalse(engine.activatableCards(me).any { it === card })
    }

    @Test
    fun `granting protection from an activated effect lasts only for the turn`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        val guard = monster("守り手")
        me.monsterZones[0] = guard

        val spell = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "一時の加護", kind = CardKind.SPELL,
                effect = EffectText(
                    clauses = listOf(
                        EffectClause(
                            actions = listOf(
                                GrantProtectionAction(
                                    CardScope(
                                        who = PlayerRef.SELF,
                                        zone = ZoneType.MONSTER_ZONE,
                                        selection = SelectionMode.ALL
                                    ),
                                    ProtectionKind.BATTLE_DESTRUCTION
                                )
                            )
                        )
                    )
                )
            )
        )
        me.hand.add(spell)

        assertFalse(engine.hasProtection(guard, ProtectionKind.BATTLE_DESTRUCTION))
        engine.activateCard(spell, me)
        assertTrue(engine.hasProtection(guard, ProtectionKind.BATTLE_DESTRUCTION))

        // 次のターンには切れている。
        guard.resetForNewTurn()
        assertFalse(engine.hasProtection(guard, ProtectionKind.BATTLE_DESTRUCTION))
    }

    @Test
    fun `old saved cards keep working through the migration`() {
        val legacy = CardDef(
            id = "x", name = "旧データ", kind = CardKind.MONSTER,
            level = 4, atk = 1000, def = 1000,
            continuous = listOf(
                ProtectionEffect(scope = null, kind = ProtectionKind.OPPONENT_EFFECTS)
            )
        )
        val migrated = com.cardforge.data.LegacyMigration.migrate(legacy)

        assertTrue(migrated.continuous.isEmpty())
        val clause = migrated.effect!!.clauses.single()
        assertEquals(ActivationMode.CONTINUOUS, clause.mode)
        val action = clause.actions.single() as GrantProtectionAction
        assertEquals(ProtectionKind.OPPONENT_EFFECTS, action.kind)
        assertTrue("旧データの対象はこのカード自身", action.scope.selfOnly)
    }
}
