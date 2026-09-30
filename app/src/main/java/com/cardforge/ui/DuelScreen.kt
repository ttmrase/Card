package com.cardforge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardforge.data.LibraryRepository
import com.cardforge.game.BoardSignal
import com.cardforge.game.BoardSignalKind
import com.cardforge.game.CardInstance
import com.cardforge.game.GameState
import com.cardforge.game.PlayerState
import com.cardforge.game.ownerIndexOf
import com.cardforge.model.CardKind
import com.cardforge.model.MasterData
import com.cardforge.model.Phase
import com.cardforge.text.EffectTextRenderer
import com.cardforge.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun DuelScreen(
    repository: LibraryRepository,
    config: DuelConfig,
    onExit: () -> Unit
) {
    val library = repository.library
    // 「もう一度」で数を進めると、新しいデュエルとして作り直す。
    var round by remember { mutableIntStateOf(0) }
    key(round) {
        val controller = remember { DuelController(library, config) }
        DuelTable(
            controller = controller,
            master = library.master,
            onExit = onExit,
            onRematch = { round++ }
        )
    }
}

/**
 * デュエルの画面本体。盤面・演出・ダイアログをまとめる。
 * 作り済みの [controller] を受け取るので、決まった盤面を描いて確かめることもできる。
 */
