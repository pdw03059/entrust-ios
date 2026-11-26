// ENTRUST 안드로이드 앱(관객) 진입점: entrust://open#t=… 로 표를 받는다.
package kr.co.synapseai.entrust.entrust

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import kr.co.synapseai.entrust.ui.EntrustTheme
import kr.co.synapseai.entrust.ui.Palette

class MainActivity : FragmentActivity() {
    private lateinit var model: AppModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = AppModel(this)
        // 화면을 다시 만들 때(회전 등)는 같은 주소를 두 번 받지 않는다
        if (savedInstanceState == null) handle(intent)
        setContent {
            EntrustTheme(primary = Palette.accentBlue, background = Palette.paper) {
                ContentView(model)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    // 화면을 떠나면 QR을 닫고 다시 지문 확인(아이폰 scenePhase != .active 와 같은 뜻)
    override fun onStop() {
        super.onStop()
        model.closeQr()
    }

    private fun handle(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "entrust") model.receiveURL(uri.toString())
    }
}
