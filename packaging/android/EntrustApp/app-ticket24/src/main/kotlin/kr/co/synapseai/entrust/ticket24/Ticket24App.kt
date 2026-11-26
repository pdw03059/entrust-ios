// 티켓24 화면: 홈 · 공연 상세 · 예매(회차 · 좌석 · 결제 · 완료) · 예매확인 · 예매 상세/취소 · ENTRUST 연동
// (아이폰 Shop/Ticket24App.swift 와 같은 흐름 · 문구 · 색)
package kr.co.synapseai.entrust.ticket24

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kr.co.synapseai.entrust.core.ShowEvent
import kr.co.synapseai.entrust.ui.Card
import kr.co.synapseai.entrust.ui.GhostButton
import kr.co.synapseai.entrust.ui.Palette
import kr.co.synapseai.entrust.ui.PrimaryButton
import kr.co.synapseai.entrust.ui.QRView
import kr.co.synapseai.entrust.ui.clock
import kr.co.synapseai.entrust.ui.meters
import kr.co.synapseai.entrust.ui.openLink
import kr.co.synapseai.entrust.ui.won

/** 티켓24 색(아이폰 Color.t24 · t24Soft · bg24 · line 과 같음) */
object T24 {
    val brand = Color(0xFFEE2E3A)      // 티켓24 브랜드
    val soft = Color(0xFFFDECED)
    val bg = Color(0xFFF6F6F8)
    val line = Color.Black.copy(alpha = 0.08f)
    val rSeat = Color(red = 0.55f, green = 0.36f, blue = 0.96f)
    val sSeat = Color(red = 0.13f, green = 0.59f, blue = 0.95f)
    val taken = Color.Gray.copy(alpha = 0.35f)
}

private val ink = Palette.ink
private val muted = Palette.muted

/* ───── 공통 조각 ───── */
/** 포스터(사진 대신 공연마다 다른 색 · 큰 글자) */
@Composable
fun Poster(e: ShowEvent, modifier: Modifier = Modifier, big: Boolean = false) {
    val colors = remember(e.id) { posterColors(e.id) }
    Box(
        modifier
            .clipToBounds()
            .background(Brush.linearGradient(colors))
            .drawBehind {
                val d = (if (big) 260 else 120).dp.toPx()
                val stroke = (if (big) 28 else 14).dp.toPx()
                val offX = (if (big) 150 else 60).dp.toPx()
                val offY = (if (big) -110 else -50).dp.toPx()
                drawCircle(
                    color = Color.White.copy(alpha = 0.18f), radius = d / 2,
                    center = Offset(d / 2 + offX, size.height - d / 2 + offY), style = Stroke(width = stroke),
                )
            },
        contentAlignment = Alignment.BottomStart,
    ) {
        Column(Modifier.padding(if (big) 20.dp else 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text((e.category ?: "공연").uppercase(), fontSize = if (big) 13.sp else 9.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.8f))
            Text(e.show, fontSize = if (big) 26.sp else 14.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (big) Text(e.venue, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.85f))
        }
    }
}

private fun posterColors(id: String): List<Color> {
    val palettes = listOf(
        listOf(Color(red = 0.10f, green = 0.13f, blue = 0.30f), Color(red = 0.93f, green = 0.18f, blue = 0.23f)),
        listOf(Color(red = 0.05f, green = 0.25f, blue = 0.22f), Color(red = 0.95f, green = 0.70f, blue = 0.20f)),
        listOf(Color(red = 0.12f, green = 0.12f, blue = 0.14f), Color(red = 0.12f, green = 0.27f, blue = 0.79f)),
        listOf(Color(red = 0.36f, green = 0.10f, blue = 0.16f), Color(red = 0.98f, green = 0.55f, blue = 0.35f)),
    )
    var h = 0
    for (cp in id.codePoints().toArray()) h = (h * 31 + cp) and 0xFFFF
    return palettes[h % palettes.size]
}

