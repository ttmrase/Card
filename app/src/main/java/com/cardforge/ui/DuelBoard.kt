package com.cardforge.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.cardforge.game.CardInstance
import com.cardforge.game.GameEngine
import com.cardforge.game.PlayerState
import com.cardforge.model.CardKind
import com.cardforge.model.DeckRules
import com.cardforge.model.Phase
import com.cardforge.ui.theme.*
import kotlin.math.min

// ===========================================================================
// デュエルの盤面の部品
// ===========================================================================

/** カードの縦横比（幅 ÷ 高さ）。 */
const val CARD_ASPECT = 0.72f

/** レイアウトの位置をウィンドウ上の四角形にする。 */
fun LayoutCoordinates.windowRect(): Rect = Rect(positionInWindow(), size.toSize())

/** 盤面のカード枠の種類。空き枠に描く目印を変える。 */
enum class SlotKind { MONSTER, SPELL_TRAP }

/**
 * 枠いっぱいに収まるカード1枚。
 *
 * [sideways] が 1 に近いほど横向き（守備表示）になり、枠に収まるよう縮む。
 * 途中の値を渡せば、表示形式を変えたときに回って見える。
 */
@Composable
fun SlotCard(
    inst: CardInstance,
    faceUp: Boolean,
    sideways: Float,
    modifier: Modifier = Modifier,
    atk: Int? = null,
    def: Int? = null,
    decorate: (DrawScope.() -> Unit)? = null
) {
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val w = maxWidth
        val h = maxHeight
        val cardW = min(w.value, h.value * CARD_ASPECT)
        val cardH = cardW / CARD_ASPECT
        // 横にしたときに枠へ収まる倍率。
        val fit = min(1f, min(w.value / cardH, h.value / cardW))
        val scale = 1f + (fit - 1f) * sideways
        Box(
            Modifier
                .size(cardW.dp, cardH.dp)
                .graphicsLayer {
                    rotationZ = 90f * sideways
                    scaleX = scale
                    scaleY = scale
                }
                .then(if (decorate != null) Modifier.drawWithContent { drawContent(); decorate() } else Modifier)
        ) {
            if (faceUp) {
                CardFace(inst.card, Modifier.fillMaxSize(), atk = atk, def = def)
                // 自分の伏せカードは、中身が見えるよう裏面を薄く重ねる。
                if (inst.faceDown) {
                    CardBack(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = 0.6f }
                    )
                }
            } else {
                CardBack(Modifier.fillMaxSize())
            }
        }
    }
}

/** 演出で使う、記録しておいた見た目のままのカード。 */
@Composable
fun SlotCard(inst: CardInstance, spot: Spot) =
    SlotCard(inst, spot.faceUp, if (spot.rotated) 1f else 0f, atk = spot.atk, def = spot.def)

// ---------------------------------------------------------------------------
// 盤面のカード枠
// ---------------------------------------------------------------------------

/**
 * 枠の強調。攻撃できる相手、使えるカード、選んでいるカード。
 * 使えるカードは数が多くなりがちなので、細く控えめに光らせる。
 */
enum class SlotMark(val color: Color, val pulse: Boolean, val width: Float, val low: Float) {
    TARGET(Danger, true, 5f, 0.35f),
    USABLE(Gold, true, 3f, 0.2f),
    CHOSEN(Gold, false, 5f, 1f)
}

