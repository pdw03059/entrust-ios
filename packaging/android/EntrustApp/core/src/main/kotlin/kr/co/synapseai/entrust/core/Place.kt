// 공연장 반경 판정(아이폰 Shared/Location.swift 의 PlaceCheck 와 같은 기준). 위치는 폰 밖으로 보내지 않는다.
package kr.co.synapseai.entrust.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** 폰이 잡은 위치 한 점(안드로이드 Location 에서 옮겨 담음) */
data class Fix(val lat: Double, val lon: Double, val accuracy: Double, val timeMs: Long, val mock: Boolean)

sealed class PlaceCheck {
    data class Ok(val dist: Int) : PlaceCheck()
    data class Far(val dist: Int) : PlaceCheck()
    data class Inaccurate(val acc: Int) : PlaceCheck()
    data object Simulated : PlaceCheck()
    data object Denied : PlaceCheck()
    data object Waiting : PlaceCheck()
    data object Stale : PlaceCheck()

    val isOK get() = this is Ok

    companion object {
        /** 두 점 사이 거리(m, 지구 반지름 6371km 하버사인) */
        fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
            val dp = Math.toRadians(lat2 - lat1); val dl = Math.toRadians(lon2 - lon1)
            val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * r * asin(min(1.0, sqrt(a)))
        }

        fun check(lat: Double, lon: Double, radius: Double, fix: Fix?, permitted: Boolean, nowMs: Long = System.currentTimeMillis()): PlaceCheck {
            if (!permitted) return Denied
            val l = fix ?: return Waiting
            if (nowMs - l.timeMs > 60_000) return Stale
            if (l.mock) return Simulated // 위치 조작 앱 · 개발자 옵션 '가짜 위치'로 꾸민 위치
            if (l.accuracy <= 0 || l.accuracy > Entrust.MAX_ACCURACY) return Inaccurate(maxOf(0, l.accuracy.toInt()))
            val dist = distance(l.lat, l.lon, lat, lon)
            if (dist - min(l.accuracy, 50.0) > radius) return Far(dist.toInt())
            return Ok(dist.toInt())
        }
    }
}