@Composable
fun Badge(text: String, color: Color) {
    Text(
        text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f)).padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
fun BottomBar(content: @Composable RowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color.White)) {
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically, content = content,
        )
    }
}

/** 화면 위 제목줄(아이폰 내비게이션 막대 대신) */
@Composable
private fun TopBar(title: String, onBack: (() -> Unit)? = null, backLabel: String = "‹ 뒤로") {
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).background(Color.White)) {
        if (onBack != null) {
            TextButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Text(backLabel, color = T24.brand, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Text(
            title, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 88.dp),
        )
    }
}

private fun period(e: ShowEvent): String = "${clock(e.from)} ~ ${clock(e.to)}"

private val headline = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold)

/** 표 코드 복사(민감한 내용으로 표시 — 안드로이드 13+ 는 복사 미리보기에 내용을 가림) */
private fun copySensitive(ctx: Context, text: String) {
    val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText("ENTRUST", text)
    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    cm.setPrimaryClip(clip)
}

/** 확인 창(아이폰 confirmationDialog 대신) */
@Composable
private fun ConfirmDialog(title: String, message: String?, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold) },
        text = if (message != null) { { Text(message) } } else null,
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm, color = Palette.badRed, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기", color = ink) } },
        containerColor = Color.White,
    )
}

/* ───── 화면 ───── */
@Composable
fun RootView(store: ShopStore) {
    // 탭마다 따로 쌓이는 화면(아이폰 탭별 NavigationStack)
    var detail by remember { mutableStateOf<ShowEvent?>(null) }
    var booking by remember { mutableStateOf<ShowEvent?>(null) }
    var orderDetail by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = booking == null && store.tab == 0 && detail != null) { detail = null }
    BackHandler(enabled = booking == null && store.tab == 1 && orderDetail != null) { orderDetail = null }

    Box(Modifier.fillMaxSize().background(Color.White)) {
        Scaffold(
            containerColor = Color.White,
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    listOf("홈" to "⌂", "예매확인" to "🎫", "ENTRUST 연동" to "🔗").forEachIndexed { i, (label, icon) ->
                        NavigationBarItem(
                            selected = store.tab == i,
                            onClick = { store.tab = i },
                            icon = { Text(icon, fontSize = 20.sp) },
                            label = { Text(label, fontSize = 11.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = T24.brand, selectedTextColor = T24.brand, indicatorColor = T24.soft,
                                unselectedIconColor = muted, unselectedTextColor = muted,
                            ),
                        )
                    }
                }
            },
        ) { inner ->
            Box(Modifier.fillMaxSize().padding(inner)) {
                when (store.tab) {
                    0 -> {
                        val d = detail
                        if (d == null) HomeView(store) { detail = it }
                        else ShowDetailView(d, onBack = { detail = null }, onBook = { booking = d })
                    }
                    1 -> {
                        val id = orderDetail
                        if (id == null) MyTicketsView(store) { orderDetail = it }
                        else OrderDetailView(store, id) { orderDetail = null }
                    }
                    else -> PartnerView()
                }
            }
        }

        booking?.let { e ->
            key(e.id) { BookingFlow(store, e) { booking = null } }
        }

        store.toast?.let { t ->
            LaunchedEffect(t) { delay(3000); if (store.toast == t) store.toast = null }
            Text(
                t, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp, start = 16.dp, end = 16.dp)
                    .clip(RoundedCornerShape(50)).background(ink.copy(alpha = 0.92f))
                    .clickable { store.toast = null }.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
fun HomeView(store: ShopStore, open: (ShowEvent) -> Unit) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("전체") }
    val categories = listOf("전체", "콘서트", "뮤지컬", "연극", "클래식", "전시")
    val list = store.events.filter { e ->
        (category == "전체" || (e.category ?: "콘서트") == category) &&
            (query.isEmpty() || e.show.contains(query, ignoreCase = true) || e.venue.contains(query, ignoreCase = true))
    }

    Column(
        Modifier.fillMaxSize().background(Color.White).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("티켓24", fontSize = 26.sp, fontWeight = FontWeight.Black, color = T24.brand)
            Text("TICKET", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = muted, modifier = Modifier.padding(bottom = 5.dp))
            Spacer(Modifier.weight(1f))
            Badge("시험용", muted)
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T24.bg).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("🔍", fontSize = 15.sp, color = muted)
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("공연 · 장소 검색", fontSize = 16.sp, color = muted.copy(alpha = 0.7f))
                BasicTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    textStyle = TextStyle(fontSize = 16.sp, color = ink), cursorBrush = SolidColor(T24.brand),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            categories.forEach { c ->
                val on = category == c
                Text(
                    c, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (on) Color.White else ink,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) ink else Color.White)
                        .border(1.dp, T24.line, RoundedCornerShape(50)).clickable { category = c }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }

        list.firstOrNull()?.let { top ->
            Poster(top, Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(18.dp)).clickable { open(top) }, big = true)
        }

        Text("지금 예매 가능", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        if (list.isEmpty()) {
            Box(Modifier.fillMaxWidth().heightIn(min = 80.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (store.events.isEmpty()) "아직 판매 중인 공연이 없어요.\nENTRUST 관리자 앱에서 공연을 만들고 '저장하고 티켓24로 보내기'를 눌러 주세요."
                    else "조건에 맞는 공연이 없어요.",
                    color = muted, textAlign = TextAlign.Center,
                )
            }
        }
        list.forEachIndexed { i, e ->
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(Modifier.fillMaxWidth().clickable { open(e) }, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Poster(e, Modifier.width(84.dp).height(112.dp).clip(RoundedCornerShape(8.dp)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}", fontSize = 15.sp, fontWeight = FontWeight.Black, color = T24.brand)
                            Badge(e.category ?: "콘서트", T24.brand)
                            Badge("모바일티켓", Palette.accentBlue)
                        }
                        Text(e.show, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(e.venue, fontSize = 13.sp, color = muted)
                        Text(period(e), fontSize = 13.sp, color = muted)
                        Text("${won(e.price)}~", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = ink)
                    }
                }
                HorizontalDivider()
            }
        }
        Text("공연은 ENTRUST 관리자 앱에서 만들어 '티켓24로 보내기'를 누르면 여기에 올라와요.", fontSize = 13.sp, color = muted)
    }
}

