// 실제 GPS: 공연장 반경 안인지 확인(판정은 core PlaceCheck). 위치는 폰 밖으로 보내지 않는다.
// 구글 플레이 서비스 없이 안드로이드 기본 LocationManager 만 쓴다. 가짜 위치(위치 조작 앱 · 개발자 옵션)는 isMock 으로 거절.
package kr.co.synapseai.entrust.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kr.co.synapseai.entrust.core.Fix

class LocationState {
    var fix by mutableStateOf<Fix?>(null)
    var permitted by mutableStateOf(false)
    /** 권한을 물었는데 거절됨(다시 물을 수 있게 버튼을 보여 줄 때) */
    var asked by mutableStateOf(false)
    var providerOff by mutableStateOf(false)
    internal var request: () -> Unit = {}
    fun requestPermission() = request()
}

fun Location.toFix(): Fix {
    val mock = if (Build.VERSION.SDK_INT >= 31) isMock else @Suppress("DEPRECATION") isFromMockProvider
    return Fix(latitude, longitude, if (hasAccuracy()) accuracy.toDouble() else -1.0, time, mock)
}

/** 화면에 있는 동안 위치를 받는다(처음이면 권한을 묻는다). active=false 면 멈춘다. */
@SuppressLint("MissingPermission")
@Composable
fun rememberLocation(active: Boolean = true): LocationState {
    val ctx = LocalContext.current
    val st = remember { LocationState() }
    fun granted() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        st.asked = true; st.permitted = granted()
    }
    st.request = { launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
    LaunchedEffect(Unit) {
        st.permitted = granted()
        if (!st.permitted && active) st.request()
    }
    DisposableEffect(active, st.permitted) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = LocationListener { l ->
            val f = l.toFix()
            val cur = st.fix
            // 더 정확하거나 더 새 위치만 받음(망 위치가 GPS 위치를 덮지 않게)
            if (cur == null || f.timeMs - cur.timeMs > 10_000 || f.accuracy in 0.0..cur.accuracy.coerceAtLeast(0.0) || cur.accuracy < 0) st.fix = f
        }
        if (active && st.permitted) {
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            st.providerOff = providers.isEmpty()
            providers.forEach { p ->
                runCatching { lm.getLastKnownLocation(p)?.let { listener.onLocationChanged(it) } }
                runCatching { lm.requestLocationUpdates(p, 1000L, 3f, listener, Looper.getMainLooper()) }
            }
        }
        onDispose { runCatching { lm.removeUpdates(listener) } }
    }
    return st
}
