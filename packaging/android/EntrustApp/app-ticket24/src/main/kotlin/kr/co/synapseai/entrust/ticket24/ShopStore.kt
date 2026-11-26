// 티켓24 — 시험용 가상 예매처 앱. 실제 예매처 앱(공연 · 교통 예매)의 흔한 흐름을 그대로 따른다:
//   홈(배너 · 분류 · 목록) → 상품 상세(공연정보 · 판매정보) → 예매(① 회차 ② 좌석 ③ 결제) → 예매 완료(모바일 티켓) → 예매확인 · 취소
// ENTRUST 를 아는 곳은 EntrustBridge.kt 하나뿐(실제 고객사 앱에 붙일 때 그 파일만 옮김).
// 결제 · 회원 · 개인정보는 없다(가상 결제).
package kr.co.synapseai.entrust.ticket24

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kr.co.synapseai.entrust.core.Entrust
import kr.co.synapseai.entrust.core.ShowEvent
import kr.co.synapseai.entrust.ui.Prefs
import kr.co.synapseai.entrust.ui.clock
import kr.co.synapseai.entrust.ui.meters
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/* ───── 데이터 ───── */
@Serializable
enum class OrderStatus {
    @SerialName("paid") PAID,
    @SerialName("sent") SENT,
    @SerialName("cancelled") CANCELLED,
}

@Serializable
data class Order(
    val id: String,            // 예매번호
    val eventId: String,
    val show: String,
    val venue: String,
    val round: Long,           // 회차 시작 시각
    val seats: List<String>,
    val amount: Int,
    val method: String,
    val at: Long,
    val ck: String,
    val token: String,
    val status: OrderStatus,
)

@Serializable
private data class Saved(
    val events: List<ShowEvent>,
    val orders: List<Order>,
    val sold: Map<String, List<String>>,
    val seq: Int,
)

private val storeJson = Json { ignoreUnknownKeys = true }

class ShopStore(context: Context) {
    val events = mutableStateListOf<ShowEvent>()
    val orders = mutableStateListOf<Order>()
    val sold = mutableStateMapOf<String, List<String>>()
    var toast by mutableStateOf<String?>(null)
    var tab by mutableStateOf(0)

    private val prefs = Prefs(context, "ticket24")
    private val key = "ticket24.store.v1"
    private var seq = 0

    init {
        prefs.get(key)?.let { raw ->
            runCatching { storeJson.decodeFromString(Saved.serializer(), raw) }.getOrNull()?.let { s ->
                events.addAll(s.events); orders.addAll(s.orders); sold.putAll(s.sold); seq = s.seq
            }
        }
        events.removeAll { it.id == "ev_demo" } // 예전 판이 넣던 체험 공연(반경 200m · 항상 열림)은 지움 — 관리자가 보낸 공연만 판다
    }

    private fun save() {
        val s = Saved(events.toList(), orders.toList(), sold.toMap(), seq)
        prefs.put(key, storeJson.encodeToString(Saved.serializer(), s))
    }

    /** ENTRUST 관리자 앱이 보낸 공연(ticket24://event#e=…) — 같은 id 공연은 관리자가 정한 값으로 바꿈 */
    fun handle(url: String) {
        val e = Entrust.urlParam(url, "e")?.let { ShowEvent.decode(it) } ?: return
        val prev = events.firstOrNull { it.id == e.id }
        events.removeAll { it.id == e.id || it.id == "ev_demo" }
        events.add(0, e)
        tab = 0
        val changed = prev?.let { it.lat != e.lat || it.lon != e.lon || it.r != e.r || it.from != e.from || it.to != e.to || it.gate != e.gate } ?: false
        val hasSold = orders.any { it.eventId == e.id && it.status != OrderStatus.CANCELLED }
        toast = if (changed && hasSold) "공연 조건이 바뀌었어요(${meters(e.r)} · ${clock(e.from)}부터). 이미 산 표는 예전 조건 그대로라 새로 예매해야 새 조건이 들어가요."
        else "관리자 앱에서 공연을 받았어요: ${e.show}"
        save()
    }

    fun book(e: ShowEvent, seats: List<String>, method: String): Order {
        seq += 1
        val no = "T" + SimpleDateFormat("yyMMdd", Locale.KOREA).format(Date()) + String.format(Locale.US, "%04d", seq)
        val issued = EntrustBridge.issueTicket(
            EntrustOrderInput(
                orderNo = no, showTitle = e.show, venueName = e.venue, seats = seats, gate = e.gate,
                venueLat = e.lat, venueLon = e.lon, radiusM = e.r, entryFrom = e.from, entryTo = e.to,
            ),
        )
        val amount = seats.sumOf { price(e, it) }
        val o = Order(
            id = no, eventId = e.id, show = e.show, venue = e.venue, round = e.from, seats = seats, amount = amount,
            method = method, at = Entrust.now(), ck = issued.ck, token = issued.token, status = OrderStatus.PAID,
        )
        orders.add(0, o)
        sold[e.id] = (sold[e.id] ?: emptyList()) + seats
        save()
        return o
    }

    fun markSent(id: String) {
        val i = orders.indexOfFirst { it.id == id }
        if (i < 0 || orders[i].status != OrderStatus.PAID) return
        orders[i] = orders[i].copy(status = OrderStatus.SENT)
        save()
    }

    /** 취소 → 검표기(관리자 앱)에 보낼 취소 알림 주소 */
    fun cancel(id: String): String? {
        val i = orders.indexOfFirst { it.id == id }
        if (i < 0 || orders[i].status == OrderStatus.CANCELLED) return null
        val o = orders[i].copy(status = OrderStatus.CANCELLED)
        orders[i] = o
        sold[o.eventId]?.let { list -> sold[o.eventId] = list.filterNot { it in o.seats } }
        save()
        return EntrustBridge.cancelURL(o.ck)
    }

    companion object {
        fun grade(seat: String): String = if (seat.startsWith("A") || seat.startsWith("B")) "R" else "S"
        fun price(e: ShowEvent, seat: String): Int = if (grade(seat) == "R") e.price + 22000 else e.price
    }
}
