package com.cardforge.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.cardforge.model.*

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

    /** 発動する効果で与えられた、そのターンだけの耐性。 */
    val turnProtections: SnapshotStateList<ProtectionKind> = mutableStateListOf()

    /** 乗っているカウンター。種類の ID ごとに個数を持つ。 */
    val counters: SnapshotStateMap<String, Int> = mutableStateMapOf()

    /** [counterId] のカウンターの数。null なら全種類の合計。 */
    fun counterCount(counterId: String?): Int =
        if (counterId == null) counters.values.sum() else counters[counterId] ?: 0

    /** 発動する効果で、そのターン攻撃を封じられているか。 */
    var attackLockedThisTurn by mutableStateOf(false)

    /**
     * このカードを特殊召喚した効果を持つカードの uid。
     * 「このカードの効果によって特殊召喚されたモンスター」の判定に使う。
     */
    var summonedByUid by mutableStateOf<String?>(null)

    /**
     * 公開されている（相手にも見えている）ターン。
     * [PERMANENT_REVEAL] ならその領域にある限りずっと公開。-1 は非公開。
     */
    var revealedUntilTurn by mutableIntStateOf(-1)

    /** [turn] の時点で相手にも見えているか。 */
    fun isRevealed(turn: Int): Boolean =
        revealedUntilTurn == PERMANENT_REVEAL || revealedUntilTurn >= turn

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
        turnProtections.clear()
        attackLockedThisTurn = false
    }

    override fun toString(): String = "${card.name}#${uid.take(4)}"

    companion object {
        /** [revealedUntilTurn] に入れると、ずっと公開し続ける。 */
        const val PERMANENT_REVEAL = Int.MAX_VALUE
    }
}

/** 画面に見せる出来事の種類。演出と音を選ぶのに使う。 */
enum class BoardSignalKind(val label: String) {
    SUMMONED("召喚"),
    ACTIVATED("発動"),
    ATTACK("攻撃"),
    DESTROYED("破壊"),
    SENT_TO_GRAVEYARD("墓地へ"),
    BANISHED("除外"),
    DAMAGE("ダメージ"),
    RECOVER("回復"),
    DRAW("ドロー")
}

/** 画面に見せる出来事1つ。 */
data class BoardSignal(val id: Long, val kind: BoardSignalKind, val text: String)

/**
 * 「このターン、〜以外を特殊召喚できない」のような制限。
 *
 * [untilTurn] のターンが終わると消える。
 */
/** 「このターン、〜は直接攻撃できる」のような許可。 */
data class PlayPermission(
    val kind: PermissionKind,
    val filters: List<CardFilter>,
    val except: Boolean,
    val untilTurn: Int
)

data class PlayRestriction(
    val kind: RestrictionKind,
    val filters: List<CardFilter>,
    /** true なら [filters] に当てはまるもの「以外」を禁止する。 */
    val except: Boolean,
    val untilTurn: Int
)

/**
 * そのターンに発動した効果の記録。【制限】を同名カードやカテゴリの単位で
 * 数えられるよう、カード本体ではなくプレイヤー側に持たせる。
 *
 * [limitKeys] は、この発動が消費した制限の枠。制限は「枠」を共有する
 * カードどうしでだけ数え合うので、制限を書いていないカードが他のカードの
 * 枠を勝手に消費することはない。
 */
data class ActivationRecord(
    val instanceUid: String,
    val cardName: String,
    val categoryIds: List<String>,
    val clauseIndex: Int,
    val limitKeys: List<String> = emptyList()
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

    /** いま掛かっている制限。ターンが進むと期限切れのものが消える。 */
    val restrictions: SnapshotStateList<PlayRestriction> = mutableStateListOf()

    /** いま付いている許可。ターンが進むと期限切れのものが消える。 */
    val permissions: SnapshotStateList<PlayPermission> = mutableStateListOf()

    val monsters: List<CardInstance> get() = monsterZones.filterNotNull()
    val spellsAndTraps: List<CardInstance> get() = spellTrapZones.filterNotNull()

    val hasMonsters: Boolean get() = monsterZones.any { it != null }

    fun freeMonsterZones(): List<Int> =
        monsterZones.indices.filter { monsterZones[it] == null }

    fun freeSpellTrapZones(): List<Int> =
        spellTrapZones.indices.filter { spellTrapZones[it] == null }
}

class GameState(
    val players: List<PlayerState>,
    /** カード名・属性名などを引くための一覧。ログの文面に使う。 */
    val master: MasterData = MasterData()
) {
    var turn by mutableIntStateOf(1)
    var turnPlayerIndex by mutableIntStateOf(0)
    var phase by mutableStateOf(Phase.DRAW)

    /** 決着がついたら勝者のインデックス。引き分けは null のまま [finished] が true。 */
    var winnerIndex by mutableStateOf<Int?>(null)
    var finished by mutableStateOf(false)

    val log: SnapshotStateList<String> = mutableStateListOf()

    /** トークンを作るための定義。ライブラリのトークンカードを持ち込む。 */
    var tokenDefs: List<CardDef> = emptyList()

    /**
     * 画面に見せたい出来事。UI が拾って演出と音を出し、済んだら消す。
     */
    val signals: SnapshotStateList<BoardSignal> = mutableStateListOf()

    private var signalSeq = 0L

    fun signal(kind: BoardSignalKind, text: String) {
        signals.add(BoardSignal(signalSeq++, kind, text))
        // 画面が見ていない場合に溜まり続けないよう、古いものは捨てる。
        while (signals.size > 8) signals.removeAt(0)
    }

    /**
     * このターンに起きた出来事。
     * 「〜されたターン」という条件を見るために残しておく。
     */
    val eventsThisTurn: MutableList<GameEvent> = mutableListOf()

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

        val state = GameState(listOf(p0, p1), library.master)
        state.tokenDefs = library.cards.filter { it.isToken }
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
