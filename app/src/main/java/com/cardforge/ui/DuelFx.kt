package com.cardforge.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardforge.game.BoardSignal
import com.cardforge.game.BoardSignalKind
import com.cardforge.game.CardInstance
import com.cardforge.game.GameState
import com.cardforge.model.CardKind
import com.cardforge.ui.theme.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// ===========================================================================
// デュエルの演出
//
// エンジンは盤面をすぐ書き換え、合図（[BoardSignal]）を積む。ここではその合図を
// 1つずつ取り出して、盤面の正しい場所に演出を重ねる。盤面の変化に演出が
// 追いつくまでは、
//   ・場を離れたカードは、元の場所に「残像」として描き続け、
//   ・召喚されたカードは、召喚の演出が始まるまで隠しておく。
// こうすると、攻撃が当たる前に相手のモンスターが消える、といったことが起きない。
// ===========================================================================

/** 盤面に置かれたカードの位置（ウィンドウ座標）と、そのときの見た目。 */
data class Spot(
    val rect: Rect,
    val faceUp: Boolean,
    val rotated: Boolean,
    /** そのとき盤面に出ていた攻守。演出の写しにも同じ値を載せる。 */
    val atk: Int? = null,
    val def: Int? = null
)

/** 名前の付いた場所。LP の欄やデッキの数字など、カード以外の目印。 */
object Anchor {
    fun panel(player: Int) = "panel$player"
    fun lp(player: Int) = "lp$player"
    fun deck(player: Int) = "deck$player"
    fun grave(player: Int) = "grave$player"
    fun banish(player: Int) = "banish$player"
    fun hand(player: Int) = "hand$player"
    const val CENTER = "center"
    const val BOARD = "board"
}

/** 再生中の演出1つ。位置は演出を始めた時点で決めておく（演出側の座標系）。 */
class FxPlay(
    val signal: BoardSignal,
    val durationMs: Int,
    /** 主役のカードがいた場所。 */
    val from: Spot?,
    /** 向かう先。攻撃なら攻撃された側、ダメージなら LP の欄など。 */
    val to: Rect?,
    /** 攻撃されたモンスターの見た目（直接攻撃なら null）。 */
    val targetSpot: Spot?
) {
    val progress = Animatable(0f)
}

/**
 * 演出の状態。盤面の位置の記録と、合図の再生を受け持つ。
 *
 * 位置の記録は毎回の配置で更新されるので、Compose の状態にはせず普通の変数に持つ。
 * 再生中の演出と、合図の待ち行列だけが画面の再描画を起こす。
 */
@Stable
class DuelFx(private val state: GameState) {

    private val spots = HashMap<String, Spot>()
    private val anchors = HashMap<String, Rect>()

    /**
     * 場を離れる演出を待っているカードの、離れる前の位置。
     * 手札に戻ったカードなどは新しい場所でも位置が記録されるので、
     * 演出の番が来るまでは元の位置を別に取っておく。
     */
    private val departed = HashMap<String, Spot>()

    /** 演出を重ねる層の、ウィンドウ上の左上。 */
    var origin: Offset = Offset.Zero

    val playing: SnapshotStateList<FxPlay> = mutableStateListOf()

    fun place(
        card: CardInstance,
        rect: Rect,
        faceUp: Boolean,
        rotated: Boolean,
        atk: Int? = null,
        def: Int? = null
    ) {
        val spot = Spot(rect, faceUp, rotated, atk, def)
        val old = spots[card.uid]
        if (old != null && old != spot && card.uid !in departed && isLeavingPending(card)) {
            departed[card.uid] = old
        }
        spots[card.uid] = spot
    }

    private fun isLeavingPending(card: CardInstance): Boolean =
        state.signals.any { it.kind.isLeaving && it.card === card }

    /** 場を離れる演出に使う位置。離れる前の位置が取ってあればそちら。 */
    private fun departureOf(card: CardInstance): Spot? =
        (departed[card.uid] ?: spots[card.uid])?.let(::localSpot)

    fun anchor(name: String, rect: Rect) {
        anchors[name] = rect
    }

    private fun local(rect: Rect): Rect = rect.translate(-origin)
    private fun localSpot(spot: Spot): Spot = spot.copy(rect = local(spot.rect))
    fun spotOf(card: CardInstance): Spot? = spots[card.uid]?.let(::localSpot)
    fun anchorOf(name: String): Rect? = anchors[name]?.let(::local)

    // -- 盤面が参照する状態 ---------------------------------------------------

    /** 演出が全部済んで、画面が今の盤面に追いついているか。 */
    val idle: Boolean get() = state.signals.isEmpty() && playing.isEmpty()

    /**
     * 演出がひと区切りついたか。問い合わせのダイアログや操作はこれを待って出す。
     * 最後まで待つと間延びするので、それぞれ山場を越えたところで区切りとする。
     * ターンの始まりの帯だけは、盤面を覆うので終わるまで待つ。
     */
    val settled: Boolean
        get() = state.signals.isEmpty() && playing.all {
            it.signal.kind != BoardSignalKind.TURN_START && it.progress.value >= SETTLE_AT
        }

