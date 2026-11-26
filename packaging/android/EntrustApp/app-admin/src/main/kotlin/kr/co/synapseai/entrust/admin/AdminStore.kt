// ENTRUST 관리자 앱 상태(아이폰 Admin/AdminApp.swift AdminStore 와 같음):
// 공연 목록 · 등록된 예매처(ES1) · 표(EL2) · 입장 기록 · 취소(EC1) · 검표 기록 · 담당 입구 — 이 폰 안에만 저장.
package kr.co.synapseai.entrust.admin

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kr.co.synapseai.entrust.core.B45
import kr.co.synapseai.entrust.core.EnteredRec
import kr.co.synapseai.entrust.core.Entrust
import kr.co.synapseai.entrust.core.Gate
import kr.co.synapseai.entrust.core.GateCode
import kr.co.synapseai.entrust.core.GateLock
import kr.co.synapseai.entrust.core.GateResult
import kr.co.synapseai.entrust.core.ShopReg
import kr.co.synapseai.entrust.core.ShowEvent
import kr.co.synapseai.entrust.ui.Prefs
import java.util.UUID

@Serializable
data class LogLine(val id: String, val at: Long, val ok: Boolean, val text: String)

/** kind: 0 통과 · 1 거절 · 2 안내 */
data class Verdict(val kind: Int, val title: String, val text: String)

@Serializable
internal data class Saved(
    val events: List<ShowEvent> = emptyList(),
    val shops: Map<String, ShopReg> = emptyMap(),
    val locks: Map<String, GateLock> = emptyMap(),
    val entered: Map<String, EnteredRec> = emptyMap(),
    val cancelled: List<String> = emptyList(),
    val log: List<LogLine> = emptyList(),
    val gate: String = "1",
)

internal val adminJson = Json { ignoreUnknownKeys = true }

class AdminStore private constructor(context: Context) {
    private val prefs = Prefs(context, "entrustadmin")

    var events by mutableStateOf<List<ShowEvent>>(emptyList()); private set
    var shops by mutableStateOf<Map<String, ShopReg>>(emptyMap()); private set
    var locks by mutableStateOf<Map<String, GateLock>>(emptyMap()); private set
    var entered by mutableStateOf<Map<String, EnteredRec>>(emptyMap()); private set
    var cancelled by mutableStateOf<List<String>>(emptyList()); private set
    var log by mutableStateOf<List<LogLine>>(emptyList()); private set
    var gate by mutableStateOf("1"); private set
    var verdict by mutableStateOf<Verdict?>(null)
    var tab by mutableIntStateOf(0)

    /** 판정마다 손에 느낌(아이폰 UINotificationFeedbackGenerator 대신) — 액티비티가 채운다 */
    var feedback: ((Int) -> Unit)? = null

    init {
        val saved = prefs.get(KEY)?.let { runCatching { adminJson.decodeFromString(Saved.serializer(), it) }.getOrNull() }
        if (saved != null) {
            events = saved.events; shops = saved.shops; locks = saved.locks; entered = saved.entered
            cancelled = saved.cancelled; log = saved.log; gate = saved.gate
        }
    }

    fun save() {
        val s = Saved(events, shops, locks, entered, cancelled, log.takeLast(200), gate)
        runCatching { prefs.put(KEY, adminJson.encodeToString(Saved.serializer(), s)) }
    }

    fun upsert(e: ShowEvent) {
        val i = events.indexOfFirst { it.id == e.id }
        events = if (i >= 0) events.toMutableList().also { it[i] = e } else listOf(e) + events
        save()
    }

    fun delete(e: ShowEvent) {
        events = events.filter { it.id != e.id }
        save()
    }

    fun updateGate(g: String) {
        gate = g
        save()
    }

    private fun show(kind: Int, title: String, text: String) {
        verdict = Verdict(kind, title, text)
        log = log + LogLine(UUID.randomUUID().toString(), Entrust.now(), kind == 0, "$title · $text")
        runCatching { feedback?.invoke(kind) }
        save()
    }

