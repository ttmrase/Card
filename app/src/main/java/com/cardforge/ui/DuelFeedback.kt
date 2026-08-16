package com.cardforge.ui

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardforge.game.BoardSignal
import com.cardforge.game.BoardSignalKind
import com.cardforge.game.GameState
import com.cardforge.ui.theme.Accent
import com.cardforge.ui.theme.Boost
import com.cardforge.ui.theme.Danger
import com.cardforge.ui.theme.Gold
import kotlinx.coroutines.delay

/** 出来事ごとの色。 */
private fun signalColor(kind: BoardSignalKind): Color = when (kind) {
    BoardSignalKind.DESTROYED, BoardSignalKind.DAMAGE -> Danger
    BoardSignalKind.RECOVER -> Boost
    BoardSignalKind.ATTACK -> Danger
    BoardSignalKind.BANISHED, BoardSignalKind.SENT_TO_GRAVEYARD -> Gold
    else -> Accent
}

/** 出来事ごとの音。高さと長さを変えて聞き分けられるようにする。 */
private fun signalTone(kind: BoardSignalKind): Pair<Int, Int> = when (kind) {
    BoardSignalKind.DESTROYED -> ToneGenerator.TONE_CDMA_ABBR_ALERT to 220
    BoardSignalKind.DAMAGE -> ToneGenerator.TONE_CDMA_LOW_L to 200
    BoardSignalKind.RECOVER -> ToneGenerator.TONE_CDMA_HIGH_L to 160
    BoardSignalKind.ATTACK -> ToneGenerator.TONE_CDMA_MED_SS to 200
    BoardSignalKind.BANISHED -> ToneGenerator.TONE_CDMA_MED_L to 180
    BoardSignalKind.SENT_TO_GRAVEYARD -> ToneGenerator.TONE_CDMA_LOW_SS to 160
    BoardSignalKind.SUMMONED -> ToneGenerator.TONE_CDMA_HIGH_SS to 140
    BoardSignalKind.ACTIVATED -> ToneGenerator.TONE_PROP_BEEP to 120
    BoardSignalKind.DRAW -> ToneGenerator.TONE_PROP_ACK to 100
}

/**
 * 盤面の出来事を、短い帯のアニメーションと音で知らせる。
 *
 * エンジンが積んだ [GameState.signals] を1つずつ取り出して見せ、
 * 見せ終わったら消す。音は端末の内蔵トーンを使うので、音源ファイルは要らない。
 */
@Composable
fun BoardSignalBanner(
    state: GameState,
    soundOn: Boolean,
    modifier: Modifier = Modifier
) {
    var showing by remember { mutableStateOf<BoardSignal?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }

    val tones = remember {
        runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull()
    }
    DisposableEffect(Unit) {
        onDispose { runCatching { tones?.release() } }
    }

    // 積まれた出来事を順に見せる。
    LaunchedEffect(state.signals.size, showing) {
        if (showing != null) return@LaunchedEffect
        val next = state.signals.firstOrNull() ?: return@LaunchedEffect
        state.signals.remove(next)
        showing = next
        if (soundOn) {
            val (tone, ms) = signalTone(next.kind)
            runCatching { tones?.startTone(tone, ms) }
        }
        // 0 →1 → 0 の山を作って、出て消えるように見せる。
        val steps = 16
        repeat(steps) { step ->
            progress = if (step < steps / 3) {
                (step + 1f) / (steps / 3f)
            } else {
                1f - (step - steps / 3f) / (steps - steps / 3f)
            }
            delay(40)
        }
        progress = 0f
        showing = null
    }

    val signal = showing ?: return
    val alpha = progress.coerceIn(0f, 1f)
    val color = signalColor(signal.kind)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                this.alpha = alpha
                scaleX = 0.94f + 0.06f * alpha
                scaleY = 0.94f + 0.06f * alpha
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            "${signal.kind.label}　${signal.text}",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(color.copy(alpha = 0.16f))
                .padding(horizontal = 14.dp, vertical = 5.dp)
        )
    }
}