    /** 召喚や攻撃の演出が自分でカードを描くあいだ、盤面の方は隠す。 */
    fun isHidden(card: CardInstance): Boolean =
        state.signals.any { it.kind == BoardSignalKind.SUMMONED && it.card === card } ||
            playing.any {
                (it.signal.kind == BoardSignalKind.SUMMONED || it.signal.kind == BoardSignalKind.ATTACK) &&
                    it.signal.card === card
            }

    /** まだ演出が始まっていない「場を離れた」カードと、その元の場所。 */
    fun ghosts(): List<Pair<CardInstance, Spot>> =
        state.signals
            .filter { it.kind.isLeaving }
            .mapNotNull { signal -> signal.card?.let { card -> departureOf(card)?.let { card to it } } }
            .distinctBy { it.first.uid }

    /** 画面に出している LP。ダメージの演出が始まるまでは、前の値のまま見せる。 */
    fun shownLife(playerIndex: Int): Int =
        state.signals.firstOrNull {
            (it.kind == BoardSignalKind.DAMAGE || it.kind == BoardSignalKind.RECOVER) &&
                it.playerIndex == playerIndex
        }?.lifeBefore ?: state.players[playerIndex].life

    /** ダメージを受けた側の欄を揺らす量（px）。描画の段階で読む。 */
    fun panelShake(playerIndex: Int): Float {
        val play = playing.firstOrNull {
            it.signal.kind == BoardSignalKind.DAMAGE && it.signal.playerIndex == playerIndex
        } ?: return 0f
        val t = play.progress.value
        if (t > 0.5f) return 0f
        return (sin(t * PI * 16).toFloat() * (1f - t * 2f)) * 14f
    }

    /** 大きなダメージのときに盤面ごと揺らす量（px）。 */
    fun boardShake(): Float {
        val play = playing.firstOrNull {
            it.signal.kind == BoardSignalKind.DAMAGE && it.signal.amount >= BIG_HIT
        } ?: return 0f
        val t = play.progress.value
        if (t > 0.4f) return 0f
        return (sin(t * PI * 20).toFloat() * (1f - t / 0.4f)) * 10f
    }

    // -- 再生 ----------------------------------------------------------------

    /**
     * 合図を順に取り出して演出を始める。画面にいるあいだ回り続ける。
     * 時間はすべて画面のフレームで数えるので、端末の描画と必ず揃う。
     */
    suspend fun run(onStart: (BoardSignal) -> Unit) = coroutineScope {
        while (isActive) {
            val next = snapshotFlow { state.signals.firstOrNull() }.filterNotNull().first()
            // 盤面が新しい状態で並べ直されるのを待ってから位置を読む。
            // 召喚されたカードなら、手札ではなく置かれた枠の位置が取れる。
            withFrameMillis { }
            withFrameMillis { }
            val play = prepare(next)
            // 待ち行列から外すのと再生中に入れるのを同時に行い、
            // 残像や隠しが1コマだけ途切れることの無いようにする。
            Snapshot.withMutableSnapshot {
                state.signals.remove(next)
                playing.add(play)
            }
            onStart(next)
            launch {
                play.progress.animateTo(1f, tween(play.durationMs, easing = LinearEasing))
                playing.remove(play)
            }
            // 溜まっているときは間を詰めて追いつく。
            val backlog = state.signals.size
            val pace = when {
                backlog > 6 -> 0.35f
                backlog > 2 -> 0.7f
                else -> 1f
            }
            awaitMillis((gapOf(next.kind) * pace).toLong())
        }
    }

    private fun prepare(signal: BoardSignal): FxPlay {
        val card = signal.card
        val owner = signal.playerIndex ?: 0
        val from = card?.let {
            if (signal.kind.isLeaving) departureOf(it).also { _ -> departed.remove(it.uid) }
            else spotOf(it)
        }
        val targetSpot = signal.target?.let(::spotOf)
        val to: Rect? = when (signal.kind) {
            BoardSignalKind.ATTACK ->
                targetSpot?.rect ?: anchorOf(Anchor.lp(1 - owner)) ?: anchorOf(Anchor.panel(1 - owner))

            BoardSignalKind.SENT_TO_GRAVEYARD -> anchorOf(Anchor.grave(owner))
            BoardSignalKind.BANISHED -> anchorOf(Anchor.banish(owner))
            BoardSignalKind.RETURNED ->
                if (signal.text.contains("デッキ")) anchorOf(Anchor.deck(owner)) else anchorOf(Anchor.hand(owner))

            BoardSignalKind.DAMAGE, BoardSignalKind.RECOVER ->
                anchorOf(Anchor.lp(owner)) ?: anchorOf(Anchor.panel(owner))

            BoardSignalKind.DRAW -> anchorOf(Anchor.hand(owner))
            BoardSignalKind.TURN_START, BoardSignalKind.ACTIVATED -> anchorOf(Anchor.CENTER)
            else -> null
        }
        return FxPlay(signal, durationOf(signal.kind), from, to, targetSpot)
    }

