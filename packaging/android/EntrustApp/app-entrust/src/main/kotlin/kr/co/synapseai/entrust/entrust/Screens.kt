// ENTRUST 관객 앱 화면: 표 받기 → 지문으로 잠그기 → 검표기 등록 → 입장 QR (아이폰 ContentView · ReceiveView · LockView · RegisterView · EntryView)
package kr.co.synapseai.entrust.entrust

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import kr.co.synapseai.entrust.core.Entrust
import kr.co.synapseai.entrust.core.PlaceCheck
import kr.co.synapseai.entrust.core.StoredTicket
import kr.co.synapseai.entrust.core.Terms
import kr.co.synapseai.entrust.ui.Card
import kr.co.synapseai.entrust.ui.GhostButton
import kr.co.synapseai.entrust.ui.Palette
import kr.co.synapseai.entrust.ui.PrimaryButton
import kr.co.synapseai.entrust.ui.QRView
import kr.co.synapseai.entrust.ui.TicketCard
import kr.co.synapseai.entrust.ui.clock
import kr.co.synapseai.entrust.ui.fragmentActivity
import kr.co.synapseai.entrust.ui.meters
import kr.co.synapseai.entrust.ui.openLink
import kr.co.synapseai.entrust.ui.rememberLocation

@Composable
private fun BigTitle(text: String) = Text(text, fontSize = 28.sp, fontWeight = FontWeight.Black, lineHeight = 34.sp)

@Composable
private fun Sub(text: String) = Text(text, fontSize = 15.sp, color = Palette.muted, lineHeight = 21.sp)

@Composable
private fun Foot(text: String) = Text(text, fontSize = 13.sp, color = Palette.muted, lineHeight = 18.sp)

@Composable
fun ContentView(model: AppModel) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("ENTRUST", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (model.ticket != null) {
                TextButton(onClick = { model.reset() }) { Text("처음부터", color = Palette.accentBlue, fontSize = 16.sp) }
            }
        }
        val t = model.ticket
        when {
            t == null -> ReceiveView(model)
            !t.locked -> LockView(model, t)
            !t.registered -> RegisterView(model, t)
            else -> EntryView(model, t)
        }
        if (model.notice.isNotEmpty()) {
            Text(model.notice, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.badRed)
        }
    }
}

@Composable
fun ReceiveView(model: AppModel) {
    val ctx = LocalContext.current
    var manual by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BigTitle("티켓24에서 산 표를 받아요")
        Sub("예매처가 서명한 표만 받아요. 공연 장소 · 입장 시간은 관리자가 정하고, 이 앱에서는 바꿀 수 없어요.")
        PrimaryButton("티켓24 열기") {
            if (!openLink(ctx, "ticket24://home")) model.notice = "티켓24 앱이 이 폰에 없어요."
        }
        Foot("티켓24에서 예매 후 'ENTRUST로 표 보내기'를 누르면 이 앱으로 바로 들어와요.")
        GhostButton("표 코드 붙여 넣기") { model.receive(pasteText(ctx)) }
        OutlinedTextField(
            value = manual,
            onValueChange = { manual = it },
            placeholder = { Text("ET1.… 직접 넣기") },
            singleLine = false,
            maxLines = 6,
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White,
                unfocusedBorderColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        if (manual.isNotEmpty()) {
            TextButton(onClick = { model.receive(manual) }) {
                Text("받기", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.accentBlue)
            }
        }
    }
}

private fun pasteText(ctx: Context): String {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
    val clip = cm.primaryClip ?: return ""
    if (clip.itemCount == 0) return ""
    return clip.getItemAt(0).coerceToText(ctx)?.toString() ?: ""
}

@Composable
fun LockView(model: AppModel, t: StoredTicket) {
    val activity = LocalContext.current.fragmentActivity()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BigTitle("이 표를 지문으로 잠글게요")
        Sub("이 폰의 보안 칩(안드로이드 키스토어) 안에 이 표의 열쇠를 만들어요. 열쇠는 폰 밖으로 나가지 않고(백업 · 다른 기기 복사 안 됨), 지문으로만 쓸 수 있어요.")
        TicketCard(t.terms)
        PrimaryButton(if (model.busy) "잠그는 중…" else "지문으로 잠그기", enabled = !model.busy && activity != null) {
            activity?.let { model.lock(it) }
        }
    }
}