@Composable
fun ShowDetailView(e: ShowEvent, onBack: () -> Unit, onBook: () -> Unit) {
    var section by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().background(Color.White)) {
        TopBar("", onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Poster(e, Modifier.fillMaxWidth().height(300.dp), big = true)
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Badge(e.category ?: "콘서트", T24.brand); Badge("단독판매", muted) }
                Text(e.show, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Segmented(listOf("공연정보", "판매정보"), section) { section = it }
                if (section == 0) {
                    InfoRow("장소", e.venue)
                    InfoRow("공연기간", period(e))
                    InfoRow("관람시간", "120분(인터미션 없음)")
                    InfoRow("관람등급", "전체 관람가")
                    HorizontalDivider()
                    Text("가격", style = headline)
                    InfoRow("R석", won(e.price + 22000))
                    InfoRow("S석", won(e.price))
                } else {
                    InfoRow("티켓 수령", "모바일 티켓(ENTRUST)")
                    InfoRow("입장 방법", "ENTRUST 앱의 입장 QR — 공연장 반경 ${meters(e.r)} 안 · 입장 시간에만 열림")
                    InfoRow("담당 입구", "${e.gate}번 입구")
                    InfoRow("1인 최대", "3매")
                    InfoRow("취소", "공연 전까지 '예매확인'에서 취소(체험판: 수수료 없음)")
                }
            }
        }
        BottomBar {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("S석", fontSize = 12.sp, color = muted)
                Text("${won(e.price)}~", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            }
            PrimaryButton("예매하기", Modifier.weight(1f), color = T24.brand) { onBook() }
        }
    }
}

