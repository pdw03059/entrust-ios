// 현황: 등록된 예매처 · 표(대기 · 입장 · 취소) · 검표 기록 모두 지우기
package kr.co.synapseai.entrust.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.co.synapseai.entrust.ui.Palette

private fun orgLabel(org: String) = when {
    org.startsWith("ios-app") -> "아이폰 앱(보안 칩)"
    org.startsWith("android-app") -> "안드로이드 앱(보안 칩)"
    else -> "웹"
}

@Composable
fun StatusScreen(store: AdminStore) {
    var confirm by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle("현황")
        Section(header = "등록된 예매처") {
            if (store.shops.isEmpty()) Text("없음 — 티켓24 › ENTRUST 연동 › '관리자 앱에 등록'", color = Palette.muted)
            store.shops.values.forEach { s ->
                Column {
                    Text(s.name, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("지문 ${s.id}", fontSize = 12.sp, color = Palette.muted)
                }
            }
        }
        Section(header = "등록된 표") {
            if (store.locks.isEmpty()) Text("없음", color = Palette.muted)
            store.locks.values.forEach { l ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${l.terms.show} · ${l.terms.seat}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("${l.terms.no} · ${l.terms.gate}번 입구 · ${orgLabel(l.org)}", fontSize = 12.sp, color = Palette.muted)
                    }
                    when {
                        l.terms.ck in store.cancelled -> Badge2("취소", Palette.muted)
                        store.entered[l.terms.ck] != null -> Badge2("입장", Palette.okGreen)
                        else -> Badge2("대기", Palette.accentBlue)
                    }
                }
            }
        }
        Section(footer = "기록은 이 폰에만 저장돼요. 한 입구에는 검표 기기 한 대를 권해요.") {
            TextButton(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) {
                Text("검표 기록 모두 지우기", color = Palette.badRed, fontSize = 16.sp)
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("등록된 예매처 · 표 · 입장 기록을 모두 지울까요?", fontSize = 17.sp) },
            confirmButton = { TextButton(onClick = { store.resetGate(); confirm = false }) { Text("모두 지우기", color = Palette.badRed) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("취소") } },
        )
    }
}