    companion object {
        /** 盤面ごと揺らすダメージの大きさ。 */
        const val BIG_HIT = 2000

        /** 演出がどこまで進めば、次の操作を受け付けてよいか。 */
        const val SETTLE_AT = 0.6f

        fun durationOf(kind: BoardSignalKind): Int = when (kind) {
            BoardSignalKind.TURN_START -> 1400
            BoardSignalKind.SUMMONED -> 850
            BoardSignalKind.ACTIVATED -> 1150
            BoardSignalKind.ATTACK -> 760
            BoardSignalKind.TARGETED -> 620
            BoardSignalKind.DESTROYED -> 760
            BoardSignalKind.SENT_TO_GRAVEYARD -> 620
            BoardSignalKind.BANISHED -> 760
            BoardSignalKind.RETURNED -> 520
            BoardSignalKind.VANISHED -> 520
            BoardSignalKind.DAMAGE -> 1000
            BoardSignalKind.RECOVER -> 950
            BoardSignalKind.DRAW -> 460
        }

        /** 次の演出を始めるまでの間。演出どうしを少し重ねて、流れるように見せる。 */
        fun gapOf(kind: BoardSignalKind): Int = when (kind) {
            BoardSignalKind.TURN_START -> 1150
            BoardSignalKind.SUMMONED -> 430
            BoardSignalKind.ACTIVATED -> 780
            BoardSignalKind.ATTACK -> 330
            BoardSignalKind.TARGETED -> 200
            BoardSignalKind.DESTROYED -> 280
            BoardSignalKind.SENT_TO_GRAVEYARD -> 220
            BoardSignalKind.BANISHED -> 240
            BoardSignalKind.RETURNED -> 200
            BoardSignalKind.VANISHED -> 180
            BoardSignalKind.DAMAGE -> 380
            BoardSignalKind.RECOVER -> 300
            BoardSignalKind.DRAW -> 130
        }
    }
}

/** 画面のフレームで [ms] ミリ秒待つ。 */
private suspend fun awaitMillis(ms: Long) {
    if (ms <= 0) return
    val start = withFrameMillis { it }
    while (withFrameMillis { it } - start < ms) {
        // フレームごとに経過を見る。
    }
}

// ===========================================================================
// 演出の層
// ===========================================================================

/**
 * 盤面の上に重ねる演出の層。盤面と同じ大きさで置く。
 *
 * [cardLook] は、そのカードを誰から見て表にするか（相手の伏せカードは裏）を返す。
 */
@Composable
fun DuelFxLayer(
    fx: DuelFx,
    state: GameState,
    bottomIndex: Int,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { fx.origin = it.positionInWindow() }
    ) {
        // まだ演出の番が来ていない、場を離れたカードの残像。
        fx.ghosts().forEach { (card, spot) ->
            key("ghost-${card.uid}") {
                AtRect(spot.rect) { SlotCard(card, spot) }
            }
        }
        fx.playing.forEach { play ->
            key(play.signal.id) {
                FxView(play, state, bottomIndex)
            }
        }
    }
}