@Composable
private fun Segmented(items: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(T24.bg).padding(2.dp)) {
        items.forEachIndexed { i, s ->
            val on = i == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(7.dp)).background(if (on) Color.White else Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) { Text(s, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = ink) }
        }
    }
}

@Composable
private fun InfoRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(k, fontSize = 15.sp, color = muted, modifier = Modifier.width(76.dp))
        Text(v, fontSize = 15.sp, modifier = Modifier.weight(1f))
    }
}

/* ───── 예매: ① 회차 ② 좌석 ③ 결제 ④ 완료 ───── */
@Composable
fun BookingFlow(store: ShopStore, e: ShowEvent, dismiss: () -> Unit) {
    var step by remember { mutableStateOf(1) }
    var roundPicked by remember { mutableStateOf(false) }
    val seats = remember { mutableStateListOf<String>() }
    var method by remember { mutableStateOf("가상 신용카드") }
    var agree by remember { mutableStateOf(false) }
    var paying by remember { mutableStateOf(false) }
    var order by remember { mutableStateOf<Order?>(null) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val total = seats.sumOf { ShopStore.price(e, it) }

    // 전체 화면(아이폰 fullScreenCover): 뒤로 = 닫기(결제 중에는 막음)
    BackHandler { if (!paying) dismiss() }

    fun pay() {
        error = ""
        paying = true
        scope.launch {
            delay(1000) // 결제 대행 응답을 흉내 냄
            try {
                order = store.book(e, seats.toList(), method)
                step = 4
            } catch (ex: Exception) {
                error = "결제 처리에 실패했어요. 공연 정보(장소 · 시간)를 관리자 앱에서 다시 보내 주세요."
            }
            paying = false
        }
    }

    Column(Modifier.fillMaxSize().background(Color.White).systemBarsPadding()) {
        TopBar(if (step == 4) "예매 완료" else e.show, onBack = if (step < 4) ({ if (!paying) dismiss() }) else null, backLabel = "닫기")
        if (step < 4) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
                listOf("회차", "좌석", "결제").forEachIndexed { i, name ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "${i + 1} $name", fontSize = 15.sp,
                            fontWeight = if (step == i + 1) FontWeight.Bold else FontWeight.Normal,
                            color = if (step >= i + 1) ink else muted,
                        )
                        Box(Modifier.fillMaxWidth().height(3.dp).background(if (step >= i + 1) T24.brand else T24.line))
                    }
                }
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (step) {
                1 -> RoundStep(store, e, roundPicked) { roundPicked = true }
                2 -> SeatMap(e, store.sold[e.id] ?: emptyList(), seats)
                3 -> PayStep(e, seats, method, { method = it }, agree, { agree = it })
                else -> order?.let { o -> DoneView(store, o) { dismiss() } }
            }
            if (error.isNotEmpty()) Text(error, color = Palette.badRed, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        if (step < 4) {
            BottomBar {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (seats.isEmpty()) "좌석 미선택" else "${seats.size}매", fontSize = 12.sp, color = muted)
                    Text(won(total), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                }
                when (step) {
                    1 -> PrimaryButton("좌석 선택", Modifier.weight(1f), enabled = roundPicked, color = T24.brand) { step = 2 }
                    2 -> PrimaryButton("다음", Modifier.weight(1f), enabled = seats.isNotEmpty(), color = T24.brand) { step = 3 }
                    else -> PrimaryButton(
                        if (paying) "결제 중…" else "${won(total)} 결제하기", Modifier.weight(1f),
                        enabled = agree && !paying, color = T24.brand,
                    ) { pay() }
                }
            }
        }
    }
}