@Composable
fun FieldSlot(
    inst: CardInstance?,
    kind: SlotKind,
    faceUp: Boolean,
    fx: DuelFx,
    modifier: Modifier = Modifier,
    atk: Int? = null,
    def: Int? = null,
    mark: SlotMark? = null,
    onClick: (CardInstance) -> Unit = {},
    onLongClick: (CardInstance) -> Unit = onClick
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        EmptySlotFrame(kind)
        if (inst == null) return@Box

        val rotated = inst.card.kind == CardKind.MONSTER && inst.displayPosition.isDefense
        val sideways by animateFloatAsState(if (rotated) 1f else 0f, tween(380), label = "sideways")
        val hidden = fx.isHidden(inst)

        // 攻守が動いたら、上がれば緑・下がれば赤にひと光りさせる。
        val flash = remember(inst.uid) { Animatable(0f) }
        var lastAtk by remember(inst.uid) { mutableStateOf(atk) }
        var flashUp by remember(inst.uid) { mutableStateOf(true) }
        LaunchedEffect(atk) {
            val before = lastAtk
            lastAtk = atk
            if (before != null && atk != null && atk != before) {
                flashUp = atk > before
                flash.snapTo(1f)
                flash.animateTo(0f, tween(900))
            }
        }

        val pulse = if (mark?.pulse == true) {
            rememberInfiniteTransition(label = "mark").animateFloat(
                mark.low, if (mark == SlotMark.USABLE) 0.75f else 1f,
                infiniteRepeatable(tween(if (mark == SlotMark.USABLE) 1100 else 650), RepeatMode.Reverse),
                label = "pulse"
            )
        } else null

        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { fx.place(inst, it.windowRect(), faceUp, rotated, atk, def) }
                .graphicsLayer {
                    alpha = when {
                        hidden -> 0f
                        inst.hasAttacked -> 0.72f
                        else -> 1f
                    }
                }
                .tapOrHold(onClick = { onClick(inst) }, onLongClick = { onLongClick(inst) })
        ) {
            SlotCard(
                inst = inst,
                faceUp = faceUp,
                sideways = sideways,
                atk = atk,
                def = def,
                decorate = {
                    val f = flash.value
                    if (f > 0f) {
                        drawRoundRect(
                            (if (flashUp) Boost else Danger).copy(alpha = 0.45f * f),
                            cornerRadius = CornerRadius(8f, 8f)
                        )
                    }
                    if (mark != null) {
                        val a = pulse?.value ?: 1f
                        drawRoundRect(
                            mark.color.copy(alpha = a),
                            topLeft = Offset(-3f, -3f),
                            size = Size(size.width + 6f, size.height + 6f),
                            cornerRadius = CornerRadius(10f, 10f),
                            style = Stroke(width = mark.width)
                        )
                    }
                }
            )
            val counters = inst.counterCount(null)
            if (counters > 0) {
                Text(
                    counters.toString(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Accent)
                )
            }
        }
    }
}

/** 空いている枠。種類ごとの紋章をうっすら描く。 */
@Composable
private fun EmptySlotFrame(kind: SlotKind) {
    Canvas(Modifier.fillMaxSize()) {
        val cardW = min(size.width, size.height * CARD_ASPECT)
        val cardH = cardW / CARD_ASPECT
        val topLeft = Offset((size.width - cardW) / 2, (size.height - cardH) / 2)
        val frame = if (kind == SlotKind.MONSTER) MonsterColor else SpellColor
        drawRoundRect(
            Color.White.copy(alpha = 0.035f),
            topLeft = topLeft,
            size = Size(cardW, cardH),
            cornerRadius = CornerRadius(10f, 10f)
        )
        drawRoundRect(
            frame.copy(alpha = 0.28f),
            topLeft = topLeft,
            size = Size(cardW, cardH),
            cornerRadius = CornerRadius(10f, 10f),
            style = Stroke(width = 1.5f)
        )
        drawEmblem(
            if (kind == SlotKind.MONSTER) CardKind.MONSTER else CardKind.SPELL,
            Offset(topLeft.x + cardW / 2, topLeft.y + cardH / 2),
            cardW * 0.2f,
            frame.copy(alpha = 0.22f)
        )
    }
}

/** モンスターゾーンか魔法・罠ゾーンの1列。 */
@Composable
fun ZoneRow(
    zones: List<CardInstance?>,
    kind: SlotKind,
    fx: DuelFx,
    isRevealed: (CardInstance) -> Boolean,
    modifier: Modifier = Modifier,
    engine: GameEngine? = null,
    markOf: (CardInstance) -> SlotMark? = { null },
    onClick: (CardInstance) -> Unit,
    onLongClick: (CardInstance) -> Unit = onClick
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        zones.forEach { card ->
            FieldSlot(
                inst = card,
                kind = kind,
                faceUp = card?.let(isRevealed) ?: false,
                fx = fx,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                atk = card?.let { engine?.atkOf(it) },
                def = card?.let { engine?.defOf(it) },
                mark = card?.let(markOf),
                onClick = onClick,
                onLongClick = onLongClick
            )
        }
    }
}

// ---------------------------------------------------------------------------
// プレイヤーの欄
// ---------------------------------------------------------------------------