@Composable
fun DuelTable(
    controller: DuelController,
    master: MasterData,
    onExit: () -> Unit,
    onRematch: () -> Unit
) {
    val state = controller.state
    val engine = controller.engine
    val config = controller.config
    val scope = rememberCoroutineScope()

    val fx = remember(controller) { DuelFx(state) }
    val sounds = rememberDuelSounds()
    var soundOn by remember { mutableStateOf(true) }
    var caption by remember { mutableStateOf<BoardSignal?>(null) }

    // 盤面の出来事を演出にする。音は演出の始まりに合わせて鳴らす。
    LaunchedEffect(fx) {
        fx.run { signal ->
            if (soundOn) sounds.play(signal.kind)
            if (signal.kind !in quietKinds) caption = signal
        }
    }
    // AI は演出が追いつくのを待ってから次の手を打つ。
    SideEffect {
        controller.awaitPresentation = {
            withTimeoutOrNull(12_000) { snapshotFlow { fx.idle }.first { it } }
        }
    }
    // 相手の手番になったら AI に打たせる。
    LaunchedEffect(state.turnPlayerIndex) {
        if (config.versusAi && state.turnPlayerIndex == controller.aiIndex && !state.finished) {
            controller.runAiTurn()
        }
    }

    val idle by remember(fx) { derivedStateOf { fx.idle } }
    val settled by remember(fx) { derivedStateOf { fx.settled } }

    // 2人で遊ぶときは手番の側を下に出す。入れ替えは演出が済んでから。
    var hotseatBottom by remember { mutableIntStateOf(state.turnPlayerIndex) }
    LaunchedEffect(idle, state.turnPlayerIndex) {
        if (idle) hotseatBottom = state.turnPlayerIndex
    }
    val bottomIndex = if (config.versusAi) controller.humanIndex else hotseatBottom
    val bottom = state.players[bottomIndex]
    val top = state.players[1 - bottomIndex]

    val interactive = state.turnPlayerIndex == bottomIndex &&
        !controller.aiThinking &&
        controller.pendingPrompt == null &&
        !state.finished &&
        settled

    var selected by remember { mutableStateOf<CardInstance?>(null) }
    var detail by remember { mutableStateOf<CardInstance?>(null) }
    var attacker by remember { mutableStateOf<CardInstance?>(null) }
    var showLog by remember { mutableStateOf(false) }
    var zoneViewer by remember { mutableStateOf<Pair<String, List<CardInstance>>?>(null) }
    var showSurrender by remember { mutableStateOf(false) }

    // 攻撃を選んでいる途中でフェイズや手番が変わったら取り消す。
    LaunchedEffect(state.phase, state.turnPlayerIndex) { attacker = null }

    // 端末の「戻る」でいきなりデュエルを抜けないようにする。
    // 攻撃の相手を選んでいる途中なら、まずそれを取り消す。
    BackHandler {
        if (attacker != null) attacker = null else showSurrender = true
    }

    // いま使えるカード。操作できるときだけ光らせる。
    val usable: Set<CardInstance> = if (interactive) usableCards(controller, bottom) else emptySet()
    val attackTargets: List<CardInstance> = if (attacker != null) engine.attackTargets() else emptyList()

    Box(
        Modifier
            .fillMaxSize()
            .background(Ink)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            DuelHeader(
                turn = state.turn,
                turnOwner = state.turnPlayer.name,
                mine = state.turnPlayerIndex == bottomIndex,
                soundOn = soundOn,
                onToggleSound = { soundOn = !soundOn },
                onShowLog = { showLog = true },
                onBack = { showSurrender = true }
            )

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .drawBehind { drawDuelMat() }
                        .graphicsLayer {
                            val shake = fx.boardShake()
                            translationX = shake
                            translationY = shake * 0.4f
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    PlayerPanel(
                        player = top,
                        isTurnPlayer = state.turnPlayerIndex == top.index,
                        thinking = controller.aiThinking && top.index == controller.aiIndex,
                        fx = fx,
                        onOpenZone = { title, cards -> zoneViewer = title to cards }
                    )
                    OpponentHand(
                        player = top,
                        turn = state.turn,
                        fx = fx,
                        onInspect = { detail = it },
                        modifier = Modifier.height(44.dp)
                    )
                    ZoneRow(
                        zones = top.spellTrapZones,
                        kind = SlotKind.SPELL_TRAP,
                        fx = fx,
                        isRevealed = { !it.faceDown },
                        modifier = Modifier.weight(1f),
                        onClick = { detail = it }
                    )
                    ZoneRow(
                        zones = top.monsterZones,
                        kind = SlotKind.MONSTER,
                        fx = fx,
                        isRevealed = { !it.faceDown },
                        modifier = Modifier.weight(1f),
                        engine = engine,
                        markOf = { card -> if (attackTargets.any { it === card }) SlotMark.TARGET else null },
                        onClick = { card ->
                            val current = attacker
                            if (current != null && attackTargets.any { it === card }) {
                                attacker = null
                                scope.launch { engine.declareAttack(current, card) }
                            } else {
                                detail = card
                            }
                        },
                        onLongClick = { detail = it }
                    )

                    PhaseStrip(
                        phase = state.phase,
                        turnOwner = state.turnPlayer.name,
                        interactive = interactive,
                        waiting = state.turnPlayerIndex != bottomIndex,
                        attacking = attacker != null,
                        canAttackDirectly = attacker?.let { engine.canAttackDirectlyWith(it) } ?: false,
                        fx = fx,
                        onCancelAttack = { attacker = null },
                        onDirectAttack = {
                            val current = attacker
                            attacker = null
                            if (current != null) scope.launch { engine.declareAttack(current, null) }
                        },
                        onNextPhase = { scope.launch { engine.advancePhase() } },
                        onEndTurn = { scope.launch { engine.endTurnImmediately() } }
                    )

                    ZoneRow(
                        zones = bottom.monsterZones,
                        kind = SlotKind.MONSTER,
                        fx = fx,
                        isRevealed = { true },
                        modifier = Modifier.weight(1f),
                        engine = engine,
                        markOf = { card ->
                            when {
                                card === attacker -> SlotMark.CHOSEN
                                usable.any { it === card } -> SlotMark.USABLE
                                else -> null
                            }
                        },
                        onClick = { if (interactive) selected = it else detail = it },
                        onLongClick = { detail = it }
                    )
                    ZoneRow(
                        zones = bottom.spellTrapZones,
                        kind = SlotKind.SPELL_TRAP,
                        fx = fx,
                        isRevealed = { true },
                        modifier = Modifier.weight(1f),
                        markOf = { card -> if (usable.any { it === card }) SlotMark.USABLE else null },
                        onClick = { if (interactive) selected = it else detail = it },
                        onLongClick = { detail = it }
                    )
                    PlayerPanel(
                        player = bottom,
                        isTurnPlayer = state.turnPlayerIndex == bottom.index,
                        thinking = false,
                        fx = fx,
                        onOpenZone = { title, cards -> zoneViewer = title to cards }
                    )
                    OwnHand(
                        player = bottom,
                        fx = fx,
                        engine = engine,
                        markOf = { card -> if (usable.any { it === card }) SlotMark.USABLE else null },
                        onClick = { card -> if (interactive) selected = card else detail = card },
                        onLongClick = { detail = it },
                        modifier = Modifier.height(98.dp)
                    )
                }

                DuelFxLayer(fx = fx, state = state, bottomIndex = bottomIndex)

                Column(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 58.dp, start = 24.dp, end = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ToastStack(controller)
                    SignalCaption(caption) { caption = null }
                }
            }
        }

        if (state.finished && idle) {
            DuelResult(
                state = state,
                bottomIndex = bottomIndex,
                versusAi = config.versusAi,
                onRematch = onRematch,
                onExit = onExit
            )
        }
    }

    // ---- カードごとの行動 --------------------------------------------------
    selected?.let { card ->
        CardActionDialog(
            inst = card,
            player = bottom,
            controller = controller,
            master = master,
            scope = scope,
            onDismiss = { selected = null },
            onShowDetail = {
                selected = null
                detail = card
            },
            onStartAttack = {
                selected = null
                attacker = card
            }
        )
    }

    detail?.let { card ->
        CardPreviewDialog(
            card = card.card,
            master = master,
            statLine = listOfNotNull(
                if (card.card.kind == CardKind.MONSTER) {
                    "★${card.card.level} " +
                        "${master.attributeName(card.card.attributeId)}/" +
                        "${master.raceName(card.card.raceId)} " +
                        "ATK ${engine.atkOf(card)} / DEF ${engine.defOf(card)}"
                } else EffectTextRenderer.summary(card.card, master),
                // 乗っているカウンターは種類ごとに見せる。
                card.counters.filter { it.value > 0 }.takeIf { it.isNotEmpty() }?.entries
                    ?.joinToString("、") { (id, n) ->
                        "${master.counterName(id.takeIf { it != "counter" })}×$n"
                    }
            ).joinToString("\n").ifEmpty { null },
            onDismiss = { detail = null }
        )
    }

    zoneViewer?.let { (title, cards) ->
        ZoneViewerDialog(
            title = title,
            cards = cards,
            master = master,
            onDismiss = { zoneViewer = null },
            onSelect = { card ->
                zoneViewer = null
                // 【場所】に墓地を指定した効果は、ここから発動できる。
                if (interactive && bottom.graveyard.any { it === card }) selected = card
                else detail = card
            }
        )
    }

    if (showLog) {
        DuelLogDialog(state) { showLog = false }
    }

    // ---- エンジンからの問い合わせ ------------------------------------------
    // 演出が一段落してから出す。相手のカードの発動を見てから答えられるように。
    controller.pendingPrompt?.takeIf { settled }?.let { prompt ->
        PromptDialog(prompt = prompt, master = master, controller = controller)
    }

    if (showSurrender) {
        ConfirmDialog(
            title = "デュエルを終了",
            message = "デュエルを中断して戻ります。",
            confirmLabel = "終了する",
            onConfirm = onExit,
            onDismiss = { showSurrender = false }
        )
    }
}

