package com.cardforge

import com.cardforge.game.*
import com.cardforge.model.*
import com.cardforge.text.EffectTextRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class Auto2 : Interaction {
    override suspend fun chooseCards(
        playerIndex: Int, prompt: String,
        candidates: List<CardInstance>, min: Int, max: Int
    ): List<CardInstance> = candidates.take(max.coerceAtLeast(min).coerceAtLeast(1))

    override suspend fun chooseZone(playerIndex: Int, prompt: String, freeZones: List<Int>) =
        freeZones.firstOrNull()

    override suspend fun confirm(playerIndex: Int, prompt: String) = true
    override suspend fun chooseOption(playerIndex: Int, prompt: String, options: List<String>) = 0
}

/** 誘発のきっかけを対象にする指定、直接攻撃の許可、戦闘ダメージとリリースの制限。 */
class TriggerTargetTest {

    private val master = MasterData()

    private fun game(turnPlayer: Int = 0): Pair<GameState, GameEngine> {
        val state = GameState(listOf(PlayerState(0, "A"), PlayerState(1, "B")), master)
        state.turnPlayerIndex = turnPlayer
        state.turn = 3
        state.phase = Phase.MAIN1
        return state to GameEngine(state, Auto2())
    }

    private fun monster(name: String, atk: Int = 1500, level: Int = 4) = CardInstance(
        newId(),
        CardDef(
            id = newId(), name = name, kind = CardKind.MONSTER,
            level = level, atk = atk, def = 1000
        )
    )

    // -- 攻撃に関わった相手を対象にする -----------------------------------