/** 演出の座標系で [rect] の位置と大きさに置く。 */
@Composable
private fun AtRect(
    rect: Rect,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val density = LocalDensity.current
    // 位置と大きさを先に決めてから [modifier]（回転・拡大など）を掛ける。
    // 逆にすると、回転や拡大の中心がカードではなく画面の左上になってしまう。
    Box(
        Modifier
            .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
            .then(modifier),
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
private fun FxView(play: FxPlay, state: GameState, bottomIndex: Int) {
    when (play.signal.kind) {
        BoardSignalKind.TURN_START -> TurnBanner(play, state, bottomIndex)
        BoardSignalKind.SUMMONED -> SummonFx(play)
        BoardSignalKind.ACTIVATED -> ActivateFx(play, state)
        BoardSignalKind.ATTACK -> AttackFx(play)
        BoardSignalKind.TARGETED -> TargetFx(play)
        BoardSignalKind.DESTROYED -> ShatterFx(play)
        BoardSignalKind.SENT_TO_GRAVEYARD -> SinkFx(play)
        BoardSignalKind.BANISHED -> BanishFx(play)
        BoardSignalKind.RETURNED, BoardSignalKind.VANISHED -> DepartFx(play)
        BoardSignalKind.DAMAGE -> LifeFx(play, damage = true)
        BoardSignalKind.RECOVER -> LifeFx(play, damage = false)
        BoardSignalKind.DRAW -> DrawFx(play)
    }
}

// ---------------------------------------------------------------------------
// 時間の曲線
// ---------------------------------------------------------------------------

/** [t] の [a]〜[b] の区間を 0〜1 に引き伸ばす。 */
private fun seg(t: Float, a: Float, b: Float): Float = ((t - a) / (b - a)).coerceIn(0f, 1f)

private fun easeOut(x: Float): Float = 1f - (1f - x) * (1f - x) * (1f - x)
private fun easeIn(x: Float): Float = x * x * x
private fun easeInOut(x: Float): Float =
    if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).let { it * it * it } / 2f

/** 0 → 1 → 0 の山。[rise] までに上がりきり、[fall] から下がる。 */
private fun envelope(t: Float, rise: Float, fall: Float): Float = when {
    t < rise -> t / rise
    t > fall -> ((1f - t) / (1f - fall)).coerceAtLeast(0f)
    else -> 1f
}

private fun kindTint(card: CardInstance?): Color = card?.let { kindColor(it.card.kind) } ?: Accent

// ---------------------------------------------------------------------------
// 召喚：光の柱が立ち、その中からカードが現れる。
// ---------------------------------------------------------------------------

@Composable
private fun SummonFx(play: FxPlay) {
    val spot = play.from ?: return
    val card = play.signal.card ?: return
    val tint = kindTint(card).lighten(0.25f)
    val rect = spot.rect

    Canvas(Modifier.fillMaxSize()) {
        val t = play.progress.value
        val beam = envelope(t, 0.18f, 0.6f)
        val cx = rect.center.x
        val beamWidth = rect.width * (0.5f + 0.9f * beam)
        val beamTop = rect.top - rect.height * 2.2f * beam
        drawRect(
            Brush.verticalGradient(
                listOf(Color.Transparent, tint.copy(alpha = 0.55f * beam), Color.White.copy(alpha = 0.8f * beam)),
                startY = beamTop,
                endY = rect.bottom
            ),
            topLeft = Offset(cx - beamWidth / 2, beamTop),
            size = Size(beamWidth, rect.bottom - beamTop)
        )
        // 足もとから広がる輪。
        val ring = seg(t, 0.3f, 0.95f)
        if (ring > 0f) {
            val radius = max(rect.width, rect.height) * (0.45f + ring * 0.9f)
            drawCircle(
                tint.copy(alpha = (1f - ring) * 0.9f),
                radius,
                rect.center,
                style = Stroke(width = 6f * (1f - ring) + 1f)
            )
        }
        sparks(rect.center, max(rect.width, rect.height), seg(t, 0.35f, 1f), tint, count = 12, seed = play.signal.id)
    }
    AtRect(
        rect,
        Modifier.graphicsLayer {
            val t = play.progress.value
            val appear = easeOut(seg(t, 0.22f, 0.62f))
            alpha = appear
            val scale = 1.45f - 0.45f * appear
            scaleX = scale
            scaleY = scale
        }
    ) { SlotCard(card, spot) }
}

// ---------------------------------------------------------------------------
// 発動：カードが光り、盤面の中央をカードの帯が横切る。
// ---------------------------------------------------------------------------

@Composable
private fun ActivateFx(play: FxPlay, state: GameState) {
    val card = play.signal.card ?: return
    val tint = kindTint(card)
    val who = play.signal.playerIndex?.let { state.players.getOrNull(it)?.name }.orEmpty()

    // 元の場所で2回脈打つ光。
    play.from?.let { spot ->
        Canvas(Modifier.fillMaxSize()) {
            val t = play.progress.value
            for (wave in 0..1) {
                val w = seg(t, wave * 0.22f, 0.45f + wave * 0.22f)
                if (w <= 0f || w >= 1f) continue
                val grow = spot.rect.inflate(spot.rect.width * 0.35f * w)
                drawRoundRect(
                    tint.lighten(0.3f).copy(alpha = (1f - w) * 0.9f),
                    topLeft = grow.topLeft,
                    size = grow.size,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f, 12f),
                    style = Stroke(width = 5f * (1f - w) + 1.5f)
                )
            }
        }
    }

    // 中央を横切る帯。
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val bandHeight = with(LocalDensity.current) { 104.dp.toPx() }
        // 盤面の中央（フェイズの帯）に重ねる。
        val centerY = play.to?.center?.y ?: (height * 0.5f)
        Box(
            Modifier
                .fillMaxWidth()
                .height(with(LocalDensity.current) { bandHeight.toDp() })
                .graphicsLayer {
                    val t = play.progress.value
                    translationY = centerY - bandHeight / 2
                    val enter = easeOut(seg(t, 0f, 0.16f))
                    val leave = easeIn(seg(t, 0.8f, 1f))
                    translationX = (1f - enter) * width - leave * width
                    alpha = min(1f, enter * 1.4f) * (1f - leave * 0.6f)
                }
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            tint.darken(0.55f).copy(alpha = 0.94f),
                            tint.darken(0.35f).copy(alpha = 0.96f),
                            tint.darken(0.55f).copy(alpha = 0.94f),
                            Color.Transparent
                        )
                    )
                )
        ) {
            // 帯の上下の光る縁。
            Canvas(Modifier.fillMaxSize()) {
                val edge = tint.lighten(0.45f)
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Transparent, edge, Color.Transparent)),
                    size = Size(size.width, 3f)
                )
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Transparent, edge, Color.Transparent)),
                    topLeft = Offset(0f, size.height - 3f),
                    size = Size(size.width, 3f)
                )
                // 帯の中を走る光の筋。
                val t = play.progress.value
                val sweep = seg(t, 0.12f, 0.7f)
                val x = size.width * (sweep * 1.4f - 0.2f)
                drawRect(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.22f), Color.Transparent),
                        startX = x - 80f,
                        endX = x + 80f
                    )
                )
            }
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                CardFace(card.card, Modifier.size(width = 60.dp, height = 84.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (card.card.kind == CardKind.MONSTER) "効果発動" else "${card.card.kind.label}カード発動",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = tint.lighten(0.5f)
                    )
                    Text(
                        card.card.name,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(shadow = Shadow(tint, Offset(0f, 0f), 12f))
                    )
                    if (who.isNotEmpty()) {
                        Text(who, fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 攻撃：攻撃するカードが相手に飛び込み、斬撃が走る。
// ---------------------------------------------------------------------------

@Composable
private fun AttackFx(play: FxPlay) {
    val spot = play.from ?: return
    val card = play.signal.card ?: return
    val to = play.to ?: return
    val start = spot.rect.center
    val aim = to.center
    val direct = play.targetSpot == null

    Canvas(Modifier.fillMaxSize()) {
        val t = play.progress.value
        // 飛び込む軌跡。
        val dash = seg(t, 0f, 0.42f)
        if (dash in 0.01f..0.99f) {
            val head = lerp(start, aim, easeIn(dash) * 0.86f)
            drawLine(
                Brush.linearGradient(listOf(Color.Transparent, Danger.copy(alpha = 0.8f)), start, head),
                start,
                head,
                strokeWidth = 10f * (1f - dash) + 3f
            )
        }
        // 当たった瞬間の斬撃と火花。
        val hit = seg(t, 0.4f, 0.8f)
        if (hit > 0f && hit < 1f) {
            // LP の欄のような横長の的でも、斬撃がはみ出しすぎないよう大きさを抑える。
            val size = min(max(to.width, to.height), 110.dp.toPx())
            val reach = size * (0.5f + hit * 0.5f)
            val fade = 1f - hit
            val slash = Color.White.copy(alpha = fade)
            drawLine(slash, aim + Offset(-reach, -reach * 0.8f), aim + Offset(reach, reach * 0.8f), 7f * fade + 2f)
            drawLine(
                Danger.lighten(0.3f).copy(alpha = fade),
                aim + Offset(reach, -reach * 0.8f),
                aim + Offset(-reach, reach * 0.8f),
                5f * fade + 2f
            )
            drawCircle(
                Brush.radialGradient(listOf(Color.White.copy(alpha = fade * 0.9f), Color.Transparent), aim, reach),
                reach,
                aim
            )
            sparks(aim, reach * 1.4f, hit, if (direct) Danger else Gold, count = 14, seed = play.signal.id)
        }
    }
    AtRect(
        spot.rect,
        Modifier.graphicsLayer {
            val t = play.progress.value
            val offset = when {
                t < 0.42f -> easeIn(seg(t, 0f, 0.42f)) * 0.86f
                t < 0.52f -> 0.86f
                else -> 0.86f * (1f - easeOut(seg(t, 0.52f, 1f)))
            }
            translationX = (aim.x - start.x) * offset
            translationY = (aim.y - start.y) * offset
            val lift = envelope(t, 0.2f, 0.7f)
            scaleX = 1f + 0.18f * lift
            scaleY = 1f + 0.18f * lift
            shadowElevation = 16f * lift
        }
    ) { SlotCard(card, spot) }
}

// ---------------------------------------------------------------------------
// 対象に取る：照準が絞られていく。
// ---------------------------------------------------------------------------

@Composable
private fun TargetFx(play: FxPlay) {
    val spot = play.from ?: return
    Canvas(Modifier.fillMaxSize()) {
        val t = play.progress.value
        val close = easeOut(seg(t, 0f, 0.55f))
        val alpha = envelope(t, 0.15f, 0.7f)
        val rect = spot.rect.inflate(spot.rect.width * 0.55f * (1f - close) + 4f)
        val arm = min(rect.width, rect.height) * 0.28f
        val color = Accent.lighten(0.3f).copy(alpha = alpha)
        rotate((1f - close) * 45f, rect.center) {
            corners(rect, arm, color, 4f)
        }
        drawCircle(color.copy(alpha = alpha * 0.8f), 5f * close + 1f, rect.center)
    }
}

private fun DrawScope.corners(rect: Rect, arm: Float, color: Color, width: Float) {
    val (l, tp, r, b) = listOf(rect.left, rect.top, rect.right, rect.bottom)
    drawLine(color, Offset(l, tp), Offset(l + arm, tp), width)
    drawLine(color, Offset(l, tp), Offset(l, tp + arm), width)
    drawLine(color, Offset(r, tp), Offset(r - arm, tp), width)
    drawLine(color, Offset(r, tp), Offset(r, tp + arm), width)
    drawLine(color, Offset(l, b), Offset(l + arm, b), width)
    drawLine(color, Offset(l, b), Offset(l, b - arm), width)
    drawLine(color, Offset(r, b), Offset(r - arm, b), width)
    drawLine(color, Offset(r, b), Offset(r, b - arm), width)
}

// ---------------------------------------------------------------------------
// 破壊：カードが砕けて飛び散る。
// ---------------------------------------------------------------------------

/** カードを割る三角形。中心を頂点に、縁を2つずつ区切る。 */
private val shardTriangles: List<List<Offset>> = run {
    val c = Offset(0.5f, 0.48f)
    val rim = listOf(
        Offset(0f, 0f), Offset(0.5f, 0f), Offset(1f, 0f), Offset(1f, 0.5f),
        Offset(1f, 1f), Offset(0.5f, 1f), Offset(0f, 1f), Offset(0f, 0.5f)
    )
    rim.indices.map { i -> listOf(c, rim[i], rim[(i + 1) % rim.size]) }
}

@Composable
private fun ShatterFx(play: FxPlay) {
    val spot = play.from
    val card = play.signal.card
    if (spot == null || card == null) {
        FallbackBurst(play, Danger)
        return
    }
    val rect = spot.rect
    // 先に白く光り、そのあと割れて飛ぶ。
    shardTriangles.forEachIndexed { index, tri ->
        val shape = remember(index) {
            GenericShape { size, _ ->
                tri.forEachIndexed { i, p ->
                    if (i == 0) moveTo(p.x * size.width, p.y * size.height)
                    else lineTo(p.x * size.width, p.y * size.height)
                }
                close()
            }
        }
        val mid = Offset((tri[0].x + tri[1].x + tri[2].x) / 3f - 0.5f, (tri[0].y + tri[1].y + tri[2].y) / 3f - 0.48f)
        val spin = if (index % 2 == 0) 1f else -1f
        AtRect(
            rect,
            Modifier.graphicsLayer {
                val t = play.progress.value
                val fly = easeOut(seg(t, 0.14f, 1f))
                translationX = mid.x * rect.width * 2.6f * fly
                translationY = mid.y * rect.height * 2.2f * fly + rect.height * 0.5f * fly * fly
                rotationZ = spin * 110f * fly * (1f + index % 3 * 0.3f)
                alpha = 1f - seg(t, 0.5f, 1f)
                clip = true
                this.shape = shape
            }
        ) { SlotCard(card, spot) }
    }
    Canvas(Modifier.fillMaxSize()) {
        val t = play.progress.value
        // 一瞬だけ白く光る。
        val flash = if (t < 0.06f) t / 0.06f else 1f - seg(t, 0.06f, 0.26f)
        if (flash > 0f) {
            drawRoundRect(
                Color.White.copy(alpha = 0.85f * flash),
                rect.topLeft,
                rect.size,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f, 10f)
            )
        }
        val ring = seg(t, 0.12f, 0.7f)
        if (ring in 0.001f..0.999f) {
            drawCircle(
                Danger.lighten(0.2f).copy(alpha = 1f - ring),
                max(rect.width, rect.height) * (0.4f + ring),
                rect.center,
                style = Stroke(5f * (1f - ring) + 1f)
            )
        }
        sparks(rect.center, max(rect.width, rect.height) * 1.3f, seg(t, 0.12f, 1f), Danger.lighten(0.2f), 16, play.signal.id)
    }
}

// ---------------------------------------------------------------------------
// 墓地へ：色を失って沈みながら、墓地の方へ吸い込まれる。
// ---------------------------------------------------------------------------

@Composable
private fun SinkFx(play: FxPlay) {
    val spot = play.from
    val card = play.signal.card
    if (spot == null || card == null) {
        FallbackBurst(play, Gold)
        return
    }
    val to = play.to
    AtRect(
        spot.rect,
        Modifier.graphicsLayer {
            val t = play.progress.value
            val go = easeIn(seg(t, 0.15f, 1f))
            if (to != null) {
                translationX = (to.center.x - spot.rect.center.x) * go
                translationY = (to.center.y - spot.rect.center.y) * go
            } else {
                translationY = spot.rect.height * 0.5f * go
            }
            val s = 1f - 0.7f * go
            scaleX = s
            scaleY = s
            alpha = 1f - seg(t, 0.55f, 1f)
        }
    ) {
        SlotCard(card, spot)
        // 色を抜いていく。
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = seg(play.progress.value, 0f, 0.4f) * 0.7f }
                .background(Color(0xFF3B3848), RoundedCornerShape(5.dp))
        )
    }
}