/** 字幕を出さない出来事。帯や浮かぶ数字、手札の動きで十分に分かるもの。 */
private val quietKinds = setOf(
    BoardSignalKind.TURN_START,
    BoardSignalKind.ACTIVATED,
    BoardSignalKind.DRAW,
    BoardSignalKind.TARGETED,
    BoardSignalKind.DAMAGE,
    BoardSignalKind.RECOVER
)

/** 操作できるときに光らせるカード。発動できるもの、召喚できるもの、攻撃できるもの。 */
private fun usableCards(controller: DuelController, player: PlayerState): Set<CardInstance> {
    val engine = controller.engine
    val state = controller.state
    val result = engine.activatableCards(player).toMutableSet()
    if (state.phase.isMain) {
        result += player.hand.filter { it.card.kind == CardKind.MONSTER && engine.canNormalSummon(it, player) }
    }
    if (state.phase == Phase.BATTLE) {
        result += player.monsters.filter { engine.canAttack(it) }
    }
    return result
}

// ===========================================================================
// 画面の上の帯・字幕・お知らせ
// ===========================================================================

@Composable
private fun DuelHeader(
    turn: Int,
    turnOwner: String,
    mine: Boolean,
    soundOn: Boolean,
    onToggleSound: () -> Unit,
    onShowLog: () -> Unit,
    onBack: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Surface1)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "デュエルを終了")
        }
        Text("ターン $turn", fontSize = 16.sp, fontWeight = FontWeight.Black, color = Color.White)
        Spacer(Modifier.width(10.dp))
        Text(
            "${turnOwner}のターン",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (mine) Gold else Danger,
            maxLines = 1,
            modifier = Modifier
                .background((if (mine) Gold else Danger).copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onToggleSound) {
            Icon(
                if (soundOn) Icons.Filled.MusicNote else Icons.Filled.MusicOff,
                contentDescription = if (soundOn) "音を消す" else "音を出す",
                tint = if (soundOn) Accent else Color.White.copy(alpha = 0.5f)
            )
        }
        IconButton(onClick = onShowLog) {
            Icon(Icons.Filled.History, contentDescription = "デュエルログ")
        }
    }
}

