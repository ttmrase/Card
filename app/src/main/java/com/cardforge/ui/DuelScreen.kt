package com.cardforge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardforge.data.LibraryRepository
import com.cardforge.game.CardInstance
import com.cardforge.game.PlayerState
import com.cardforge.game.ownerIndexOf
import com.cardforge.model.CardKind
import com.cardforge.model.MasterData
import com.cardforge.model.Phase
import com.cardforge.model.Position
import com.cardforge.text.EffectTextRenderer
import com.cardforge.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun DuelScreen(
    repository: LibraryRepository,
    config: DuelConfig,
    onExit: () -> Unit
) {
    val library = repository.library
    val master = library.master
    val controller = remember { DuelController(library, config) }
    val state = controller.state
    val engine = controller.engine
    val scope = rememberCoroutineScope()

    // 相手の手番になったら AI に打たせる。
    LaunchedEffect(state.turnPlayerIndex) {
        if (config.versusAi && state.turnPlayerIndex == controller.aiIndex && !state.finished) {
            controller.runAiTurn()
        }
    }

    val bottomIndex = if (config.versusAi) controller.humanIndex else state.turnPlayerIndex
    val bottom = state.players[bottomIndex]
    val top = state.players[1 - bottomIndex]

    val interactive = state.turnPlayerIndex == bottomIndex &&
        !controller.aiThinking &&
        controller.pendingPrompt == null &&
        !state.finished

    var selected by remember { mutableStateOf<CardInstance?>(null) }
    var detail by remember { mutableStateOf<CardInstance?>(null) }
    var attacker by remember { mutableStateOf<CardInstance?>(null) }
    var showLog by remember { mutableStateOf(false) }
    var zoneViewer by remember { mutableStateOf<Pair<String, List<CardInstance>>?>(null) }
    var showSurrender by remember { mutableStateOf(false) }

    ScreenScaffold(
        title = "ターン${state.turn}　${state.phase.label}",
        onBack = { showSurrender = true },
        actions = {
            TextButton(onClick = { showLog = !showLog }) { Text("ログ") }
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // ---- 相手側 -------------------------------------------------
            PlayerBanner(
                player = top,
                isTurnPlayer = state.turnPlayerIndex == top.index,
                thinking = controller.aiThinking && top.index == controller.aiIndex,
                onOpenZone = { title, cards -> zoneViewer = title to cards }
            )
            FaceDownHand(count = top.hand.size)
            ZoneRow(
                zones = top.spellTrapZones,
                ownerIndex = top.index,
                bottomIndex = bottomIndex,
                onClick = { detail = it },
                onLongClick = { detail = it }
            )
            ZoneRow(
                zones = top.monsterZones,
                ownerIndex = top.index,
                bottomIndex = bottomIndex,
                highlight = attacker != null,
                onClick = { card ->
                    val current = attacker
                    if (current != null) {
                        attacker = null
                        scope.launch { engine.declareAttack(current, card) }
                    } else {
                        detail = card
                    }
                },
                onLongClick = { detail = it }
            )

            // ---- 中央のコントロール -------------------------------------
            PhaseBar(
                phaseLabel = state.phase.label,
                interactive = interactive,
                canAttackDirectly = state.phase == Phase.BATTLE && engine.canAttackDirectly(),
                attacking = attacker != null,
                onCancelAttack = { attacker = null },
                onDirectAttack = {
                    val current = attacker
                    attacker = null
                    if (current != null) scope.launch { engine.declareAttack(current, null) }
                },
                onNextPhase = { scope.launch { engine.advancePhase() } },
                onEndTurn = { scope.launch { engine.endTurnImmediately() } }
            )

            // ---- 自分側 -------------------------------------------------
            ZoneRow(
                zones = bottom.monsterZones,
                ownerIndex = bottom.index,
                bottomIndex = bottomIndex,
                onClick = { if (interactive) selected = it else detail = it },
                onLongClick = { detail = it }
            )
            ZoneRow(
                zones = bottom.spellTrapZones,
                ownerIndex = bottom.index,
                bottomIndex = bottomIndex,
                onClick = { if (interactive) selected = it else detail = it },
                onLongClick = { detail = it }
            )
            PlayerBanner(
                player = bottom,
                isTurnPlayer = state.turnPlayerIndex == bottom.index,
                thinking = false,
                onOpenZone = { title, cards -> zoneViewer = title to cards }
            )

            Text(
                "手札 (${bottom.hand.size})",
                style = MaterialTheme.typography.labelMedium,
                color = Gold
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(bottom.hand, key = { it.uid }) { card ->
                    FieldCard(
                        inst = card,
                        revealed = true,
                        atk = engine.atkOf(card),
                        def = engine.defOf(card),
                        modifier = Modifier.size(width = 64.dp, height = 90.dp),
                        onClick = { if (interactive) selected = card else detail = card },
                        onLongClick = { detail = card }
                    )
                }
            }

            if (showLog) {
                SectionCard(title = "デュエルログ") {
                    LazyColumn(Modifier.heightIn(max = 220.dp)) {
                        items(state.log.reversed()) { line ->
                            Text(line, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
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
            statLine = if (card.card.kind == CardKind.MONSTER) {
                "★${card.card.level} " +
                    "${master.attributeName(card.card.attributeId)}/" +
                    "${master.raceName(card.card.raceId)} " +
                    "ATK ${engine.atkOf(card)} / DEF ${engine.defOf(card)}"
            } else null,
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

    // ---- エンジンからの問い合わせ ------------------------------------------
    controller.pendingPrompt?.let { prompt ->
        PromptDialog(prompt = prompt, master = master, controller = controller)
    }

    controller.toast?.let { message ->
        AlertDialog(
            onDismissRequest = { controller.toast = null },
            title = { Text("メッセージ") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { controller.toast = null }) { Text("OK") }
            }
        )
    }

    if (state.finished) {
        val winner = state.winnerIndex?.let { state.players[it].name } ?: "引き分け"
        AlertDialog(
            onDismissRequest = {},
            title = { Text("デュエル終了") },
            text = { Text("勝者: $winner") },
            confirmButton = { TextButton(onClick = onExit) { Text("戻る") } }
        )
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

// ===========================================================================
// 盤面のパーツ
// ===========================================================================

@Composable
private fun PlayerBanner(
    player: PlayerState,
    isTurnPlayer: Boolean,
    thinking: Boolean,
    onOpenZone: (String, List<CardInstance>) -> Unit
) {
    Surface(
        color = if (isTurnPlayer) Surface2 else Surface1,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                player.name,
                fontWeight = FontWeight.Bold,
                color = if (isTurnPlayer) Gold else MaterialTheme.colorScheme.onSurface
            )
            Text(
                "LP ${player.life}",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f)
            )
            Text("デッキ${player.deck.size}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "墓地${player.graveyard.size}",
                fontSize = 11.sp,
                color = Gold,
                modifier = Modifier.clickable {
                    onOpenZone("${player.name}の墓地", player.graveyard.toList())
                }
            )
            Text(
                "除外${player.banished.size}",
                fontSize = 11.sp,
                color = Gold,
                modifier = Modifier.clickable {
                    onOpenZone("${player.name}の除外ゾーン", player.banished.toList())
                }
            )
            if (thinking) {
                Text("思考中…", fontSize = 11.sp, color = Accent)
            }
        }
    }
}

@Composable
private fun FaceDownHand(count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(count.coerceAtMost(10)) {
            Box(
                Modifier
                    .size(width = 22.dp, height = 32.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Surface2)
                    .border(1.dp, Accent.copy(alpha = 0.4f), RoundedCornerShape(3.dp))
            )
        }
        if (count > 10) Text("+${count - 10}", fontSize = 10.sp)
    }
}

@Composable
private fun ZoneRow(
    zones: List<CardInstance?>,
    ownerIndex: Int,
    bottomIndex: Int,
    highlight: Boolean = false,
    onClick: (CardInstance) -> Unit,
    onLongClick: (CardInstance) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        zones.forEach { card ->
            Box(Modifier.weight(1f)) {
                if (card == null) {
                    EmptySlot()
                } else {
                    FieldCard(
                        inst = card,
                        revealed = !card.faceDown || ownerIndex == bottomIndex,
                        highlight = highlight,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(74.dp),
                        onClick = { onClick(card) },
                        onLongClick = { onLongClick(card) }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptySlot() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(74.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Color.White.copy(alpha = 0.03f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(5.dp))
    )
}

@Composable
private fun FieldCard(
    inst: CardInstance,
    revealed: Boolean,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    /** 永続効果込みの値。渡されなければカード自身の値を表示する。 */
    atk: Int? = null,
    def: Int? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit = onClick
) {
    val borderColor = when {
        highlight -> Danger
        inst.faceDown -> Accent.copy(alpha = 0.5f)
        else -> kindColor(inst.card.kind)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Surface2)
            .border(1.5.dp, borderColor, RoundedCornerShape(5.dp))
            .tapOrHold(onClick = onClick, onLongClick = onLongClick)
    ) {
        if (!revealed) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("裏", fontSize = 12.sp, color = Accent)
            }
            return@Box
        }

        Column(Modifier.fillMaxSize()) {
            CardArt(
                imagePath = inst.card.imagePath,
                kind = inst.card.kind,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
            Text(
                inst.card.name,
                fontSize = 8.sp,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp)
            )
            if (inst.card.kind == CardKind.MONSTER) {
                Text(
                    "${atk ?: inst.atkValue}/${def ?: inst.defValue}",
                    fontSize = 8.sp,
                    color = Gold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // 表示形式・使用済みの目印。
        if (inst.card.kind == CardKind.MONSTER) {
            Text(
                inst.displayPosition.short,
                fontSize = 9.sp,
                color = if (inst.displayPosition == Position.ATTACK) Danger else Accent,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
            )
        }
        if (inst.hasAttacked) {
            Text(
                "済",
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(2.dp)
            )
        }
    }
}

@Composable
private fun PhaseBar(
    phaseLabel: String,
    interactive: Boolean,
    canAttackDirectly: Boolean,
    attacking: Boolean,
    onCancelAttack: () -> Unit,
    onDirectAttack: () -> Unit,
    onNextPhase: () -> Unit,
    onEndTurn: () -> Unit
) {
    Surface(color = Surface1, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (attacking) {
                Text(
                    "攻撃対象を選んでください。",
                    color = Danger,
                    style = MaterialTheme.typography.labelMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (canAttackDirectly) {
                        Button(onClick = onDirectAttack, modifier = Modifier.weight(1f)) {
                            Text("プレイヤーに直接攻撃")
                        }
                    } else {
                        Text(
                            "相手にモンスターがいるため直接攻撃はできません。",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedButton(onClick = onCancelAttack) { Text("やめる") }
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(phaseLabel, color = Gold, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onNextPhase, enabled = interactive) {
                        Text("次のフェイズ")
                    }
                    Button(onClick = onEndTurn, enabled = interactive) { Text("ターン終了") }
                }
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
                        enabled = engine.canNormalSummon(inst, player)
                    ) {
                        onDismiss()
                        scope.launch { engine.normalSummon(inst, player, asSet = true) }
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
            text = { Text(prompt.message) },
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
                                CardArt(
                                    imagePath = card.card.imagePath,
                                    kind = card.card.kind,
                                    modifier = Modifier.size(width = 30.dp, height = 42.dp)
                                )
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
                                CardArt(
                                    imagePath = card.card.imagePath,
                                    kind = card.card.kind,
                                    modifier = Modifier.size(width = 30.dp, height = 42.dp)
                                )
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
