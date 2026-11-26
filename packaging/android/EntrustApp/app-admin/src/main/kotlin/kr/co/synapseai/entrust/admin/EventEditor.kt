// 공연 만들기 · 수정: 이름 · 분류 · 가격 · 장소(위도 · 경도 · 내 위치 · 반경) · 입장 시간 · 담당 입구.
// 아이폰의 지도(MapKit) 대신 위도 · 경도 칸 + '지도 앱에서 보기'(geo: 주소, 구글 지도 API 열쇠 없이).
package kr.co.synapseai.entrust.admin

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.co.synapseai.entrust.core.Entrust
import kr.co.synapseai.entrust.core.ShowEvent
import kr.co.synapseai.entrust.ui.GhostButton
import kr.co.synapseai.entrust.ui.Palette
import kr.co.synapseai.entrust.ui.PrimaryButton
import kr.co.synapseai.entrust.ui.clock
import kr.co.synapseai.entrust.ui.meters
import kr.co.synapseai.entrust.ui.openLink
import kr.co.synapseai.entrust.ui.rememberLocation
import kr.co.synapseai.entrust.ui.won
import java.util.Calendar
import java.util.Locale

private val categories = listOf("콘서트", "뮤지컬", "연극", "클래식", "전시")
private val prices = listOf(33000, 55000, 77000, 99000)
private val radii = listOf(50, 100, 200, 500, 1000, 3000)
private val gates = listOf("1", "2", "3")

private fun fmt6(d: Double) = String.format(Locale.US, "%.6f", d)

