package com.cardforge.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cardforge.ui.theme.Gold
import com.cardforge.ui.theme.Ink
import kotlin.math.min

/** 切り抜いた範囲。いずれも元画像に対する 0.0〜1.0 の割合。 */
data class CropRect(val left: Float, val top: Float, val width: Float, val height: Float)

/** カードのイラスト枠の縦横比。 */
const val CARD_ART_ASPECT = 3f / 4f

/**
 * イラストを切り抜く画面。
 *
 * 画像全体を見せたうえで、中央のカード枠に収める。枠の外は暗く表示するだけで
 * 元画像は失われない。ドラッグで位置、つまむ操作で拡大縮小する。
 */
@Composable
fun ImageCropDialog(
    imagePath: String,
    onDismiss: () -> Unit,
    onConfirm: (CropRect) -> Unit
) {
    val bitmap = rememberCardImage(imagePath)

    var zoom by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var containerWidth by remember { mutableFloatStateOf(0f) }
    var containerHeight by remember { mutableFloatStateOf(0f) }

    // 枠は、表示領域に収まる最大の 3:4。
    val frameWidth = min(containerWidth, containerHeight * CARD_ART_ASPECT)
    val frameHeight = frameWidth / CARD_ART_ASPECT

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(color = Ink, modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "イラストの切り抜き",
                    style = MaterialTheme.typography.titleLarge,
                    color = Gold
                )
                Text(
                    "ドラッグで位置を、つまむ操作で大きさを決めます。明るい枠の中がイラストになります。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onSizeChanged {
                            containerWidth = it.width.toFloat()
                            containerHeight = it.height.toFloat()
                        }
                        .clipToBounds()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, gestureZoom, _ ->
                                zoom = (zoom * gestureZoom).coerceIn(0.5f, 6f)
                                offsetX += pan.x
                                offsetY += pan.y
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (bitmap != null) {
                        // 画像全体が見えるように収めてから、拡大縮小と移動を掛ける。
                        Image(
                            bitmap = bitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = zoom,
                                    scaleY = zoom,
                                    translationX = offsetX,
                                    translationY = offsetY
                                )
                        )
                    }

                    // 枠の外を暗くして、切り抜かれる範囲を示す。
                    Box(
                        Modifier
                            .fillMaxSize()
                            .drawBehind {
                                val left = (size.width - frameWidth) / 2f
                                val top = (size.height - frameHeight) / 2f
                                val shade = Color.Black.copy(alpha = 0.6f)
                                drawRect(shade, Offset.Zero, Size(size.width, top))
                                drawRect(
                                    shade,
                                    Offset(0f, top + frameHeight),
                                    Size(size.width, size.height - top - frameHeight)
                                )
                                drawRect(shade, Offset(0f, top), Size(left, frameHeight))
                                drawRect(
                                    shade,
                                    Offset(left + frameWidth, top),
                                    Size(size.width - left - frameWidth, frameHeight)
                                )
                                drawRect(
                                    color = Gold,
                                    topLeft = Offset(left, top),
                                    size = Size(frameWidth, frameHeight),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f)
                                )
                            }
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = {
                        zoom = 1f
                        offsetX = 0f
                        offsetY = 0f
                    }) { Text("リセット") }

                    OutlinedButton(onClick = onDismiss) { Text("切り抜かない") }

                    Button(
                        enabled = bitmap != null,
                        onClick = {
                            val image = bitmap ?: return@Button
                            onConfirm(
                                computeCrop(
                                    imageWidth = image.width,
                                    imageHeight = image.height,
                                    containerWidth = containerWidth,
                                    containerHeight = containerHeight,
                                    frameWidth = frameWidth,
                                    frameHeight = frameHeight,
                                    zoom = zoom,
                                    offsetX = offsetX,
                                    offsetY = offsetY
                                )
                            )
                        }
                    ) { Text("この範囲で決定") }
                }
            }
        }
    }
}

/**
 * 枠の中に映っているのが、元画像のどの範囲かを求める。
 *
 * 画像は表示領域に収まるよう等倍で配置され（Fit）、そこへ拡大率と移動量が
 * 掛かっている。枠の四隅を画像の座標に戻して割合にする。
 */
internal fun computeCrop(
    imageWidth: Int,
    imageHeight: Int,
    containerWidth: Float,
    containerHeight: Float,
    frameWidth: Float,
    frameHeight: Float,
    zoom: Float,
    offsetX: Float,
    offsetY: Float
): CropRect {
    if (imageWidth <= 0 || imageHeight <= 0 ||
        containerWidth <= 0f || containerHeight <= 0f || zoom <= 0f
    ) {
        return CropRect(0f, 0f, 1f, 1f)
    }

    val fitScale = min(containerWidth / imageWidth, containerHeight / imageHeight)
    val displayedWidth = imageWidth * fitScale * zoom
    val displayedHeight = imageHeight * fitScale * zoom
    if (displayedWidth <= 0f || displayedHeight <= 0f) return CropRect(0f, 0f, 1f, 1f)

    // 表示領域における画像の左上と、枠の左上。
    val imageLeft = containerWidth / 2f + offsetX - displayedWidth / 2f
    val imageTop = containerHeight / 2f + offsetY - displayedHeight / 2f
    val frameLeft = (containerWidth - frameWidth) / 2f
    val frameTop = (containerHeight - frameHeight) / 2f

    val rawLeft = (frameLeft - imageLeft) / displayedWidth
    val rawTop = (frameTop - imageTop) / displayedHeight
    val rawWidth = frameWidth / displayedWidth
    val rawHeight = frameHeight / displayedHeight

    // 画像の外にはみ出した分は切り詰める。
    val left = rawLeft.coerceIn(0f, 1f)
    val top = rawTop.coerceIn(0f, 1f)
    val right = (rawLeft + rawWidth).coerceIn(0f, 1f)
    val bottom = (rawTop + rawHeight).coerceIn(0f, 1f)

    val width = (right - left).coerceAtLeast(0.01f)
    val height = (bottom - top).coerceAtLeast(0.01f)
    return CropRect(
        left = left.coerceAtMost(1f - width),
        top = top.coerceAtMost(1f - height),
        width = width,
        height = height
    )
}
