// ENTRUST 안드로이드 앱(관객) 상태: 티켓24에서 산 표 받기 → 키스토어 열쇠로 지문 잠금 → 검표기(관리자 앱) 등록
// → 공연장 반경 안 · 입장 시간 안에서만 10초마다 바뀌는 입장 QR. (아이폰 Sources/EntrustApp.swift 의 AppModel 과 같은 흐름)
package kr.co.synapseai.entrust.entrust

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import kotlinx.serialization.json.Json
import kr.co.synapseai.entrust.core.Entrust
import kr.co.synapseai.entrust.core.EntrustException
import kr.co.synapseai.entrust.core.EntrySession
import kr.co.synapseai.entrust.core.StoredTicket
import kr.co.synapseai.entrust.ui.DeviceKey
import kr.co.synapseai.entrust.ui.DeviceKeyException
import kr.co.synapseai.entrust.ui.Prefs
import kr.co.synapseai.entrust.ui.dur

private val storeJson = Json { ignoreUnknownKeys = true }

data class TimeState(val ok: Boolean, val text: String)

class AppModel(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = Prefs(appContext, "entrust")
    private val main = Handler(Looper.getMainLooper())

    var ticket by mutableStateOf<StoredTicket?>(null)
    var notice by mutableStateOf("")
    var busy by mutableStateOf(false)
    var lockText by mutableStateOf<String?>(null)
        private set
    var entryText by mutableStateOf<String?>(null)
        private set
    var qrLeft by mutableIntStateOf(Entrust.ENTRY_TTL)
        private set
    var bioLeft by mutableIntStateOf(Entrust.BIO_TTL)
        private set
    var now by mutableLongStateOf(Entrust.now())
        private set
    var session by mutableStateOf<EntrySession?>(null)
        private set
    private var lastQrAt = 0L

    init {
        ticket = prefs.get(STORE_KEY)?.let { runCatching { storeJson.decodeFromString(StoredTicket.serializer(), it) }.getOrNull() }
        val t = ticket
        if (t != null && t.locked && !DeviceKey.exists) {
            ticket = t.copy(locked = false, registered = false)
        }
    }

    private fun save() {
        val t = ticket
        prefs.put(STORE_KEY, t?.let { storeJson.encodeToString(StoredTicket.serializer(), it) })
    }

    /* 1. 예매처 표 받기 */
    fun receive(text: String) {
        notice = ""
        try {
            val t = Entrust.openTicket(text)
            val cur = ticket
            if (cur != null && cur.terms.ck == t.terms.ck && cur.locked) {
                notice = "이미 받아서 잠근 표예요."
                return
            }
            closeQr()
            DeviceKey.delete()
            lockText = null
            ticket = t
            save()
        } catch (e: EntrustException) {
            notice = if (e.code == "badShopSig") "예매처 서명이 맞지 않아요(표 내용이 바뀌었어요)."
            else "예매처 표 코드를 읽을 수 없어요. 티켓24에서 다시 보내 주세요."
        } catch (e: Exception) {
            notice = "예매처 표 코드를 읽을 수 없어요. 티켓24에서 다시 보내 주세요."
        }
    }

    fun receiveURL(url: String) {
        val t = Entrust.urlParam(url, "t")
        if (!t.isNullOrEmpty()) receive(t)
    }

    /* 2. 키스토어 열쇠로 잠그기: 열쇠를 만들고 지문으로 한 번 서명해 봐서 실제로 쓸 수 있는지 확인 */
    fun lock(activity: FragmentActivity) {
        if (ticket == null || busy) return
        notice = ""
        DeviceKey.biometricProblem(activity)?.let { notice = it; return }
        busy = true
        Thread {
            val pub = try {
                DeviceKey.create(appContext)
            } catch (e: Exception) {
                DeviceKey.delete()
                val msg = describe(e)
                main.post { notice = msg; busy = false }
                return@Thread
            }
            main.post {
                DeviceKey.sign(activity, "ENTRUST-lock-check".toByteArray(Charsets.UTF_8), "표를 이 폰에 잠글게요", "지문을 확인해 주세요") { r ->
                    r.fold(
                        onSuccess = { finishLock(pub) },
                        onFailure = { e ->
                            DeviceKey.delete()
                            notice = describe(e)
                            busy = false
                        },
                    )
                }
            }
        }.start()
    }

    private fun finishLock(pub: ByteArray) {
        busy = false
        val t = ticket?.copy(locked = true) ?: return
        ticket = t
        save()
        setLockQr(t, pub)
    }

    private fun setLockQr(t: StoredTicket, pub: ByteArray) {
        lockText = Entrust.lockQr(t, pub)
    }

    /* 3. 검표기 등록 */
    fun refreshLockQr() {
        val t = ticket ?: return
        if (!t.locked || lockText != null) return
        val pub = runCatching { DeviceKey.publicKey() }.getOrNull() ?: return
        setLockQr(t, pub)
    }

    val adminURL: String?
        get() = lockText?.let { "entrustadmin://lock#l=" + Entrust.urlValue(it) }

    fun markRegistered() {
        val t = ticket?.copy(registered = true) ?: return
        ticket = t
        save()
    }

    /* 4. 입장 QR */
    fun timeState(): TimeState {
        val t = ticket?.terms ?: return TimeState(false, "")
        if (now < t.from) return TimeState(false, "${dur(t.from - now)} 뒤 열림")
        if (now > t.to) return TimeState(false, "끝났어요")
        return TimeState(true, "열림 · ${dur(t.to - now)} 남음")
    }

    fun openQr(activity: FragmentActivity, placeOK: Boolean) {
        val t = ticket ?: return
        if (!placeOK || !timeState().ok || busy) return
        notice = ""
        busy = true
        val pending = try {
            EntrySession.begin(t.terms.ck)
        } catch (e: Exception) {
            notice = describe(e); busy = false; return
        }
        DeviceKey.sign(activity, pending.message, "입장 QR 열기", "입장 QR을 열려면 지문을 확인해 주세요") { r ->
            r.fold(
                onSuccess = { sig ->
                    session = pending.finish(sig)
                    busy = false
                    tick(placeOK = true, force = true)
                },
                onFailure = { e ->
                    notice = describe(e)
                    busy = false
                },
            )
        }
    }

    fun tick(placeOK: Boolean, force: Boolean = false) {
        now = Entrust.now()
        val t = ticket ?: return
        val s = session ?: return
        val bl = Entrust.BIO_TTL - (now - s.bts)
        if (bl <= 0) { closeQr(); return } // 30초가 지나면 다시 지문 확인
        if (!timeState().ok) { closeQr("입장 시간이 끝나 QR을 닫았어요."); return }
        if (!placeOK) { closeQr("공연장 반경을 벗어나 QR을 닫았어요."); return }
        if (force || now - lastQrAt >= Entrust.ENTRY_TTL) {
            lastQrAt = now
            runCatching { s.entryQr(t.terms.ck, now) }.getOrNull()?.let { entryText = it }
        }
        qrLeft = (Entrust.ENTRY_TTL - (now - lastQrAt)).toInt()
        bioLeft = bl.toInt()
    }

    fun closeQr(msg: String = "") {
        session = null
        entryText = null
        if (msg.isNotEmpty()) notice = msg
    }

    fun reset() {
        closeQr()
        DeviceKey.delete()
        ticket = null
        lockText = null
        notice = ""
        save()
    }

    companion object {
        private const val STORE_KEY = "entrust.ticket.v1"

        fun describe(e: Throwable): String {
            val m = e.message
            if (e is DeviceKeyException && m == "취소") return "취소했어요. 다시 눌러 주세요."
            if (!m.isNullOrBlank() && (e is DeviceKeyException)) return m
            return "지문 확인 · 열쇠 사용에 실패했어요. 지문을 새로 등록했다면 표를 다시 받아 주세요."
        }
    }
}
