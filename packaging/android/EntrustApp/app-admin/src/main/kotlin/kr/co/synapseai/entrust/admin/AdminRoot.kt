// 화면 뼈대(탭 3개: 공연 관리 · 검표 · 현황) + 공통 작은 부품
package kr.co.synapseai.entrust.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.co.synapseai.entrust.core.ShowEvent
import kr.co.synapseai.entrust.ui.Palette

@Composable
fun AdminRoot(store: AdminStore) {
    var editing by remember { mutableStateOf<ShowEvent?>(null) }
    val e = editing
    if (e != null) {
        BackHandler { editing = null }
        EventEditor(store, e) { editing = null }
        return
    }
    Scaffold(
        containerColor = Palette.paper,
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                val items = listOf(Triple(0, "📍", "공연 관리"), Triple(1, "▣", "검표"), Triple(2, "☰", "현황"))
                items.forEach { (i, icon, label) ->
                    NavigationBarItem(
                        selected = store.tab == i,
                        onClick = { store.tab = i },
                        icon = { Text(icon, fontSize = 20.sp) },
                        label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedTextColor = Palette.accentBlue, selectedIconColor = Palette.accentBlue,
                            indicatorColor = Palette.accentBlue.copy(alpha = 0.12f),
                        ),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (store.tab) {
                0 -> EventsScreen(store) { editing = it }
                1 -> GateScreen(store)
                else -> StatusScreen(store)
            }
        }
    }
}

/** 화면 큰 제목 */
@Composable
fun ScreenTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Palette.ink, modifier = modifier)
}

/** 묶음(아이폰 Form/List Section): 머리글 · 흰 상자 · 바닥글 */
@Composable
fun Section(header: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (header != null) Text(header, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.muted, modifier = Modifier.padding(horizontal = 4.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), content = content,
        )
        if (footer != null) Text(footer, fontSize = 12.sp, color = Palette.muted, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

/** 고르기(아이폰 Picker) — 가로로 넘기는 칩 */
@Composable
fun <T> ChoiceRow(label: String?, options: List<T>, selected: T, text: (T) -> String, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) Text(label, fontSize = 14.sp, color = Palette.muted)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o -> Chip(text(o), o == selected, Modifier) { onSelect(o) } }
        }
    }
}

/** 칸을 똑같이 나눈 고르기(아이폰 segmented Picker) */
@Composable
fun <T> Segmented(options: List<T>, selected: T, text: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> Chip(text(o), o == selected, Modifier.weight(1f)) { onSelect(o) } }
    }
}

@Composable
fun Chip(title: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.clip(shape)
            .background(if (on) Palette.accentBlue else Color.White)
            .border(1.dp, if (on) Palette.accentBlue else Color.Black.copy(alpha = 0.15f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, color = if (on) Color.White else Palette.ink, fontSize = 15.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium)
    }
}

/** 작은 표시(아이폰 Badge2) */
@Composable
fun Badge2(text: String, color: Color) {
    Text(
        text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** 숫자 폭을 고르게(monospacedDigit) */
val tnum = TextStyle(fontFeatureSettings = "tnum")