    /** 다른 앱에서 넘어온 것(같은 폰): entrustadmin://shop#k= · lock#l= · cancel#c= */
    fun handleURL(url: String) {
        tab = 1
        val k = Entrust.urlParam(url, "k")
        val l = Entrust.urlParam(url, "l")
        val c = Entrust.urlParam(url, "c")
        when {
            k != null -> handle(k)
            l != null -> handle(l)
            c != null -> handle(c)
        }
    }

    /** QR 글자 하나를 판정(카메라 · 앱 넘김 공통) */
    fun handle(raw: String) {
        val text = raw.trim()
        val now = Entrust.now()
        if (text.startsWith("ES1.")) {
            val s = Gate.parseShopQr(text)
            if (s == null) { show(1, "등록 실패", "예매처 등록 QR을 읽을 수 없어요"); return }
            shops = shops + (s.id to s)
            show(0, "예매처 등록됨", "${s.name} · 지문 ${s.id}")
        } else if (text.startsWith("EL2.")) {
            when (val r = Gate.readLockQr(text, shops, locks)) {
                is GateResult.Ok -> {
                    val l = r.value
                    locks = locks + (l.terms.ck to l)
                    show(0, "표 등록됨", "${l.terms.show} · ${l.terms.seat} · ${l.terms.gate}번 입구")
                }
                is GateResult.Fail -> show(1, "등록 거절", r.code.message)
            }
        } else if (text.startsWith("EC1.")) {
            when (val r = Gate.readCancel(text, shops)) {
                is GateResult.Ok -> {
                    val ck = r.value
                    if (ck !in cancelled) cancelled = cancelled + ck
                    show(2, "취소 알림 받음", "이 표는 이제 입장이 거절돼요")
                }
                is GateResult.Fail -> show(1, "취소 알림 거절", r.code.message)
            }
        } else if (text.startsWith("EQ2.")) {
            when (val r = Gate.verifyEntry(text, locks, entered, cancelled.toSet(), gate, now)) {
                is GateResult.Ok -> {
                    val p = r.value
                    entered = entered + (p.lock.terms.ck to EnteredRec(now))
                    show(0, "통과", "${p.lock.terms.qty}명 · 좌석 ${p.lock.terms.seat}${if (p.synced) " · 동기화 열쇠" else ""}")
                }
                is GateResult.Fail -> {
                    val c = r.code
                    val wrongGateLock = if (c == GateCode.WRONG_GATE) ckOf(text)?.let { locks[it] } else null
                    when {
                        wrongGateLock != null -> show(2, "${wrongGateLock.terms.gate}번 입구", "담당 입구로 안내해 주세요")
                        c == GateCode.NOT_YET -> show(2, "아직 입장 전", c.message)
                        else -> show(1, if (c == GateCode.ALREADY_ENTERED) "이미 입장" else "거절", c.message)
                    }
                }
            }
        } else {
            show(1, "모르는 QR", "ENTRUST QR이 아니에요")
        }
    }

    private fun ckOf(text: String): String? {
        val d = B45.decode(text.substring(4)) ?: return null
        if (d.size <= 2) return null
        val n = d[1].toInt() and 0xFF
        if (d.size < 2 + n) return null
        return String(d, 2, n, Charsets.UTF_8)
    }

    fun resetGate() {
        shops = emptyMap(); locks = emptyMap(); entered = emptyMap(); cancelled = emptyList(); log = emptyList(); verdict = null
        save()
    }

    companion object {
        private const val KEY = "entrustadmin.store.v1"
        @Volatile private var instance: AdminStore? = null

        /** 앱 하나에 하나(화면을 다시 만들어도 같은 상태) */
        fun get(context: Context): AdminStore =
            instance ?: synchronized(this) { instance ?: AdminStore(context.applicationContext).also { instance = it } }

        fun shopURL(e: ShowEvent): String = "ticket24://event#e=" + e.encoded()
    }
}