@Composable
fun PlayerPanel(
    player: PlayerState,
    isTurnPlayer: Boolean,
    thinking: Boolean,
    fx: DuelFx,
    onOpenZone: (String, List<CardInstance>) -> Unit,
    modifier: Modifier = Modifier
) {
    val index = player.index
    val life by animateIntAsState(fx.shownLife(index), tween(700), label = "life")
    val fraction = (life.toFloat() / DeckRules.STARTING_LIFE).coerceIn(0f, 1f)
    val lifeColor = when {
        fraction > 0.5f -> Boost
        fraction > 0.25f -> Gold
        else -> Danger
    }
    val shape = RoundedCornerShape(10.dp)

    Row(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { fx.anchor(Anchor.panel(index), it.windowRect()) }
            .graphicsLayer { translationX = fx.panelShake(index) }
            .clip(shape)
            .background(if (isTurnPlayer) Surface2 else Surface1)
            .border(if (isTurnPlayer) 1.5.dp else 0.5.dp, if (isTurnPlayer) Gold else Color.White.copy(alpha = 0.08f), shape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 名前の頭文字の丸。手番のときは金色。
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(if (isTurnPlayer) Gold else Surface2),
            contentAlignment = Alignment.Center
        ) {
            Text(
                player.name.take(1),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (isTurnPlayer) Ink else Color.White
            )
        }
        Column(
            Modifier
                .weight(1f)
                .onGloballyPositioned { fx.anchor(Anchor.lp(index), it.windowRect()) },
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    player.name,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isTurnPlayer) Gold else Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (thinking) {
                    Text("  思考中…", fontSize = 10.sp, color = Accent)
                }
                Spacer(Modifier.weight(1f))
                Text("LP ", fontSize = 10.sp, color = Color.White.copy(alpha = 0.6f))
                Text(life.toString(), fontSize = 17.sp, fontWeight = FontWeight.Black, color = Color.White)
            }
            // LP の残り。
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(5.dp)
            ) {
                drawRoundRect(Color.White.copy(alpha = 0.08f), cornerRadius = CornerRadius(size.height, size.height))
                drawRoundRect(
                    Brush.horizontalGradient(listOf(lifeColor.darken(0.2f), lifeColor.lighten(0.2f))),
                    size = Size(size.width * fraction, size.height),
                    cornerRadius = CornerRadius(size.height, size.height)
                )
            }
        }
        ZonePill("デッキ", player.deck.size, Modifier.onGloballyPositioned { fx.anchor(Anchor.deck(index), it.windowRect()) })
        ZonePill(
            "墓地",
            player.graveyard.size,
            Modifier
                .onGloballyPositioned { fx.anchor(Anchor.grave(index), it.windowRect()) }
                .clickable { onOpenZone("${player.name}の墓地", player.graveyard.toList()) },
            highlight = true
        )
        ZonePill(
            "除外",
            player.banished.size,
            Modifier
                .onGloballyPositioned { fx.anchor(Anchor.banish(index), it.windowRect()) }
                .clickable { onOpenZone("${player.name}の除外ゾーン", player.banished.toList()) },
            highlight = true
        )
    }
}