// ---------------------------------------------------------------------------
// 除外：紫の渦に吸い込まれて消える。
// ---------------------------------------------------------------------------

@Composable
private fun BanishFx(play: FxPlay) {
    val spot = play.from
    val card = play.signal.card
    val center = spot?.rect?.center ?: play.to?.center
    if (center != null) {
        val size = spot?.rect?.let { max(it.width, it.height) } ?: 80f
        Canvas(Modifier.fillMaxSize()) {
            val t = play.progress.value
            val a = envelope(t, 0.15f, 0.7f)
            val spin = t * 540f
            for (i in 0..2) {
                val r = size * (0.35f + i * 0.18f) * (1f - 0.5f * t)
                rotate(spin * (if (i % 2 == 0) 1f else -1.4f), center) {
                    drawArc(
                        Accent.lighten(0.2f * i).copy(alpha = a * (0.9f - i * 0.2f)),
                        startAngle = i * 60f,
                        sweepAngle = 200f,
                        useCenter = false,
                        topLeft = center - Offset(r, r),
                        size = Size(r * 2, r * 2),
                        style = Stroke(4f - i)
                    )
                }
            }
            rising(center, size, t, Accent.lighten(0.4f), 14, play.signal.id)
        }
    }
    if (spot != null && card != null) {
        AtRect(
            spot.rect,
            Modifier.graphicsLayer {
                val t = play.progress.value
                val go = easeIn(seg(t, 0f, 0.8f))
                scaleX = 1f - go
                scaleY = 1f - go
                rotationZ = go * 200f
                alpha = 1f - seg(t, 0.4f, 0.8f)
            }
        ) { SlotCard(card, spot) }
    }
}