@Composable
private fun RoundStep(store: ShopStore, e: ShowEvent, picked: Boolean, onPick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("관람일 · 회차", style = headline)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .border(if (picked) 2.dp else 1.dp, if (picked) T24.brand else T24.line, RoundedCornerShape(12.dp))
                .clickable { onPick() }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("1회차", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("입장 ${clock(e.from)} ~ ${clock(e.to)}", fontSize = 13.sp, color = muted)
            }
            Text("잔여 ${96 - (store.sold[e.id]?.size ?: 0)}석", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = T24.brand)
        }
        Text("입장 QR은 위 입장 시간에만, ${e.venue} 반경 ${meters(e.r)} 안에서만 열려요(ENTRUST).", fontSize = 13.sp, color = muted)
    }
}

@Composable
private fun PayStep(
    e: ShowEvent, seats: List<String>, method: String, onMethod: (String) -> Unit, agree: Boolean, onAgree: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("예매 확인", style = headline)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T24.bg).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(e.show, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("${clock(e.from)} · ${e.venue}", fontSize = 13.sp, color = muted)
            seats.forEach { s ->
                Row(Modifier.fillMaxWidth()) {
                    Text("${ShopStore.grade(s)}석 $s", fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text(won(ShopStore.price(e, s)), fontSize = 15.sp)
                }
            }
        }

        Text("티켓 수령 방법", style = headline)
        Row(
            Modifier.fillMaxWidth().border(1.dp, T24.line, RoundedCornerShape(12.dp)).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("▦", fontSize = 20.sp, color = Palette.accentBlue)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("모바일 티켓 · ENTRUST", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("예매 후 ENTRUST 앱으로 받아 지문으로 잠가요. 캡처 · 양도 불가.", fontSize = 13.sp, color = muted)
            }
        }

        Text("결제 수단", style = headline)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("가상 신용카드", "가상 계좌이체", "가상 간편결제", "가상 휴대폰").chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { m ->
                        val on = method == m
                        Box(
                            Modifier.weight(1f).heightIn(min = 46.dp).clip(RoundedCornerShape(10.dp))
                                .background(if (on) T24.soft else Color.White)
                                .border(1.dp, if (on) T24.brand else T24.line, RoundedCornerShape(10.dp))
                                .clickable { onMethod(m) },
                            contentAlignment = Alignment.Center,
                        ) { Text(m, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (on) T24.brand else ink) }
                    }
                }
            }
        }
        Text("시험용 가상 결제예요. 카드 번호 · 개인정보를 받지 않고 돈도 나가지 않아요.", fontSize = 13.sp, color = muted)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("예매 · 취소 규정 및 ENTRUST 모바일 티켓 이용에 동의", fontSize = 15.sp, modifier = Modifier.weight(1f))
            Switch(
                checked = agree, onCheckedChange = onAgree,
                colors = SwitchDefaults.colors(checkedTrackColor = T24.brand, checkedThumbColor = Color.White),
            )
        }
    }
}