@Composable
private fun ZonePill(label: String, count: Int, modifier: Modifier = Modifier, highlight: Boolean = false) {
    Column(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, fontSize = 8.sp, color = if (highlight) Gold else Color.White.copy(alpha = 0.55f))
        Text(count.toString(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

// ---------------------------------------------------------------------------
// 手札
// ---------------------------------------------------------------------------

/** 相手の手札。裏向きに重ねて並べ、公開されたカードだけ表にする。 */
@Composable
fun OpponentHand(
    player: PlayerState,
    turn: Int,
    fx: DuelFx,
    onInspect: (CardInstance) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { fx.anchor(Anchor.hand(player.index), it.windowRect()) },
        horizontalArrangement = Arrangement.Center
    ) {
        val shown = player.hand.take(12)
        shown.forEachIndexed { i, card ->
            val revealed = card.isRevealed(turn)
            Box(
                Modifier
                    .offset(x = (-9 * i).dp)
                    .size(30.dp, 42.dp)
                    .onGloballyPositioned { fx.place(card, it.windowRect(), revealed, false) }
                    .graphicsLayer { rotationZ = (i - (shown.size - 1) / 2f) * 3f }
                    .then(if (revealed) Modifier.clickable { onInspect(card) } else Modifier)
            ) {
                if (revealed) CardFace(card.card, Modifier.fillMaxSize())
                else CardBack(Modifier.fillMaxSize())
            }
        }
        if (player.hand.size > shown.size) {
            Text("+${player.hand.size - shown.size}", fontSize = 10.sp, color = Color.White.copy(alpha = 0.6f))
        }
    }
}

/** 自分の手札。横に並べ、多いときは横にスクロールする。 */
@Composable
fun OwnHand(
    player: PlayerState,
    fx: DuelFx,
    engine: GameEngine,
    markOf: (CardInstance) -> SlotMark?,
    onClick: (CardInstance) -> Unit,
    onLongClick: (CardInstance) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { fx.anchor(Anchor.hand(player.index), it.windowRect()) },
        horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
    ) {
        items(player.hand, key = { it.uid }) { card ->
            val mark = markOf(card)
            val pulse = if (mark?.pulse == true) {
                rememberInfiniteTransition(label = "hand").animateFloat(
                    mark.low, 0.75f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "pulse"
                )
            } else null
            Box(
                Modifier
                    .size(width = 62.dp, height = 86.dp)
                    .onGloballyPositioned { fx.place(card, it.windowRect(), true, false) }
                    .graphicsLayer { alpha = if (fx.isHidden(card)) 0f else 1f }
                    .drawWithContent {
                        drawContent()
                        if (mark != null) {
                            drawRoundRect(
                                mark.color.copy(alpha = pulse?.value ?: 1f),
                                topLeft = Offset(-3f, -3f),
                                size = Size(size.width + 6f, size.height + 6f),
                                cornerRadius = CornerRadius(12f, 12f),
                                style = Stroke(width = mark.width)
                            )
                        }
                    }
                    .tapOrHold(onClick = { onClick(card) }, onLongClick = { onLongClick(card) })
            ) {
                CardFace(card.card, Modifier.fillMaxSize(), atk = engine.atkOf(card), def = engine.defOf(card))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 盤面の中央：フェイズの進み具合と操作
// ---------------------------------------------------------------------------

private val phaseShort = mapOf(
    Phase.DRAW to "DP",
    Phase.MAIN1 to "M1",
    Phase.BATTLE to "BP",
    Phase.MAIN2 to "M2",
    Phase.END to "EP"
)

@Composable
fun PhaseStrip(
    phase: Phase,
    turnOwner: String,
    interactive: Boolean,
    waiting: Boolean,
    attacking: Boolean,
    canAttackDirectly: Boolean,
    fx: DuelFx,
    onCancelAttack: () -> Unit,
    onDirectAttack: () -> Unit,
    onNextPhase: () -> Unit,
    onEndTurn: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { fx.anchor(Anchor.CENTER, it.windowRect()) }
            .clip(RoundedCornerShape(10.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(Surface1.copy(alpha = 0.9f), Surface2.copy(alpha = 0.95f), Surface1.copy(alpha = 0.9f))
                )
            )
            .border(0.5.dp, Gold.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (attacking) {
            Text(
                "攻撃対象を選択",
                color = Danger,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (canAttackDirectly) {
                CompactButton("直接攻撃", filled = true, color = Danger, onClick = onDirectAttack)
            }
            CompactButton("やめる", onClick = onCancelAttack)
            return@Row
        }

        // フェイズの並び。今のフェイズを金色で示す。
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Phase.all.forEach { p ->
                val current = p == phase
                val passed = p.ordinal < phase.ordinal
                Box(
                    Modifier
                        .size(width = 27.dp, height = 22.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(
                            when {
                                current -> Gold
                                passed -> Color.White.copy(alpha = 0.06f)
                                else -> Color.Transparent
                            }
                        )
                        .border(0.5.dp, if (current) Gold else Color.White.copy(alpha = 0.15f), RoundedCornerShape(5.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        phaseShort[p].orEmpty(),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            current -> Ink
                            passed -> Color.White.copy(alpha = 0.35f)
                            else -> Color.White.copy(alpha = 0.7f)
                        }
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (waiting) {
            Text(
                "${turnOwner}のターン",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1
            )
        } else {
            CompactButton("次のフェイズ", enabled = interactive, onClick = onNextPhase)
            CompactButton("ターン終了", filled = true, enabled = interactive, onClick = onEndTurn)
        }
    }
}

/** 盤面の中で使う小さなボタン。文字が折り返さないよう、余白を詰めてある。 */
@Composable
fun CompactButton(
    label: String,
    enabled: Boolean = true,
    filled: Boolean = false,
    color: Color = Accent,
    onClick: () -> Unit
) {
    val padding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
    val content: @Composable RowScope.() -> Unit = {
        Text(label, fontSize = 12.sp, maxLines = 1, softWrap = false, fontWeight = FontWeight.Bold)
    }
    if (filled) {
        Button(
            onClick = onClick,
            enabled = enabled,
            contentPadding = padding,
            colors = ButtonDefaults.buttonColors(containerColor = color),
            modifier = Modifier.height(32.dp),
            content = content
        )
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            contentPadding = padding,
            modifier = Modifier.height(32.dp),
            content = content
        )
    }
}

/** 盤面の下地。相手側は赤み、自分側は紫がかった色にし、中央に光の線を引く。 */
fun DrawScope.drawDuelMat() {
    drawRect(
        Brush.verticalGradient(
            0f to Color(0xFF1E1020),
            0.48f to Color(0xFF15121F),
            0.52f to Color(0xFF12121F),
            1f to Color(0xFF141029)
        )
    )
    val mid = size.height / 2
    drawRect(
        Brush.horizontalGradient(listOf(Color.Transparent, Gold.copy(alpha = 0.22f), Color.Transparent)),
        topLeft = Offset(0f, mid - 1f),
        size = Size(size.width, 2f)
    )
    // うっすらした円の紋様。
    drawCircle(Accent.copy(alpha = 0.05f), size.minDimension * 0.42f, Offset(size.width / 2, mid), style = Stroke(2f))
    drawCircle(Accent.copy(alpha = 0.035f), size.minDimension * 0.3f, Offset(size.width / 2, mid), style = Stroke(1.5f))
}
