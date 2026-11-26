// ENTRUST 관리자 앱(주최 · 운영자): ① 공연 만들기 — 입장 QR이 열릴 장소(실제 GPS) · 반경 · 시간 · 입구 → 티켓24로 보내기
// ② 검표 — 카메라로 예매처 등록(ES1) · 표 등록(EL2) · 입장 QR(EQ2) · 취소 알림(EC1)을 읽고 판정
// ③ 현황 — 등록된 예매처 · 표 · 입장 · 취소
package kr.co.synapseai.entrust.admin

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import kr.co.synapseai.entrust.ui.EntrustTheme
import kr.co.synapseai.entrust.ui.Palette

class MainActivity : FragmentActivity() {
    private lateinit var store: AdminStore
    private val haptic: (Int) -> Unit = { kind -> performFeedback(kind) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = AdminStore.get(this)
        store.feedback = haptic
        // 다시 만들어진 화면(복원) · 최근 앱에서 연 것은 같은 주소를 두 번 판정하지 않게
        val fromHistory = (intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !fromHistory) handleIntent(intent)
        setContent { EntrustTheme(primary = Palette.accentBlue) { AdminRoot(store) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        if (store.feedback === haptic) store.feedback = null
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "entrustadmin") return
        store.handleURL(uri.toString())
    }

    /** 0 통과 · 1 거절 · 2 안내 — 진동 권한 없이 화면의 촉각 피드백(시스템 설정을 따름) */
    private fun performFeedback(kind: Int) {
        val c = when (kind) {
            0 -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
            1 -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
            else -> HapticFeedbackConstants.LONG_PRESS
        }
        window?.decorView?.performHapticFeedback(c)
    }
}