@Composable
fun SeatMap(e: ShowEvent, sold: List<String>, picked: MutableList<String>) {
    val rows = listOf("A", "B", "C", "D", "E", "F", "G", "H")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("좌석 선택", style = headline, modifier = Modifier.weight(1f))
            Text("최대 3매", fontSize = 13.sp, color = muted)
        }
        Box(
            Modifier.fillMaxWidth().heightIn(min = 30.dp).clip(RoundedCornerShape(6.dp)).background(T24.bg),
            contentAlignment = Alignment.Center,
        ) { Text("STAGE", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = muted) }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { r ->
                Row(Modifier.widthIn(max = 340.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(r, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = muted, modifier = Modifier.width(14.dp))
                    for (c in 1..12) {
                        val id = "$r-$c"
                        Seat(id, ShopStore.grade(id), id in sold, id in picked) {
                            if (id in picked) picked.remove(id) else if (picked.size < 3) picked.add(id)
                        }
                        if (c == 6) Spacer(Modifier.width(10.dp))
                    }
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend(T24.rSeat, "R석 ${won(e.price + 22000)}")
            Legend(T24.sSeat, "S석 ${won(e.price)}")
            Legend(T24.taken, "선택불가")
        }
        if (picked.isNotEmpty()) {
            Text("선택: " + picked.joinToString(", ") { "${ShopStore.grade(it)}석 $it" }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun RowScope.Seat(id: String, grade: String, taken: Boolean, on: Boolean, toggle: () -> Unit) {
    val base = if (grade == "R") T24.rSeat else T24.sSeat
    val label = "${grade}석 $id${if (taken) " 선택불가" else ""}"
    Box(
        Modifier.weight(1f).height(22.dp).clip(RoundedCornerShape(4.dp))
            .background(if (taken) T24.taken else if (on) T24.brand else base.copy(alpha = 0.85f))
            .clickable(enabled = !taken) { toggle() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (on) Text("✓", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
private fun Legend(c: Color, t: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(c))
        Text(t, fontSize = 12.sp, color = muted)
    }
}

@Composable
fun DoneView(store: ShopStore, o: Order, close: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(T24.brand), contentAlignment = Alignment.Center) {
            Text("✓", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Text("예매가 완료됐어요", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T24.bg).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DoneRow("예매번호", o.id)
            DoneRow("공연", o.show)
            DoneRow("좌석", o.seats.joinToString(", ") { "${ShopStore.grade(it)}석 $it" })
            DoneRow("결제", "${won(o.amount)} · ${o.method}")
        }
        MobileTicketBox(store, o)
        GhostButton("확인") { close() }
    }
}

@Composable
private fun DoneRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(k, fontSize = 15.sp, color = muted, modifier = Modifier.width(64.dp))
        Text(v, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    }
}

/** 모바일 티켓 받기 — 실제 예매처 앱이라면 '예매 완료' · '예매 상세' 화면에 들어갈 상자 */
@Composable
fun MobileTicketBox(store: ShopStore, o: Order) {
    val ctx = LocalContext.current
    val cur = store.orders.firstOrNull { it.id == o.id } ?: o
    var copied by remember { mutableStateOf(false) }
    val sent = cur.status == OrderStatus.SENT
    Column(
        Modifier.fillMaxWidth().border(1.dp, Palette.accentBlue.copy(alpha = 0.35f), RoundedCornerShape(12.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("▦", fontSize = 18.sp, color = Palette.accentBlue)
            Text("모바일 티켓", style = headline, modifier = Modifier.weight(1f))
            Badge(if (sent) "ENTRUST로 보냄" else "받기 전", if (sent) Palette.okGreen else Palette.warnOrange)
        }
        Text("ENTRUST 앱으로 받아 지문으로 잠가야 입장 QR이 열려요.", fontSize = 13.sp, color = muted)
        PrimaryButton("ENTRUST로 표 보내기", color = Palette.accentBlue) {
            if (openLink(ctx, EntrustBridge.handoffURL(cur.token))) store.markSent(cur.id)
            else store.toast = "ENTRUST 앱이 설치돼 있지 않아요"
        }
        Text(
            if (copied) "복사했어요" else "표 코드 복사(다른 폰으로 받을 때)",
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.accentBlue,
            modifier = Modifier.clickable { copySensitive(ctx, cur.token); copied = true }.padding(vertical = 4.dp),
        )
    }
}

@Composable
fun MyTicketsView(store: ShopStore, open: (String) -> Unit) {
    Column(Modifier.fillMaxSize().background(Color.White).verticalScroll(rememberScrollState())) {
        Text("예매확인", fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp))
        if (store.orders.isEmpty()) {
            Text("예매 내역이 없어요.", color = muted, modifier = Modifier.padding(16.dp))
        }
        store.orders.forEach { o ->
            Row(
                Modifier.fillMaxWidth().clickable { open(o.id) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Badge(statusLabel(o.status), statusColor(o.status))
                        Text(o.id, fontSize = 12.sp, color = muted)
                    }
                    Text(o.show, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("${clock(o.round)} · ${o.seats.joinToString(", ")} · ${won(o.amount)}", fontSize = 13.sp, color = muted)
                }
                Text("›", fontSize = 22.sp, color = muted)
            }
            HorizontalDivider(Modifier.padding(start = 16.dp))
        }
    }
}

private fun statusLabel(s: OrderStatus) = when (s) { OrderStatus.PAID -> "예매완료"; OrderStatus.SENT -> "티켓 수령"; OrderStatus.CANCELLED -> "취소" }
private fun statusColor(s: OrderStatus) = when (s) { OrderStatus.PAID -> T24.brand; OrderStatus.SENT -> Palette.okGreen; OrderStatus.CANCELLED -> muted }

@Composable
fun OrderDetailView(store: ShopStore, id: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    val o = store.orders.firstOrNull { it.id == id }
    Column(Modifier.fillMaxSize().background(Color.White)) {
        TopBar("예매 상세", onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (o != null) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(o.show, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("예매번호 ${o.id}", fontSize = 15.sp)
                        Text("관람 ${clock(o.round)} · ${o.venue}", fontSize = 15.sp)
                        Text("좌석 ${o.seats.joinToString(", ")} · ${won(o.amount)}", fontSize = 15.sp)
                    }
                    if (o.status == OrderStatus.CANCELLED) {
                        Text("취소된 예매예요. 검표기에 취소 알림을 보냈다면 입장 QR이 거절돼요.", fontSize = 15.sp, color = muted)
                    } else {
                        MobileTicketBox(store, o)
                        GhostButton("예매 취소") { confirm = true }
                    }
                }
            }
        }
    }
    if (confirm) {
        ConfirmDialog(
            title = "이 예매를 취소할까요?",
            message = "취소하면 ENTRUST 관리자(검표) 앱에 취소 알림을 보내요.",
            confirm = "예매 취소",
            onConfirm = { store.cancel(id)?.let { openLink(ctx, it) } }, // 같은 폰의 관리자(검표) 앱에 취소 알림
            onDismiss = { confirm = false },
        )
    }
}

@Composable
fun PartnerView() {
    val ctx = LocalContext.current
    var code by remember { mutableStateOf(runCatching { EntrustBridge.registrationCode() }.getOrNull()) }
    var partnerId by remember { mutableStateOf(runCatching { EntrustBridge.partnerId }.getOrNull()) }
    var confirmReset by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().background(Color.White).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("ENTRUST 연동", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        Text("티켓24가 판 표만 검표기에서 통과하도록, 처음 한 번 이 예매처를 ENTRUST 관리자(검표) 앱에 등록해요.", fontSize = 15.sp, color = muted)
        PrimaryButton("관리자 앱에 등록(같은 폰)", color = T24.brand) {
            runCatching { EntrustBridge.registrationURL() }.getOrNull()?.let { openLink(ctx, it) }
        }
        Card {
            Text("다른 기기의 검표기라면 이 QR을 찍어요", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            QRView(code)
            Text("예매처 지문 ${partnerId ?: "-"}", fontSize = 13.sp, color = muted)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("연동 구조", style = headline)
            Text(
                "이 앱에서 ENTRUST 를 아는 코드는 EntrustBridge.kt 하나예요. 실제 예매처 앱에는 그 파일만 넣고 '예매 완료 · 예매 상세 · 예매 취소' 화면에서 부르면 됩니다. 서명은 실제로는 예매처 서버가 해요.",
                fontSize = 13.sp, color = muted,
            )
        }
        GhostButton("예매처 열쇠 새로 만들기") { confirmReset = true }
    }
    if (confirmReset) {
        ConfirmDialog(
            title = "열쇠를 새로 만들면 이미 판 표는 검표기에서 거절돼요(다시 등록 필요).",
            message = null,
            confirm = "새로 만들기",
            onConfirm = {
                EntrustBridge.resetKey()
                code = runCatching { EntrustBridge.registrationCode() }.getOrNull()
                partnerId = runCatching { EntrustBridge.partnerId }.getOrNull()
            },
            onDismiss = { confirmReset = false },
        )
    }
}