// ---------------------------------------------------------------------------
// 手札・デッキへ戻る、トークンが消える。
// ---------------------------------------------------------------------------

@Composable
private fun DepartFx(play: FxPlay) {
    val spot = play.from ?: return
    val card = play.signal.card ?: return
    val to = play.to
    val vanish = play.signal.kind == BoardSignalKind.VANISHED
    AtRect(
        spot.rect,
        Modifier.graphicsLayer {
            val t = play.progress.value
            val go = easeInOut(t)
            if (to != null && !vanish) {
                translationX = (to.center.x - spot.rect.center.x) * go
                translationY = (to.center.y - spot.rect.center.y) * go
                scaleX = 1f - 0.5f * go
                scaleY = 1f - 0.5f * go
            } else {
                scaleX = 1f + 0.2f * go
                scaleY = 1f + 0.2f * go
            }
            alpha = 1f - seg(t, 0.45f, 1f)
        }
    ) { SlotCard(card, spot) }
    if (vanish) {
        Canvas(Modifier.fillMaxSize()) {
            sparks(spot.rect.center, max(spot.rect.width, spot.rect.height), play.progress.value, Glint, 12, play.signal.id)
        }
    }
}

// ---------------------------------------------------------------------------
// ダメージ・回復：LP の欄が揺れ、数字が浮かぶ。
// ---------------------------------------------------------------------------