@Composable
fun RegisterView(model: AppModel, t: StoredTicket) {
    val ctx = LocalContext.current
    LaunchedEffect(t.terms.ck) { model.refreshLockQr() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BigTitle("검표기에 표를 등록해요")
        Sub("공연 전날 명단을 보내는 일을 흉내 내요. 관리자 앱이 같은 폰에 있으면 아래 버튼, 다른 기기의 검표기면 이 QR을 찍어 주세요.")
        val url = model.adminURL
        PrimaryButton("관리자 앱에 등록(같은 폰)", enabled = url != null) {
            if (url != null && !openLink(ctx, url)) model.notice = "이 폰에 ENTRUST 관리자 앱이 없어요. 검표기에서 아래 QR을 찍어 주세요."
        }
        Card {
            QRView(model.lockText)
            Foot("예매번호 ${t.terms.no} · ${t.terms.seat} · 입구 ${t.terms.gate}")
        }
        GhostButton("등록했어요 · 입장 QR로") { model.markRegistered() }
    }
}

@Composable
fun EntryView(model: AppModel, t: StoredTicket) {
    val activity = LocalContext.current.fragmentActivity()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val loc = rememberLocation(active = lifecycleState.isAtLeast(Lifecycle.State.STARTED))
    val terms = t.terms
    val place = PlaceCheck.check(terms.lat, terms.lon, terms.r, loc.fix, loc.permitted)
    val time = model.timeState()

    // 1초마다: 시간 · 위치를 다시 보고, 입장 QR은 10초마다 새로(아이폰 tick 과 같음)
    LaunchedEffect(terms.ck) {
        while (true) {
            val ok = PlaceCheck.check(terms.lat, terms.lon, terms.r, loc.fix, loc.permitted).isOK
            model.tick(placeOK = ok)
            delay(1000)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BigTitle("입장 QR")
        Card {
            val entry = model.entryText
            if (entry != null) {
                QRView(entry)
                LinearProgressIndicator(
                    progress = { model.qrLeft.toFloat() / Entrust.ENTRY_TTL },
                    modifier = Modifier.fillMaxWidth(),
                    color = Palette.accentBlue,
                )
                Row(Modifier.fillMaxWidth()) {
                    Text("QR ${model.qrLeft}초마다 바뀜", style = tabular(13), color = Palette.muted)
                    Spacer(Modifier.weight(1f))
                    Text("지문 확인 ${model.bioLeft}초 남음", style = tabular(13), color = Palette.muted)
                }
            } else {
                Text(entryTitle(model, terms, time.ok, place.isOK), fontSize = 19.sp, fontWeight = FontWeight.Bold)
                StatusRow("입장 시간", time.text, time.ok)
                StatusRow("공연장까지", placeText(place, terms, loc.providerOff), place.isOK)
                if (place == PlaceCheck.Denied) {
                    GhostButton("위치 권한 켜기") { loc.requestPermission() }
                }
                PrimaryButton(
                    if (model.busy) "확인 중…" else "지문으로 열기",
                    enabled = time.ok && place.isOK && !model.busy && activity != null,
                ) {
                    activity?.let { model.openQr(it, place.isOK) }
                }
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("담당 입구", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.muted)
                Text(terms.gate, style = tabular(56).copy(fontWeight = FontWeight.Black))
            }
        }
        TicketCard(terms)
        Foot("이 앱의 열쇠는 이 폰의 보안 칩(키스토어) 안에만 있어 다른 기기로 복사되지 않고, 기기 암호 · 패턴으로는 열리지 않아요. 위치 조작 앱으로 꾸민 위치는 거절해요.")
    }
}

private fun tabular(size: Int) = TextStyle(fontSize = size.sp, fontFeatureSettings = "tnum")

private fun entryTitle(model: AppModel, terms: Terms, timeOK: Boolean, placeOK: Boolean): String {
    if (!timeOK) return if (model.now < terms.from) "입장은 ${clock(terms.from)}부터" else "입장 시간이 끝났어요"
    return if (placeOK) "지문으로 QR 열기" else "${terms.venue}에 오면 열려요"
}

@Composable
private fun StatusRow(k: String, v: String, ok: Boolean) {
    Row(
        Modifier.fillMaxWidth()
            .border(1.dp, Color.Black.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(k, fontSize = 15.sp)
        Spacer(Modifier.weight(1f))
        Text(v, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (ok) Palette.okGreen else Palette.badRed)
    }
}

private fun placeText(p: PlaceCheck, terms: Terms, providerOff: Boolean): String = when (p) {
    is PlaceCheck.Ok -> "${meters(p.dist)} · 반경 안"
    is PlaceCheck.Far -> "${meters(p.dist)} · 반경 ${meters(terms.r.toInt())} 밖"
    is PlaceCheck.Inaccurate -> "위치가 흐려요(오차 ${meters(p.acc)})"
    PlaceCheck.Simulated -> "꾸민 위치예요(거절)"
    PlaceCheck.Denied -> "위치 권한 꺼짐 · 설정 › ENTRUST"
    PlaceCheck.Waiting -> if (providerOff) "위치(GPS)가 꺼져 있어요 · 설정 › 위치" else "위치 확인 중…"
    PlaceCheck.Stale -> "위치가 오래됐어요"
}
