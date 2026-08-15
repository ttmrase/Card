package com.cardforge.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.cardforge.model.*

enum class Phase(val label: String) {
    DRAW("ドローフェイズ"),
    MAIN1("メインフェイズ1"),
    BATTLE("バトルフェイズ"),
    MAIN2("メインフェイズ2"),
    END("エンドフェイズ")
}

/**
 * デュエル中の1枚のカード。同じ [CardDef] から作られた複数のコピーを
 * 区別するため [uid] を持つ。表示状態やステータス変化はここに載る。
 */
class CardInstance(
    val uid: String,
    val card: CardDef
) {
    var position by mutableStateOf(Position.ATTACK)
    var faceDown by mutableStateOf(false)

    var atkMod by mutableIntStateOf(0)
    var defMod by mutableIntStateOf(0)

    /** 伏せられたターン。罠の「伏せたターンには発動できない」判定に使う。 */
    var setOnTurn by mutableIntStateOf(-1)
    var summonedOnTurn by mutableIntStateOf(-1)

    var hasAttacked by mutableStateOf(false)
    var positionChangedThisTurn by mutableStateOf(false)

    val atkValue: Int get() = (card.atk + atkMod).coerceAtLeast(0)
    val defValue: Int get() = (card.def + defMod).coerceAtLeast(0)

    /** 効果の対象判定や表示に使う、実際の表示形式。 */
    val displayPosition: Position
        get() = when {
            faceDown -> Position.FACE_DOWN_DEFENSE
            else -> position
        }

    fun resetForNewTurn() {
        hasAttacked = false
        positionChangedThisTurn = false
    }

    override fun toString(): String = "${card.name}#${uid.take(4)}"
}

/**
 * そのターンに発動した効果の記録。【制限】を同名カードやカテゴリの単位で
 * 数えられるよう、カード本体ではなくプレイヤー側に持たせる。
 */
data class ActivationRecord(
    val instanceUid: String,
    val cardName: String,
    val categoryIds: List<String>,
    val clauseIndex: Int
)

class PlayerState(
    val index: Int,
    val name: String
) {
    var life by mutableIntStateOf(DeckRules.STARTING_LIFE)

    val deck: SnapshotStateList<CardInstance> = mutableStateListOf()
    val hand: SnapshotStateList<CardInstance> = mutableStateListOf()
    val graveyard: SnapshotStateList<CardInstance> = mutableStateListOf()
    val banished: SnapshotStateList<CardInstance> = mutableStateListOf()

    val monsterZones: SnapshotStateList<CardInstance?> =
        mutableStateListOf<CardInstance?>().apply { repeat(DeckRules.ZONE_COUNT) { add(null) } }
    val spellTrapZones: SnapshotStateList<CardInstance?> =
        mutableStateListOf<CardInstance?>().apply { repeat(DeckRules.ZONE_COUNT) { add(null) } }

    /** そのターンに通常召喚（セット含む）を使ったか。 */
    var normalSummonUsed by mutableStateOf(false)

    /** そのターンに発動した効果の記録（【制限】の判定に使う）。 */
    val activationsThisTurn: SnapshotStateList<ActivationRecord> = mutableStateListOf()

    val monsters: List<CardInstance> get() = monsterZones.filterNotNull()
    val spellsAndTraps: List<CardInstance> get() = spellTrapZones.filterNotNull()

    val hasMonsters: Boolean get() = monsterZones.any { it != null }

    fun freeMonsterZones(): List<Int> =
        monsterZones.indices.filter { monsterZones[it] == null }

    fun freeSpellTrapZones(): List<Int> =
        spellTrapZones.indices.filter { spellTrapZones[it] == null }
}

class GameState(
    val players: List<PlayerState>
) {
    var turn by mutableIntStateOf(1)
    var turnPlayerIndex by mutableIntStateOf(0)
    var phase by mutableStateOf(Phase.DRAW)

    /** 決着がついたら勝者のインデックス。引き分けは null のまま [finished] が true。 */
    var winnerIndex by mutableStateOf<Int?>(null)
    var finished by mutableStateOf(false)

    val log: SnapshotStateList<String> = mutableStateListOf()

    val turnPlayer: PlayerState get() = players[turnPlayerIndex]
    val nonTurnPlayer: PlayerState get() = players[1 - turnPlayerIndex]

    fun opponentOf(player: PlayerState): PlayerState = players[1 - player.index]

    fun addLog(message: String) {
        log.add(message)
        if (log.size > 300) log.removeAt(0)
    }
}

/** デッキ構築の結果からデュエルの初期状態を作る。 */
object GameSetup {

    fun build(
        library: Library,
        deckA: Deck,
        nameA: String,
        deckB: Deck,
        nameB: String,
        firstPlayer: Int = 0
    ): GameState {
        val p0 = PlayerState(0, nameA)
        val p1 = PlayerState(1, nameB)

        fillDeck(p0, library, deckA)
        fillDeck(p1, library, deckB)

        val state = GameState(listOf(p0, p1))
        state.turnPlayerIndex = firstPlayer
        state.turn = 1
        state.phase = Phase.MAIN1

        repeat(DeckRules.STARTING_HAND) {
            drawInitial(p0)
            drawInitial(p1)
        }

        state.addLog("デュエル開始！ 先攻は ${state.turnPlayer.name}。")
        return state
    }

    private fun fillDeck(player: PlayerState, library: Library, deck: Deck) {
        val instances = deck.cardIds.mapNotNull { id ->
            library.card(id)?.let { CardInstance(newId(), it) }
        }
        player.deck.addAll(instances.shuffled())
    }

    private fun drawInitial(player: PlayerState) {
        if (player.deck.isNotEmpty()) {
            player.hand.add(player.deck.removeAt(0))
        }
    }
}
