// 앱 사이 주소 열기 · 저장(작은 JSON) · 알림 글 — 세 앱 공통.
package kr.co.synapseai.entrust.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.fragment.app.FragmentActivity

/** entrust:// · ticket24:// · entrustadmin:// 같은 앱 주소를 연다. 받을 앱이 없으면 false */
fun openLink(context: Context, url: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: ActivityNotFoundException) { false } catch (e: SecurityException) { false }

/** 화면(Compose) 안에서 지문 창을 띄울 액티비티 찾기 */
tailrec fun Context.fragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.fragmentActivity()
    else -> null
}

/** 앱 안 저장소(이 앱만 읽음, 백업 끔 — 매니페스트 allowBackup=false) */
class Prefs(context: Context, name: String) {
    private val sp = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    fun get(key: String): String? = sp.getString(key, null)
    fun put(key: String, value: String?) { sp.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() }
}
