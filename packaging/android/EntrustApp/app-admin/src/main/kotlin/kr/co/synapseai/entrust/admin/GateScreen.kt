// 검표: 담당 입구 · 판정 큰 글씨 · 카메라 · 숫자 · 최근 기록 10줄
package kr.co.synapseai.entrust.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.co.synapseai.entrust.ui.Palette
import kr.co.synapseai.entrust.ui.QRScanner
import kr.co.synapseai.entrust.ui.clockSec

private val warnYellow = Color(0xFFF0A34A) // 아이폰 Color(red: 0.94, green: 0.64, blue: 0.29)

@Composable
fun GateScreen(store: AdminStore) {
    // 화면에 들어올 때마다 켜짐(아이폰 onAppear/onDisappear)
    var scanning by remember { mutableStateOf(true) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle("검표")
        Segmented(listOf("1", "2", "3"), store.gate, { "${it}번 입구" }) { store.updateGate(it) }

        store.verdict?.let { v ->
            val bg = when (v.kind) { 0 -> Palette.okGreen; 1 -> Palette.badRed; else -> warnYellow }
            val fg = if (v.kind == 2) Palette.ink else Color.White
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(bg)
                    .clickable { store.verdict = null }
                    .padding(vertical = 22.dp, horizontal = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(v.title, fontSize = 38.sp, fontWeight = FontWeight.ExtraBold, color = fg, textAlign = TextAlign.Center, lineHeight = 44.sp)
                Text(v.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = fg, textAlign = TextAlign.Center)
            }
        }

        if (scanning) {
            Box(Modifier.fillMaxWidth().height(340.dp).clip(RoundedCornerShape(20.dp))) {
                QRScanner(Modifier.fillMaxSize(), active = true) { store.handle(it) }
                Box(Modifier.fillMaxSize().padding(70.dp).border(3.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(14.dp)))
            }
        }
        TextButton(onClick = { scanning = !scanning }) {
            Text(if (scanning) "카메라 끄기" else "카메라 켜기", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }

        Row(Modifier.fillMaxWidth()) {
            Stat("예매처", store.shops.size, Modifier.weight(1f))
            Stat("등록된 표", store.locks.size, Modifier.weight(1f))
            Stat("입장", store.entered.size, Modifier.weight(1f))
            Stat("취소", store.cancelled.size, Modifier.weight(1f))
        }
        Text(
            "읽는 것: 예매처 등록(티켓24 '연동') · 표 등록(ENTRUST) · 입장 QR · 취소 알림. 같은 폰의 앱에서 넘긴 것도 여기서 판정해요.",
            fontSize = 13.sp, color = Palette.muted,
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            store.log.takeLast(10).reversed().forEach { l ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(clockSec(l.at), fontSize = 12.sp, color = Palette.muted, style = tnum)
                    Text(l.text, fontSize = 12.sp, color = if (l.ok) Palette.ink else Palette.badRed, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Stat(k: String, n: Int, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$n", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, style = tnum)
        Text(k, fontSize = 12.sp, color = Palette.muted)
    }
}