/** 直前の出来事の短い字幕。少しすると消える。 */
@Composable
private fun SignalCaption(signal: BoardSignal?, onDone: () -> Unit) {
    if (signal == null) return
    val alpha = remember(signal.id) { Animatable(0f) }
    LaunchedEffect(signal.id) {
        alpha.animateTo(1f, tween(160))
        delay(1100)
        alpha.animateTo(0f, tween(300))
        onDone()
    }
    val color = signalColor(signal.kind)
    Text(
        "${signal.kind.label}　${signal.text}",
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = color.lighten(0.2f),
        maxLines = 1,
        modifier = Modifier
            .graphicsLayer { this.alpha = alpha.value }
            .background(Ink.copy(alpha = 0.82f), RoundedCornerShape(12.dp))
            .border(0.5.dp, color.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp)
    )
}

/** エンジンからのお知らせ。順に出して、少しすると消える。タップでも消せる。 */
@Composable
private fun ToastStack(controller: DuelController) {
    val toast = controller.toasts.firstOrNull() ?: return
    LaunchedEffect(toast.id) {
        delay(2800)
        controller.toasts.remove(toast)
    }
    Text(
        toast.text,
        fontSize = 12.sp,
        color = Color.White,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .background(Surface2.copy(alpha = 0.96f), RoundedCornerShape(10.dp))
            .border(0.5.dp, Accent.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .clickable { controller.toasts.remove(toast) }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

@Composable
private fun DuelLogDialog(state: GameState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("デュエルログ") },
        text = {
            LazyColumn(
                Modifier.heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(state.log.reversed()) { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (line.startsWith("──")) Gold else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } }
    )
}

// ===========================================================================
// 決着
// ===========================================================================

@Composable
private fun DuelResult(
    state: GameState,
    bottomIndex: Int,
    versusAi: Boolean,
    onRematch: () -> Unit,
    onExit: () -> Unit
) {
    val winner = state.winnerIndex
    val won = winner == bottomIndex
    val title = when {
        winner == null -> "引き分け"
        !versusAi -> "${state.players[winner].name}の勝利"
        won -> "勝利"
        else -> "敗北"
    }
    val color = when {
        winner == null -> Accent
        !versusAi || won -> Gold
        else -> Danger
    }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(1400)) }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = (progress.value * 3f).coerceAtMost(1f) }
            .background(Color.Black.copy(alpha = 0.72f))
            // 下の盤面を触らせない。
            .clickable(enabled = true, onClick = {}),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val t = progress.value
            val center = Offset(size.width / 2, size.height * 0.42f)
            drawCircle(
                Brush.radialGradient(listOf(color.copy(alpha = 0.45f * t), Color.Transparent), center, size.minDimension * 0.6f),
                size.minDimension * 0.6f,
                center
            )
            // 勝ったときは光の粒を散らす。
            if (color != Danger) {
                val rng = java.util.Random(42)
                repeat(40) {
                    val angle = rng.nextFloat() * 2 * PI
                    val speed = 0.3f + rng.nextFloat() * 0.7f
                    val d = size.minDimension * 0.55f * speed * (1f - (1f - t) * (1f - t))
                    val p = center + Offset((cos(angle) * d).toFloat(), (sin(angle) * d).toFloat() + 90f * t * t)
                    drawCircle(
                        (if (it % 3 == 0) Color.White else color).copy(alpha = (1f - t * 0.7f)),
                        2f + rng.nextFloat() * 4f,
                        p
                    )
                }
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.graphicsLayer {
                val t = (progress.value / 0.35f).coerceAtMost(1f)
                val s = 1.6f - 0.6f * (1f - (1f - t) * (1f - t))
                scaleX = s
                scaleY = s
            }
        ) {
            Text(
                if (winner == null) "DRAW" else if (!versusAi || won) "VICTORY" else "DEFEAT",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = color.lighten(0.3f),
                letterSpacing = 6.sp
            )
            Text(
                title,
                fontSize = 46.sp,
                fontWeight = FontWeight.Black,
                color = Color.White,
                style = TextStyle(shadow = Shadow(color, Offset.Zero, 28f))
            )
            // 「〜のライフが0になった。勝者: 〜」の前半だけを見せる。
            val reason = state.log.lastOrNull { it.contains("勝者:") }?.substringBefore("。")
            if (!reason.isNullOrBlank()) {
                Text(reason, fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onExit,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.6f))
                ) { Text("戻る", color = Color.White) }
                Button(
                    onClick = onRematch,
                    colors = ButtonDefaults.buttonColors(containerColor = color)
                ) { Text("もう一度", color = Ink, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

// ===========================================================================
// ダイアログ
// ===========================================================================

@Composable
private fun CardActionDialog(
    inst: CardInstance,
    player: PlayerState,
    controller: DuelController,
    master: MasterData,
    /**
     * 画面側のスコープを受け取る。ダイアログ内で rememberCoroutineScope() を使うと、
     * 閉じた瞬間にスコープごと解約されて、発動処理が走る前に打ち切られてしまう。
     */
    scope: CoroutineScope,
    onDismiss: () -> Unit,
    onShowDetail: () -> Unit,
    onStartAttack: () -> Unit
) {
    val engine = controller.engine
    val inHand = player.hand.any { it === inst }
    val canActivate = engine.activatableCards(player).any { it === inst }
    val refusal = if (canActivate) null else engine.whyCannotActivate(inst, player)
    // 【制限】の残り回数。発動できる効果のうち、いちばん余裕のあるものを見せる。
    val remaining = inst.card.effect?.clauses?.indices
        ?.mapNotNull { engine.remainingActivations(inst, it, player) }
        ?.maxOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(inst.card.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    EffectTextRenderer.summary(inst.card, master),
                    style = MaterialTheme.typography.bodySmall,
                    color = Gold
                )

                if (remaining != null) {
                    Text(
                        "【制限】このターンの残り発動回数: $remaining",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (remaining > 0) Accent else Danger
                    )
                }
                if (refusal != null && inst.card.hasEffect) {
                    Text(
                        refusal,
                        style = MaterialTheme.typography.bodySmall,
                        color = Danger
                    )
                }

                if (inHand && inst.card.kind == CardKind.MONSTER) {
                    ActionButton(
                        "通常召喚する" + tributeSuffix(inst),
                        enabled = engine.canNormalSummon(inst, player)
                    ) {
                        onDismiss()
                        scope.launch { engine.normalSummon(inst, player, asSet = false) }
                    }
                    ActionButton(
                        "裏側守備でセットする" + tributeSuffix(inst),
                        enabled = engine.canNormalSummon(inst, player, asSet = true)
                    ) {
                        onDismiss()
                        scope.launch { engine.normalSummon(inst, player, asSet = true) }
                    }
                    // 【場所】に手札を指定した効果（手札誘発や自己特殊召喚）は
                    // 召喚せずにここから発動する。
                    if (inst.card.hasEffect) {
                        ActionButton("効果を発動する", enabled = canActivate) {
                            onDismiss()
                            scope.launch { engine.activateCard(inst, player) }
                        }
                    }
                }

                if (inHand && inst.card.kind != CardKind.MONSTER) {
                    ActionButton("発動する", enabled = canActivate) {
                        onDismiss()
                        scope.launch { engine.activateCard(inst, player) }
                    }
                    ActionButton(
                        "セットする",
                        enabled = engine.canSetSpellTrap(inst, player)
                    ) {
                        onDismiss()
                        engine.setSpellTrap(inst, player)
                    }
                }

                if (!inHand) {
                    ActionButton("効果を発動する", enabled = canActivate) {
                        onDismiss()
                        scope.launch { engine.activateCard(inst, player) }
                    }
                }

                if (engine.isOnField(inst) && inst.card.kind == CardKind.MONSTER) {
                    ActionButton("攻撃する", enabled = engine.canAttack(inst)) {
                        onStartAttack()
                    }
                    ActionButton(
                        "表示形式を変更する",
                        enabled = engine.canChangePosition(inst, player)
                    ) {
                        onDismiss()
                        engine.changePosition(inst, player)
                    }
                }

                ActionButton("カードの詳細を見る", enabled = true) { onShowDetail() }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } }
    )
}