    @Test
    fun `an attacked monster can destroy its attacker`() = runBlocking {
        val (state, engine) = game(turnPlayer = 1)
        val me = state.players[0]
        val opponent = state.players[1]

        val defender = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "反撃する者", kind = CardKind.MONSTER,
                level = 4, atk = 1000, def = 2500,
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
                            actions = listOf(
                                DestroyAction(
                                    CardScope(triggerCard = TriggerCardRef.SOURCE_CARD)
                                )
                            )
                        )
                    )
                )
            )
        )
        defender.position = Position.DEFENSE
        me.monsterZones[0] = defender

        val attacker = monster("殴る者", atk = 2000)
        opponent.monsterZones[0] = attacker
        attacker.summonedOnTurn = state.turn - 1
        state.phase = Phase.BATTLE

        engine.declareAttack(attacker, defender)
        assertTrue("攻撃してきたモンスターが破壊される", opponent.graveyard.any { it === attacker })
    }

    @Test
    fun `the attacker can target the monster it attacked`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        val opponent = state.players[1]

        val attacker = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "狙い撃つ者", kind = CardKind.MONSTER,
                level = 4, atk = 2000, def = 1000,
                effect = EffectText(
                    locations = listOf(ActivationLocation.FIELD),
                    clauses = listOf(
                        EffectClause(
                            conditions = listOf(
                                EventCondition(
                                    event = GameEventType.ATTACK_DECLARED,
                                    selfOnly = true
                                )
                            ),
                            mode = ActivationMode.MANDATORY,
                            actions = listOf(
                                ToHandAction(
                                    CardScope(triggerCard = TriggerCardRef.SOURCE_CARD)
                                )
                            )
                        )
                    )
                )
            )
        )
        me.monsterZones[0] = attacker
        attacker.summonedOnTurn = state.turn - 1

        val victim = monster("狙われた者")
        opponent.monsterZones[0] = victim
        state.phase = Phase.BATTLE

        engine.declareAttack(attacker, victim)
        assertTrue("攻撃対象が手札に戻る", opponent.hand.any { it === victim })
    }

    @Test
    fun `a cost can send the triggering card away`() = runBlocking {
        val (state, engine) = game(turnPlayer = 1)
        val me = state.players[0]
        val opponent = state.players[1]

        // 相手のモンスターが召喚されたとき、そのモンスターを墓地へ送ってライフを回復する。
        val watcher = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "見咎める者", kind = CardKind.MONSTER,
                level = 4, atk = 1000, def = 1000,
                effect = EffectText(
                    locations = listOf(ActivationLocation.FIELD),
                    clauses = listOf(
                        EffectClause(
                            conditions = listOf(
                                EventCondition(
                                    event = GameEventType.NORMAL_SUMMONED,
                                    who = PlayerRef.OPPONENT
                                )
                            ),
                            costs = listOf(
                                MoveCost(
                                    CardScope(triggerCard = TriggerCardRef.EVENT_CARD),
                                    MoveDestination.GRAVEYARD
                                )
                            ),
                            mode = ActivationMode.MANDATORY,
                            actions = listOf(RecoverAction(PlayerRef.SELF, 400))
                        )
                    )
                )
            )
        )
        me.monsterZones[0] = watcher

        val summoned = monster("出てきた者")
        opponent.hand.add(summoned)
        engine.normalSummon(summoned, opponent, asSet = false)

        assertTrue("そのカードがコストで墓地へ送られる", opponent.graveyard.any { it === summoned })
        assertEquals(8400, me.life)
    }

    // -- 直接攻撃できる ----------------------------------------------------

    @Test
    fun `a permission lets a monster attack directly through blockers`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        val opponent = state.players[1]

        val piercer = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "すり抜ける者", kind = CardKind.MONSTER,
                level = 4, atk = 1200, def = 1000,
                selfPermissions = listOf(PermissionKind.DIRECT_ATTACK)
            )
        )
        me.monsterZones[0] = piercer
        piercer.summonedOnTurn = state.turn - 1
        opponent.monsterZones[0] = monster("壁")
        state.phase = Phase.BATTLE

        assertTrue("相手にモンスターがいても直接攻撃できる", engine.canAttackDirectlyWith(piercer))
        engine.declareAttack(piercer, null)
        assertEquals(6800, opponent.life)
    }

    @Test
    fun `a plain monster still cannot attack directly`() {
        val (state, engine) = game()
        val me = state.players[0]
        val opponent = state.players[1]

        val plain = monster("普通の子")
        me.monsterZones[0] = plain
        plain.summonedOnTurn = state.turn - 1
        opponent.monsterZones[0] = monster("壁")
        state.phase = Phase.BATTLE

        assertFalse(engine.canAttackDirectlyWith(plain))
    }

    // -- 戦闘ダメージの制限 ------------------------------------------------

    @Test
    fun `a monster can be barred from dealing battle damage`() = runBlocking {
        val (state, engine) = game()
        val me = state.players[0]
        val opponent = state.players[1]

        val harmless = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "無害な者", kind = CardKind.MONSTER,
                level = 4, atk = 2000, def = 1000,
                selfRestrictions = listOf(RestrictionKind.DEAL_BATTLE_DAMAGE)
            )
        )
        me.monsterZones[0] = harmless
        harmless.summonedOnTurn = state.turn - 1
        state.phase = Phase.BATTLE

        engine.declareAttack(harmless, null)
        assertEquals("戦闘ダメージを与えられない", 8000, opponent.life)
    }

    @Test
    fun `a player can be spared battle damage`() = runBlocking {
        val (state, engine) = game(turnPlayer = 1)
        val me = state.players[0]
        val opponent = state.players[1]

        val shield = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "守りの結界", kind = CardKind.SPELL,
                effect = EffectText(
                    locations = listOf(ActivationLocation.FIELD),
                    afterActivation = AfterActivation.STAY_ON_FIELD,
                    clauses = listOf(
                        EffectClause(
                            mode = ActivationMode.CONTINUOUS,
                            actions = listOf(
                                RestrictAction(
                                    who = PlayerRef.SELF,
                                    kind = RestrictionKind.TAKE_BATTLE_DAMAGE
                                )
                            )
                        )
                    )
                )
            )
        )
        me.spellTrapZones[0] = shield

        val attacker = monster("殴る者", atk = 2000)
        opponent.monsterZones[0] = attacker
        attacker.summonedOnTurn = state.turn - 1
        state.phase = Phase.BATTLE

        engine.declareAttack(attacker, null)
        assertEquals("戦闘ダメージを受けない", 8000, me.life)
    }

    // -- リリースできない --------------------------------------------------

    @Test
    fun `a monster that cannot be tributed is left out`() {
        val (state, engine) = game()
        val me = state.players[0]

        val cannotTribute = CardInstance(
            newId(),
            CardDef(
                id = newId(), name = "捧げられない者", kind = CardKind.MONSTER,
                level = 4, atk = 1000, def = 1000,
                selfRestrictions = listOf(RestrictionKind.TRIBUTE)
            )
        )
        me.monsterZones[0] = cannotTribute

        val big = monster("上級", level = 6)
        me.hand.add(big)
        assertFalse("リリースできる相手がいない", engine.canNormalSummon(big, me))

        me.monsterZones[1] = monster("普通の子")
        assertTrue("別のモンスターがいればできる", engine.canNormalSummon(big, me))
    }

    // -- 文 ----------------------------------------------------------------

    @Test
    fun `the trigger target and permission read plainly`() {
        assertEquals(
            "その相手のカードを破壊する",
            EffectTextRenderer.actionToText(
                DestroyAction(CardScope(triggerCard = TriggerCardRef.SOURCE_CARD)), master
            )
        )
        assertEquals(
            "このターンの間、自分のモンスターは、相手にモンスターがいても直接攻撃できる",
            EffectTextRenderer.actionToText(
                PermitAction(PlayerRef.SELF, PermissionKind.DIRECT_ATTACK), master
            )
        )
    }
}
