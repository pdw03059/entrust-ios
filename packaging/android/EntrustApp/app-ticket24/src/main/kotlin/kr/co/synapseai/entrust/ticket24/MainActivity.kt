package kr.co.synapseai.entrust.ticket24

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.Color
import androidx.fragment.app.FragmentActivity
import kr.co.synapseai.entrust.ui.EntrustTheme

class MainActivity : FragmentActivity() {
    private lateinit var store: ShopStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = ShopStore(applicationContext)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            EntrustTheme(primary = T24.brand, background = Color.White) {
                RootView(store)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** ticket24://event#e=… (관리자 앱이 보낸 공연) · ticket24://home(앱 열기만) */
    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "ticket24") return
        store.handle(data.toString())
    }
}