private fun tributeSuffix(inst: CardInstance): String {
    val need = inst.card.tributesRequired
    return if (need > 0) "（${need}体リリース）" else ""
}

@Composable
private fun ActionButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) { Text(label) }
}

@Composable
private fun PromptDialog(
    prompt: DuelPrompt,
    master: MasterData,
    controller: DuelController
) {
    val who = controller.state.players[prompt.playerIndex].name

    when (prompt) {
        is DuelPrompt.Confirm -> AlertDialog(
            onDismissRequest = { controller.resolveConfirm(false) },
            title = { Text(who) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(prompt.message)
                    PromptSubject(prompt.subject, master)
                }
            },
            confirmButton = {
                TextButton(onClick = { controller.resolveConfirm(true) }) { Text("はい") }
            },
            dismissButton = {
                TextButton(onClick = { controller.resolveConfirm(false) }) { Text("いいえ") }
            }
        )

        is DuelPrompt.Options -> AlertDialog(
            onDismissRequest = { controller.resolveOption(0) },
            title = { Text(prompt.message) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    prompt.options.forEachIndexed { index, label ->
                        Button(
                            onClick = { controller.resolveOption(index) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(label) }
                    }
                }
            },
            confirmButton = {}
        )

        is DuelPrompt.CardSelection -> CardSelectionDialog(prompt, master, controller, who)
    }
}

