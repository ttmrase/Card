package com.cardforge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardforge.model.CardDef
import com.cardforge.model.CardKind
import com.cardforge.ui.theme.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ===========================================================================
// カードの見た目。一覧・デッキ編集・デュエルのどこでも同じ部品を使う。
// ===========================================================================

/** 文字の明るい色。イラストの上に載せる線や紋章に使う。 */
private val Glyph = Color(0xFFF3EEFF)

/**
 * カードのイラスト枠。
 *
 * イラストが無いカードは、種類の色を元に [seed] ごとに違う紋様を描く。
 * 同じカードはいつも同じ絵になるので、イラストが無くても見分けがつく。
 */
@Composable
fun CardArt(
    imagePath: String?,
    kind: CardKind,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    seed: String? = null
) {
    val image = rememberCardImage(imagePath)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(kindColor(kind).copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            SigilArt(kind = kind, seed = seed, modifier = Modifier.fillMaxSize())
        }
    }
}

/** 紋様の組み立てに使う値。種から決まる。 */
private class SigilPlan(kind: CardKind, seed: String?) {
    private val rng = java.util.Random((seed ?: kind.name).hashCode().toLong() * 31 + kind.ordinal)
    val sides = 3 + rng.nextInt(6)
    val rotation = rng.nextFloat() * 360f
    val skip = if (sides >= 5) 2 else 1
    val rings = 1 + rng.nextInt(2)
    val top: Color
    val bottom: Color

    init {
        val (h, _, _) = hsv(kindColor(kind))
        val shift = (rng.nextFloat() - 0.5f) * 50f
        val hue = ((h + shift) % 360f + 360f) % 360f
        top = Color.hsv(hue, 0.55f + rng.nextFloat() * 0.2f, 0.42f + rng.nextFloat() * 0.12f)
        bottom = Color.hsv((hue + 20f) % 360f, 0.7f, 0.16f + rng.nextFloat() * 0.06f)
    }
}

@Composable
fun SigilArt(kind: CardKind, seed: String?, modifier: Modifier = Modifier) {
    val plan = remember(kind, seed) { SigilPlan(kind, seed) }
    Canvas(modifier) {
        drawRect(Brush.verticalGradient(listOf(plan.top, plan.bottom)))
        val center = Offset(size.width / 2f, size.height * 0.48f)
        val r = min(size.width, size.height) * 0.38f
        drawCircle(
            Brush.radialGradient(
                listOf(Glyph.copy(alpha = 0.28f), Color.Transparent),
                center = center,
                radius = r * 1.5f
            ),
            radius = r * 1.5f,
            center = center
        )

        val stroke = max(1f, r * 0.035f)
        rotate(plan.rotation, center) {
            drawCircle(Glyph.copy(alpha = 0.3f), r, center, style = Stroke(stroke))
            if (plan.rings > 1) {
                drawCircle(Glyph.copy(alpha = 0.18f), r * 0.72f, center, style = Stroke(stroke))
            }
            val points = List(plan.sides) { i ->
                val a = 2 * PI * i / plan.sides - PI / 2
                Offset(center.x + (r * cos(a)).toFloat(), center.y + (r * sin(a)).toFloat())
            }
            drawPath(polygon(points), Glyph.copy(alpha = 0.42f), style = Stroke(stroke))
            if (plan.skip > 1) {
                val star = List(plan.sides) { points[(it * plan.skip) % plan.sides] }
                drawPath(polygon(star), Glyph.copy(alpha = 0.22f), style = Stroke(stroke))
            }
            points.forEach { drawCircle(Glyph.copy(alpha = 0.7f), stroke * 1.6f, it) }
        }
        drawEmblem(kind, center, r * 0.42f, Glyph.copy(alpha = 0.9f))

        // 左上から差す光沢。
        drawRect(
            Brush.linearGradient(
                listOf(Color.White.copy(alpha = 0.12f), Color.Transparent),
                start = Offset.Zero,
                end = Offset(size.width * 0.6f, size.height * 0.5f)
            )
        )
    }
}

