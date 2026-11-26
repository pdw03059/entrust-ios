// 공연 관리: 목록 · 수정 · 삭제 · 티켓24로 보내기(ticket24://event#e=…)
package kr.co.synapseai.entrust.admin

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.co.synapseai.entrust.core.Entrust
import kr.co.synapseai.entrust.core.ShowEvent
import kr.co.synapseai.entrust.ui.Palette
import kr.co.synapseai.entrust.ui.clock
import kr.co.synapseai.entrust.ui.meters
import kr.co.synapseai.entrust.ui.openLink

/** 티켓24 앱으로 공연을 넘긴다(받을 앱이 없으면 안내) */
fun sendToShop(context: Context, e: ShowEvent) {
    if (!openLink(context, AdminStore.shopURL(e))) {
        Toast.makeText(context, "티켓24 앱이 설치되어 있지 않아요", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun EventsScreen(store: AdminStore, onEdit: (ShowEvent) -> Unit) {
    val ctx = LocalContext.current
    var deleting by remember { mutableStateOf<ShowEvent?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ScreenTitle("공연 관리", Modifier.weight(1f))
            Button(
                onClick = { onEdit(newEvent()) },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.accentBlue),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                modifier = Modifier.size(44.dp),
            ) { Text("+", fontSize = 24.sp, fontWeight = FontWeight.Bold) }
        }
        Section {
            Text(
                "입장 QR이 열릴 장소(GPS) · 반경 · 시간을 정하고 티켓24로 보내요. 티켓24에서 산 표에 이 조건이 서명되어 들어가요.",
                fontSize = 13.sp, color = Palette.muted,
            )
        }
        if (store.events.isEmpty()) {
            Text("아직 공연이 없어요. 오른쪽 위 '+'로 만들어 주세요.", color = Palette.muted, modifier = Modifier.padding(horizontal = 4.dp))
        }
        store.events.forEach { e ->
            Column(
                Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(14.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(e.show, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("${e.venue} · 반경 ${meters(e.r)} · ${e.gate}번 입구", fontSize = 13.sp, color = Palette.muted)
                Text("입장 ${clock(e.from)} ~ ${clock(e.to)}", fontSize = 13.sp, color = Palette.muted, style = tnum)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { onEdit(e) }, modifier = Modifier.heightIn(min = 40.dp)) { Text("수정", fontSize = 14.sp) }
                    Button(
                        onClick = { sendToShop(ctx, e) },
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.accentBlue),
                        modifier = Modifier.heightIn(min = 40.dp),
                    ) { Text("티켓24로 보내기", fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { deleting = e }) { Text("삭제", color = Palette.badRed, fontSize = 14.sp) }
                }
            }
        }
    }

    deleting?.let { d ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("'${d.show}' 공연을 삭제할까요?") },
            confirmButton = { TextButton(onClick = { store.delete(d); deleting = null }) { Text("삭제", color = Palette.badRed) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("취소") } },
        )
    }
}

private fun newEvent(): ShowEvent {
    val start = ((Entrust.now() + 120) / 60) * 60
    return ShowEvent(
        id = "ev_" + Entrust.randomB64(6), show = "새 공연", venue = "시험 공연장", lat = 37.5665, lon = 126.978,
        r = 200, from = start, to = start + 3 * 3600, gate = "1", price = 55000, category = "콘서트",
    )
}