@Composable
private fun CardSelectionDialog(
    prompt: DuelPrompt.CardSelection,
    master: MasterData,
    controller: DuelController,
    who: String
) {
    val picked = remember(prompt) { mutableStateListOf<CardInstance>() }
    var preview by remember(prompt) { mutableStateOf<CardInstance?>(null) }

    AlertDialog(
        onDismissRequest = {},
        title = { Text("${who}：${prompt.message}") },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PromptSubject(prompt.subject, master)
                Text(
                    "選択 ${picked.size} / ${prompt.max}　（長押しで効果を確認）",
                    style = MaterialTheme.typography.labelSmall,
                    color = Gold
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(prompt.candidates, key = { it.uid }) { card ->
                        val isPicked = picked.any { it === card }
                        Surface(
                            color = if (isPicked) Accent.copy(alpha = 0.25f) else Surface2,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .tapOrHold(
                                    onClick = {
                                        if (isPicked) {
                                            picked.removeAll { it === card }
                                        } else if (picked.size < prompt.max) {
                                            picked.add(card)
                                        }
                                    },
                                    // デッキをサーチしているときなどに効果を確かめられる。
                                    onLongClick = { preview = card }
                                )
                        ) {
                            Row(
                                Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CardFace(card.card, Modifier.size(width = 34.dp, height = 47.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(card.card.name, fontWeight = FontWeight.Bold)
                                    Text(
                                        EffectTextRenderer.summary(card.card, master),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    val owner = controller.state.ownerIndexOf(card)
                                    if (owner != null) {
                                        Text(
                                            controller.state.players[owner].name + "のカード",
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { controller.resolveSelection(picked.toList()) },
                enabled = picked.size >= prompt.min
            ) { Text("決定") }
        },
        dismissButton = {
            if (prompt.min == 0) {
                TextButton(onClick = { controller.resolveSelection(emptyList()) }) {
                    Text("発動しない")
                }
            }
        }
    )

    preview?.let { card ->
        CardPreviewDialog(
            card = card.card,
            master = master,
            onDismiss = { preview = null }
        )
    }
}

/**
 * その問い合わせのきっかけになったカードを見せる。
 * 「〇〇が発動されました」という場面で、そのカードの効果を確かめられるようにする。
 */
@Composable
private fun PromptSubject(subject: CardInstance?, master: MasterData) {
    if (subject == null) return
    var showing by remember(subject) { mutableStateOf(false) }

    Surface(
        color = Surface2,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showing = true }
    ) {
        Row(
            Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CardFace(subject.card, Modifier.size(width = 34.dp, height = 47.dp))
            Column(Modifier.weight(1f)) {
                Text(subject.card.name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    EffectTextRenderer.summary(subject.card, master),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("効果を見る", fontSize = 11.sp, color = Gold)
        }
    }

    if (showing) {
        CardPreviewDialog(
            card = subject.card,
            master = master,
            onDismiss = { showing = false }
        )
    }
}

/** 墓地や除外ゾーンの中身を一覧するダイアログ。 */
@Composable
private fun ZoneViewerDialog(
    title: String,
    cards: List<CardInstance>,
    master: MasterData,
    onDismiss: () -> Unit,
    onSelect: (CardInstance) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$title (${cards.size})") },
        text = {
            if (cards.isEmpty()) {
                Text("カードがありません。")
            } else {
                LazyColumn(
                    Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(cards, key = { it.uid }) { card ->
                        Surface(
                            color = Surface2,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(card) }
                        ) {
                            Row(
                                Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CardFace(card.card, Modifier.size(width = 34.dp, height = 47.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(card.card.name, fontWeight = FontWeight.Bold)
                                    Text(
                                        EffectTextRenderer.summary(card.card, master),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } }
    )
}