/** 種類ごとの紋章。モンスターは盾、魔法は輝き、罠は逆三角。 */
fun DrawScope.drawEmblem(kind: CardKind, center: Offset, r: Float, color: Color) {
    when (kind) {
        CardKind.MONSTER -> {
            val path = Path().apply {
                moveTo(center.x - r * 0.8f, center.y - r * 0.8f)
                lineTo(center.x + r * 0.8f, center.y - r * 0.8f)
                lineTo(center.x + r * 0.8f, center.y + r * 0.05f)
                lineTo(center.x, center.y + r)
                lineTo(center.x - r * 0.8f, center.y + r * 0.05f)
                close()
            }
            drawPath(path, color.copy(alpha = color.alpha * 0.25f))
            drawPath(path, color, style = Stroke(max(1.2f, r * 0.12f)))
            drawLine(color, Offset(center.x, center.y - r * 0.5f), Offset(center.x, center.y + r * 0.55f), max(1f, r * 0.1f))
            drawLine(color, Offset(center.x - r * 0.4f, center.y - r * 0.15f), Offset(center.x + r * 0.4f, center.y - r * 0.15f), max(1f, r * 0.1f))
        }

        CardKind.SPELL -> {
            val path = Path().apply {
                val inner = r * 0.22f
                for (i in 0 until 8) {
                    val a = PI / 4 * i - PI / 2
                    val len = if (i % 2 == 0) r else inner
                    val p = Offset(center.x + (len * cos(a)).toFloat(), center.y + (len * sin(a)).toFloat())
                    if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                }
                close()
            }
            drawPath(path, color)
            drawCircle(color.copy(alpha = 0.35f), r * 0.55f, center, style = Stroke(max(1f, r * 0.08f)))
        }

        CardKind.TRAP -> {
            val outer = polygon(
                listOf(
                    Offset(center.x - r, center.y - r * 0.6f),
                    Offset(center.x + r, center.y - r * 0.6f),
                    Offset(center.x, center.y + r)
                )
            )
            val inner = polygon(
                listOf(
                    Offset(center.x - r * 0.42f, center.y - r * 0.28f),
                    Offset(center.x + r * 0.42f, center.y - r * 0.28f),
                    Offset(center.x, center.y + r * 0.45f)
                )
            )
            drawPath(outer, color, style = Stroke(max(1.2f, r * 0.12f)))
            drawPath(inner, color)
        }
    }
}

private fun polygon(points: List<Offset>): Path = Path().apply {
    points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
    close()
}

/** 色を色相・彩度・明度に分ける。紋様の色をカードの種類から少しずらすのに使う。 */
private fun hsv(color: Color): Triple<Float, Float, Float> {
    val r = color.red
    val g = color.green
    val b = color.blue
    val maxC = max(r, max(g, b))
    val minC = min(r, min(g, b))
    val delta = maxC - minC
    val hue = when {
        delta == 0f -> 0f
        maxC == r -> 60f * (((g - b) / delta) % 6f)
        maxC == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }
    val saturation = if (maxC == 0f) 0f else delta / maxC
    return Triple((hue + 360f) % 360f, saturation, maxC)
}

// ---------------------------------------------------------------------------
// 裏面
// ---------------------------------------------------------------------------

/** カードの裏面。伏せたカードや相手の手札に使う。 */
@Composable
fun CardBack(modifier: Modifier = Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(5.dp))) {
        drawCardBack()
    }
}