/** 날짜 → 시각 차례로 고르기(아이폰 DatePicker 대신 안드로이드 기본 창) */
private fun pickDateTime(ctx: Context, sec: Long, onPick: (Long) -> Unit) {
    val cal = Calendar.getInstance().apply { timeInMillis = sec * 1000 }
    DatePickerDialog(ctx, { _, y, m, d ->
        TimePickerDialog(ctx, { _, h, min ->
            val c = Calendar.getInstance().apply { clear(); set(y, m, d, h, min, 0) }
            onPick(c.timeInMillis / 1000)
        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
}

@Composable
fun EventEditor(store: AdminStore, original: ShowEvent, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var show by remember { mutableStateOf(original.show) }
    var venue by remember { mutableStateOf(original.venue) }
    var category by remember { mutableStateOf(original.category ?: "콘서트") }
    var lat by remember { mutableStateOf(fmt6(original.lat)) }
    var lon by remember { mutableStateOf(fmt6(original.lon)) }
    var r by remember { mutableIntStateOf(original.r) }
    var from by remember { mutableLongStateOf(original.from) }
    var to by remember { mutableLongStateOf(original.to) }
    var gate by remember { mutableStateOf(original.gate) }
    var price by remember { mutableIntStateOf(original.price) }
    var waitingHere by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    // 아이폰 LocationModel.start() · stop(): '지금 내 위치' 를 누른 동안만 위치를 받는다
    val loc = rememberLocation(active = waitingHere)

    fun coord(): Pair<Double, Double>? {
        val a = lat.trim().toDoubleOrNull() ?: return null
        val b = lon.trim().toDoubleOrNull() ?: return null
        if (a < -90 || a > 90 || b < -180 || b > 180) return null
        return a to b
    }

    fun useHere() {
        val f = loc.fix ?: return
        if (System.currentTimeMillis() - f.timeMs >= 30_000 || f.accuracy <= 0) return
        lat = fmt6(f.lat)
        lon = fmt6(f.lon)
        if (venue == "시험 공연장" || venue.isEmpty()) venue = "내 위치(시험)"
        waitingHere = false
    }

    LaunchedEffect(loc.fix, waitingHere) { if (waitingHere) useHere() }

    fun quick(minutes: Int) {
        val start = ((Entrust.now() + minutes * 60L) / 60) * 60
        from = start
        to = start + 3 * 3600
    }

    fun build(): ShowEvent? {
        error = ""
        val c = coord() ?: run { error = "위치(위도 · 경도)를 확인해 주세요."; return null }
        val f = from; val t = to
        if (t <= f) { error = "입장 마감은 입장 시작보다 뒤여야 해요."; return null }
        if (t - f > 7 * 24 * 3600) { error = "입장 시간은 7일 안으로 정해 주세요."; return null }
        val name = show.trim()
        return ShowEvent(
            id = original.id, show = name.ifEmpty { "새 공연" }.take(60),
            venue = venue.ifEmpty { "시험 공연장" }.take(40),
            lat = c.first, lon = c.second, r = r, from = f, to = t, gate = gate, price = price, category = category,
        )
    }

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("닫기", fontSize = 16.sp) }
            Text(
                if (original.show == "새 공연") "새 공연" else "공연 수정",
                fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
            // 가운데 맞춤용 빈 자리(닫기 버튼 폭만큼)
            TextButton(onClick = {}, enabled = false) { Text("닫기", fontSize = 16.sp, color = androidx.compose.ui.graphics.Color.Transparent) }
        }

        Section(header = "공연") {
            OutlinedTextField(show, { show = it }, label = { Text("공연 이름") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ChoiceRow("분류", categories, category, { it }) { category = it }
            ChoiceRow("기본 가격(S석)", prices, price, { won(it) }) { price = it }
        }

        Section(header = "입장 QR이 열리는 장소", footer = "관객 폰이 이 지점에서 반경 안에 있을 때만 입장 QR이 열려요(GPS 오차 50m까지 봐줌).") {
            OutlinedTextField(venue, { venue = it }, label = { Text("장소 이름") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(
                onClick = {
                    waitingHere = true
                    if (!loc.permitted) loc.requestPermission()
                    useHere()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (waitingHere) "위치 찾는 중…" else "📍 지금 내 위치를 공연장으로", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            if (waitingHere && loc.asked && !loc.permitted) {
                Text("위치 권한이 꺼져 있어요. 설정 › 앱 › ENTRUST 관리자 › 권한 › 위치를 켜 주세요.", fontSize = 12.sp, color = Palette.badRed)
            } else if (waitingHere && loc.permitted && loc.providerOff) {
                Text("폰의 위치(GPS)가 꺼져 있어요. 설정에서 위치를 켜 주세요.", fontSize = 12.sp, color = Palette.badRed)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val num = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                OutlinedTextField(lat, { lat = it }, label = { Text("위도") }, singleLine = true, keyboardOptions = num, textStyle = tnum, modifier = Modifier.weight(1f))
                OutlinedTextField(lon, { lon = it }, label = { Text("경도") }, singleLine = true, keyboardOptions = num, textStyle = tnum, modifier = Modifier.weight(1f))
            }
            val c = coord()
            if (c != null) {
                TextButton(onClick = {
                    val a = fmt6(c.first); val b = fmt6(c.second)
                    if (!openLink(ctx, "geo:$a,$b?q=$a,$b")) Toast.makeText(ctx, "지도 앱이 없어요", Toast.LENGTH_SHORT).show()
                }) { Text("🗺 지도 앱에서 보기", fontSize = 14.sp) }
            }
            ChoiceRow("입장 가능 반경", radii, r, { meters(it) }) { r = it }
        }

        Section(header = "입장 QR이 열리는 시간", footer = "이미 팔린 표에는 산 때의 조건이 서명되어 있어요. 바꾼 조건은 이후에 파는 표부터 들어가요.") {
            TimeRow("입장 시작", from) { pickDateTime(ctx, from) { from = it } }
            TimeRow("입장 마감", to) { pickDateTime(ctx, to) { to = it } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { quick(0) }) { Text("지금", fontSize = 13.sp) }
                OutlinedButton(onClick = { quick(2) }) { Text("2분 뒤", fontSize = 13.sp) }
                OutlinedButton(onClick = { quick(10) }) { Text("10분 뒤", fontSize = 13.sp) }
            }
            ChoiceRow("담당 입구", gates, gate, { "${it}번" }) { gate = it }
        }

        if (error.isNotEmpty()) Text(error, color = Palette.badRed, fontSize = 15.sp)

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("저장") { build()?.let { store.upsert(it); onClose() } }
            PrimaryButton("저장하고 티켓24로 보내기") {
                build()?.let {
                    store.upsert(it)
                    sendToShop(ctx, it)
                    onClose()
                }
            }
        }
    }
}

@Composable
private fun TimeRow(label: String, sec: Long, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 16.sp, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onClick) { Text(clock(sec), fontSize = 15.sp, style = tnum) }
    }
}
