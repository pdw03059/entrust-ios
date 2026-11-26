// 세 앱이 함께 쓰는 색 · 글자 · 버튼 · 카드 · QR 그림(아이폰 Shared/UI.swift 와 같은 디자인 기준: 종이색 바탕 · 파랑 · 초록(통과) · 빨강(거절)).
package kr.co.synapseai.entrust.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kr.co.synapseai.entrust.core.Terms
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Palette {
    val paper = Color(0xFFF4F2EC)
    val ink = Color(0xFF14171C)
    val muted = Color(0xFF5F6670)
    val accentBlue = Color(0xFF1F45C9)
    val okGreen = Color(0xFF0E7A55)
    val badRed = Color(0xFFC2352B)
    val warnOrange = Color(0xFFA8590F)
}

@Composable
fun EntrustTheme(primary: Color = Palette.accentBlue, background: Color = Palette.paper, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = primary, onPrimary = Color.White, background = background, surface = Color.White,
            onBackground = Palette.ink, onSurface = Palette.ink, error = Palette.badRed,
        ),
    ) { Surface(color = background, contentColor = Palette.ink, content = content) }
}

/** QR 그림(오류 정정 L, 흰 여백 포함 — 아이폰과 같음) */
fun qrBitmap(text: String, size: Int = 720): Bitmap? = runCatching {
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 2, EncodeHintType.CHARACTER_SET to "UTF-8")
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val px = IntArray(m.width * m.height)
    for (y in 0 until m.height) for (x in 0 until m.width) px[y * m.width + x] = if (m[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
}.getOrNull()

@Composable
fun QRView(text: String?, modifier: Modifier = Modifier) {
    val bmp = remember(text) { text?.let { qrBitmap(it) } }
    Box(modifier.fillMaxWidth().background(Color.White).padding(14.dp), contentAlignment = Alignment.Center) {
        if (bmp != null) {
            Image(bmp.asImageBitmap(), contentDescription = "QR", modifier = Modifier.fillMaxWidth().aspectRatio(1f), filterQuality = FilterQuality.None)
        } else {
            Box(Modifier.fillMaxWidth().heightIn(min = 200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
}

fun meters(m: Int): String = if (m >= 1000) String.format(Locale.US, "%.1fkm", m / 1000.0) else "${m}m"

fun dur(s: Long): String = when {
    s >= 3600 -> "${s / 3600}시간 ${(s % 3600) / 60}분"
    s >= 60 -> "${s / 60}분 ${s % 60}초"
    else -> "${s}초"
}

fun clock(sec: Long): String = SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(Date(sec * 1000))
fun clockSec(sec: Long): String = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(sec * 1000))
fun won(n: Int): String = NumberFormat.getNumberInstance(Locale.KOREA).format(n) + "원"

@Composable
fun PrimaryButton(title: String, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Palette.accentBlue, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White, disabledContainerColor = color.copy(alpha = 0.45f), disabledContentColor = Color.White),
        modifier = modifier.fillMaxWidth().heightIn(min = 54.dp),
    ) { Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
}

@Composable
fun GhostButton(title: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Color.Black.copy(alpha = 0.18f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.ink),
        modifier = modifier.fillMaxWidth().heightIn(min = 50.dp),
    ) { Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().shadow(12.dp, RoundedCornerShape(20.dp), ambientColor = Color.Black.copy(alpha = 0.08f), spotColor = Color.Black.copy(alpha = 0.08f))
            .background(Color.White, RoundedCornerShape(20.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content,
    )
}

@Composable
fun InfoCell(k: String, v: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(k, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.muted)
        Text(v, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun TicketCard(t: Terms) {
    Card {
        Text(t.show, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        Text("예매번호 ${t.no} · ${t.venue}", fontSize = 13.sp, color = Palette.muted)
        HorizontalDivider()
        val cells = listOf("입구" to t.gate, "좌석" to t.seat, "인원" to "${t.qty}명", "입장 열림" to clock(t.from), "입장 닫힘" to clock(t.to), "반경" to meters(t.r.toInt()))
        cells.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth()) { row.forEach { (k, v) -> InfoCell(k, v, Modifier.weight(1f)) } }
        }
    }
}

/** 화면 제목 · 설명 한 쌍 */
@Composable
fun Heading(title: String, sub: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        if (sub != null) Text(sub, fontSize = 14.sp, color = Palette.muted)
    }
}

@Composable fun Hint(text: String, color: Color = Palette.muted) = Text(text, fontSize = 13.sp, color = color)