private val Glint = Color(0xFFF3EEFF)

@Composable
private fun LifeFx(play: FxPlay, damage: Boolean) {
    val area = play.to ?: return
    val color = if (damage) Danger else Boost
    val big = damage && play.signal.amount >= DuelFx.BIG_HIT

    Canvas(Modifier.fillMaxSize()) {
        val t = play.progress.value
        if (damage) {
            val flash = envelope(t, 0.05f, 0.35f)
            drawRoundRect(
                color.copy(alpha = 0.45f * flash),
                topLeft = area.topLeft,
                size = area.size,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(14f, 14f)
            )
            if (big) {
                // 画面の縁を赤く染める。
                val edge = envelope(t, 0.05f, 0.5f) * 0.55f
                drawRect(
                    Brush.radialGradient(
                        listOf(Color.Transparent, color.copy(alpha = edge)),
                        center = Offset(size.width / 2, size.height / 2),
                        radius = max(size.width, size.height) * 0.75f
                    )
                )
            }
            sparks(area.center, area.height * 1.6f, seg(t, 0f, 0.6f), color.lighten(0.2f), 10, play.signal.id)
        } else {
            val glow = envelope(t, 0.1f, 0.6f)
            drawRoundRect(
                color.copy(alpha = 0.3f * glow),
                topLeft = area.topLeft,
                size = area.size,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(14f, 14f)
            )
            rising(area.center, area.width * 0.5f, t, color.lighten(0.3f), 18, play.signal.id)
        }
    }

    // 浮かぶ数字。上の欄なら盤面の側（下）へ、下の欄なら上へ浮かせて、画面の外に出さない。
    val density = LocalDensity.current
    val sign = if (damage) "−" else "+"
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val downward = area.center.y < constraints.maxHeight / 2f
    Box(
        Modifier
            .offset {
                val y = if (downward) area.bottom + with(density) { 2.dp.toPx() }
                else area.top - with(density) { 44.dp.toPx() }
                IntOffset(
                    (area.center.x - with(density) { 90.dp.toPx() }).roundToInt(),
                    y.roundToInt()
                )
            }
            .width(180.dp)
            .graphicsLayer {
                val t = play.progress.value
                val pop = easeOut(seg(t, 0f, 0.18f))
                val s = if (t < 0.18f) 0.4f + 0.9f * pop else 1.3f - 0.3f * seg(t, 0.18f, 0.4f)
                scaleX = s
                scaleY = s
                val drift = with(density) { 26.dp.toPx() } * easeOut(seg(t, 0.1f, 1f))
                translationY = if (downward) drift else -drift
                alpha = 1f - seg(t, 0.72f, 1f)
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            "$sign${play.signal.amount}",
            fontSize = if (big) 38.sp else 30.sp,
            fontWeight = FontWeight.Black,
            color = color.lighten(0.15f),
            style = TextStyle(shadow = Shadow(Color.Black, Offset(0f, 3f), 8f))
        )
    }
    }
}