fun DrawScope.drawCardBack() {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF2E2658), Color(0xFF141126))))
    // 斜めの格子。
    val step = max(6f, size.width / 5f)
    var x = -size.height
    while (x < size.width + size.height) {
        drawLine(Accent.copy(alpha = 0.12f), Offset(x, 0f), Offset(x + size.height, size.height), 1f)
        drawLine(Accent.copy(alpha = 0.12f), Offset(x + size.height, 0f), Offset(x, size.height), 1f)
        x += step
    }
    val inset = min(size.width, size.height) * 0.08f
    drawRoundRect(
        Gold.copy(alpha = 0.55f),
        topLeft = Offset(inset, inset),
        size = Size(size.width - inset * 2, size.height - inset * 2),
        cornerRadius = CornerRadius(inset, inset),
        style = Stroke(max(1f, inset * 0.25f))
    )
    val center = Offset(size.width / 2f, size.height / 2f)
    val r = min(size.width, size.height) * 0.26f
    drawCircle(
        Brush.radialGradient(listOf(Accent.copy(alpha = 0.45f), Color.Transparent), center, r * 1.6f),
        r * 1.6f,
        center
    )
    drawCircle(Gold.copy(alpha = 0.75f), r, center, style = Stroke(max(1f, r * 0.08f)))
    // 2枚の正方形を重ねた八芒星。
    for (turn in listOf(0f, 45f)) {
        rotate(turn, center) {
            val side = r * 1.25f
            drawRect(
                Accent.copy(alpha = 0.6f),
                topLeft = Offset(center.x - side / 2, center.y - side / 2),
                size = Size(side, side),
                style = Stroke(max(1f, r * 0.07f))
            )
        }
    }
    drawCircle(Gold, r * 0.18f, center)
}

// ---------------------------------------------------------------------------
// 表面
// ---------------------------------------------------------------------------

/**
 * 小さなカード1枚の表面。名前・イラスト・レベル・攻守を載せる。
 *
 * 大きさに合わせて文字も伸び縮みするので、手札でも盤面でも同じ部品で描ける。
 * [atk] [def] にはデュエル中の実際の値を渡す。素の値から動いていれば色で示す。
 */
@Composable
fun CardFace(
    card: CardDef,
    modifier: Modifier = Modifier,
    atk: Int? = null,
    def: Int? = null
) {
    val frame = kindColor(card.kind)
    val shape = RoundedCornerShape(5.dp)
    BoxWithConstraints(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(frame.darken(0.55f), Color(0xFF15122A))))
            .border(
                1.5.dp,
                Brush.linearGradient(listOf(frame.lighten(0.35f), frame, frame.darken(0.3f))),
                shape
            )
    ) {
        // 幅 64dp を基準にした倍率。
        val unit = (maxWidth.value / 64f).coerceIn(0.75f, 2.4f)
        Column(
            Modifier
                .fillMaxSize()
                .padding((2.5f * unit).dp),
            verticalArrangement = Arrangement.spacedBy((1.5f * unit).dp)
        ) {
            Text(
                card.name,
                fontSize = (7.5f * unit).sp,
                lineHeight = (8.5f * unit).sp,
                fontWeight = FontWeight.Bold,
                color = Glyph,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
            CardArt(
                imagePath = card.imagePath,
                kind = card.kind,
                seed = card.id,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
            if (card.kind == CardKind.MONSTER) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "★${card.level}",
                        fontSize = (6.5f * unit).sp,
                        lineHeight = (7.5f * unit).sp,
                        color = Gold,
                        maxLines = 1
                    )
                    Spacer(Modifier.weight(1f))
                    StatText(atk ?: card.atk, card.atk, unit)
                    Text("/", fontSize = (6.5f * unit).sp, lineHeight = (7.5f * unit).sp, color = Gold.copy(alpha = 0.7f))
                    StatText(def ?: card.def, card.def, unit)
                }
            } else {
                Text(
                    if (card.kind == CardKind.SPELL) "魔法" else "罠",
                    fontSize = (6.5f * unit).sp,
                    lineHeight = (7.5f * unit).sp,
                    color = frame.lighten(0.4f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** 攻守の1つぶん。上がっていれば緑、下がっていれば赤。 */
@Composable
private fun StatText(current: Int, base: Int, unit: Float) {
    Text(
        current.toString(),
        fontSize = (6.5f * unit).sp,
        lineHeight = (7.5f * unit).sp,
        fontWeight = if (current == base) FontWeight.Normal else FontWeight.Bold,
        maxLines = 1,
        color = when {
            current > base -> Boost
            current < base -> Danger
            else -> Glyph
        }
    )
}

fun Color.lighten(amount: Float): Color = Color(
    red + (1f - red) * amount,
    green + (1f - green) * amount,
    blue + (1f - blue) * amount,
    alpha
)

fun Color.darken(amount: Float): Color = Color(
    red * (1f - amount),
    green * (1f - amount),
    blue * (1f - amount),
    alpha
)