// ---------------------------------------------------------------------------
// ドロー：デッキから手札へカードが飛ぶ。
// ---------------------------------------------------------------------------

@Composable
private fun DrawFx(play: FxPlay) {
    val to = play.to ?: return
    // デッキの位置は演出を作った時点では分からないので、手札の枠の外側から飛ばす。
    val owner = play.signal.playerIndex ?: 0
    val density = LocalDensity.current
    val w = with(density) { 40.dp.toPx() }
    val h = with(density) { 56.dp.toPx() }
    val start = Offset(to.right - w, to.top - h * 1.2f)
    val end = Offset(to.center.x - w / 2, to.center.y - h / 2)
    Box(
        Modifier
            .offset { IntOffset(start.x.roundToInt(), start.y.roundToInt()) }
            .size(40.dp, 56.dp)
            .graphicsLayer {
                val t = easeOut(play.progress.value)
                translationX = (end.x - start.x) * t
                translationY = (end.y - start.y) * t
                rotationZ = (1f - t) * (if (owner == 0) -25f else 25f)
                alpha = 1f - seg(play.progress.value, 0.75f, 1f)
            }
    ) { CardBack(Modifier.fillMaxSize()) }
}

// ---------------------------------------------------------------------------
// ターンの始まり：帯が盤面を横切る。
// ---------------------------------------------------------------------------

@Composable
private fun TurnBanner(play: FxPlay, state: GameState, bottomIndex: Int) {
    val playerIndex = play.signal.playerIndex ?: return
    val mine = playerIndex == bottomIndex
    val name = state.players.getOrNull(playerIndex)?.name.orEmpty()
    val color = if (mine) Gold else Danger
    val turn = play.signal.amount

    Canvas(Modifier.fillMaxSize()) {
        val t = play.progress.value
        drawRect(Color.Black.copy(alpha = 0.45f * envelope(t, 0.12f, 0.82f)))
    }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val width = constraints.maxWidth.toFloat()
        Box(
            Modifier
                .fillMaxWidth()
                .height(92.dp)
                .graphicsLayer {
                    val t = play.progress.value
                    val enter = easeOut(seg(t, 0f, 0.2f))
                    val leave = easeIn(seg(t, 0.8f, 1f))
                    translationX = -(1f - enter) * width + leave * width
                }
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            color.darken(0.6f).copy(alpha = 0.95f),
                            color.darken(0.45f).copy(alpha = 0.95f),
                            color.darken(0.6f).copy(alpha = 0.95f),
                            Color.Transparent
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val edge = color.lighten(0.3f)
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Transparent, edge, Color.Transparent)),
                    size = Size(size.width, 3f)
                )
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Transparent, edge, Color.Transparent)),
                    topLeft = Offset(0f, size.height - 3f),
                    size = Size(size.width, 3f)
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "TURN $turn",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = color.lighten(0.4f),
                    letterSpacing = 4.sp
                )
                Text(
                    "${name}のターン",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    style = TextStyle(shadow = Shadow(color, Offset.Zero, 18f))
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 場所が分からないときの代わり。
// ---------------------------------------------------------------------------

@Composable
private fun FallbackBurst(play: FxPlay, color: Color) {
    val at = play.to?.center ?: return
    Canvas(Modifier.fillMaxSize()) {
        sparks(at, 70f, play.progress.value, color, 10, play.signal.id)
    }
}

// ---------------------------------------------------------------------------
// 粒子
// ---------------------------------------------------------------------------

/** 中心から放射状に飛ぶ火花。[seed] で毎回同じ散り方にする。 */
private fun DrawScope.sparks(center: Offset, reach: Float, t: Float, color: Color, count: Int, seed: Long) {
    if (t <= 0f || t >= 1f) return
    val rng = java.util.Random(seed * 7919)
    repeat(count) {
        val angle = rng.nextFloat() * 2 * PI
        val speed = 0.55f + rng.nextFloat() * 0.6f
        val d = reach * speed * easeOut(t)
        val p = center + Offset((cos(angle) * d).toFloat(), (sin(angle) * d).toFloat() + reach * 0.25f * t * t)
        val r = (2.5f + rng.nextFloat() * 3.5f) * (1f - t)
        drawCircle(color.copy(alpha = (1f - t)), r, p)
    }
}

/** 下から立ちのぼる光の粒。 */
private fun DrawScope.rising(center: Offset, spread: Float, t: Float, color: Color, count: Int, seed: Long) {
    val rng = java.util.Random(seed * 104729)
    repeat(count) {
        val delay = rng.nextFloat() * 0.4f
        val local = seg(t, delay, delay + 0.6f)
        if (local <= 0f || local >= 1f) return@repeat
        val x = center.x + (rng.nextFloat() - 0.5f) * spread * 2f
        val y = center.y + spread * 0.4f - spread * 1.4f * easeOut(local)
        val r = (2f + rng.nextFloat() * 3f) * (1f - local * 0.6f)
        drawCircle(color.copy(alpha = 1f - local), r, Offset(x, y))
    }
}

private fun lerp(a: Offset, b: Offset, t: Float): Offset = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
