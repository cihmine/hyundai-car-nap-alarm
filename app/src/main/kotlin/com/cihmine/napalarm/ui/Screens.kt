package com.cihmine.napalarm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cihmine.napalarm.NapController
import com.cihmine.napalarm.NapUi
import com.cihmine.napalarm.core.ControlState
import com.cihmine.napalarm.core.NapStatus
import com.cihmine.napalarm.core.RestoreDecision
import com.cihmine.napalarm.core.SeatPreset
import com.cihmine.napalarm.core.Texts
import com.cihmine.napalarm.vehicle.VehicleState

private val Banner = Color(0xFF3A2F00)
private val BannerText = Color(0xFFFFD54F)
private val Panel = Color(0xFF1E1E26)
private val Accent = Color(0xFFFFB74D)
private val Night = Color(0xFF07070C)

@Composable
fun NapApp(c: NapController) {
    val ui by c.ui.collectAsState()
    val status = ui.nap.session?.status
    val dark = status == NapStatus.RESTING || status == NapStatus.SNOOZED
    Surface(Modifier.fillMaxSize(), color = if (dark) Night else Color(0xFF121218), contentColor = Color.White) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().background(Banner).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("차량 연동 시뮬레이션 · 공조·시트: 모의 차량 · 차량 상태: 모의 ${ui.vehicle.label}", color = BannerText, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                Text("음성: ${ui.sttEngine} / ${ui.ttsEngine}", color = BannerText, fontSize = 15.sp)
            }
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(24.dp)) {
                    when (status) {
                        null, NapStatus.FINISHED -> Setup(ui, c)
                        NapStatus.RESTING, NapStatus.SNOOZED -> Resting(ui, c)
                        NapStatus.RINGING, NapStatus.OVERDUE -> Ringing(ui, c)
                        NapStatus.RESTORE_PENDING -> Restore(ui, c)
                    }
                }
                DemoPanel(ui, c)
            }
        }
    }
}

@Composable
private fun Message(ui: NapUi) {
    ui.message?.let { Text(it, fontSize = 18.sp, color = Accent) }
}

@Composable
private fun MicButton(ui: NapUi, c: NapController) {
    OutlinedButton(onClick = c::listen, enabled = !ui.listening, modifier = Modifier.height(56.dp)) {
        Text(if (ui.listening) "듣는 중…" else "🎤 말하기", fontSize = 18.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Setup(ui: NapUi, c: NapController) {
    val prefs = ui.nap.prefs
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("낮잠 알람", fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text(if (ui.controlAllowed) "주차 중 · 앱이 켜져 있는 동안 알람이 울려요" else "주차 상태가 아니에요 — 휴식을 시작할 수 없어요",
            fontSize = 18.sp, color = if (ui.controlAllowed) Color.LightGray else Accent)
        Text("휴식 시간", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(15, 30, 60).forEach { m -> FilterChip(prefs.lastMinutes == m, { c.setMinutes(m) }, { Text("${m}분", fontSize = 20.sp) }) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { c.setMinutes(prefs.lastMinutes - 5) }) { Text("−5") }
                Text("  ${prefs.lastMinutes}분  ", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { c.setMinutes(prefs.lastMinutes + 5) }) { Text("+5") }
            }
        }
        Text("예상 알람 시각: ${c.time(System.currentTimeMillis() + prefs.lastMinutes * 60_000L)}", fontSize = 20.sp)
        HorizontalDivider()
        Text("휴식 설정(모의 차량)", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("공조 온도", fontSize = 18.sp)
            OutlinedButton(onClick = { c.setTemperature(prefs.temperatureC - 0.5) }) { Text("−") }
            Text("%.1f℃".format(prefs.temperatureC), fontSize = 20.sp)
            OutlinedButton(onClick = { c.setTemperature(prefs.temperatureC + 0.5) }) { Text("+") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SeatPreset.entries.forEach { s -> FilterChip(prefs.seatPreset == s, { c.setSeat(s) }, { Text(s.label) }) }
        }
        ui.pendingMinutes?.let { m ->
            Box(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(Texts.confirmDuration(m, System.currentTimeMillis(), java.time.ZoneId.systemDefault()), fontSize = 20.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { c.onUtterance("네") }) { Text("네") }
                        OutlinedButton(onClick = { c.onUtterance("아니요") }) { Text("아니요") }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { c.startRest() }, enabled = ui.controlAllowed, modifier = Modifier.height(64.dp)) { Text("휴식 시작", fontSize = 22.sp) }
            MicButton(ui, c)
        }
        Message(ui)
        Text(Texts.NOT_GUARANTEE, fontSize = 14.sp, color = Color.Gray)
        History(ui, c)
    }
}

@Composable
private fun Actions(ui: NapUi) {
    val actions = ui.nap.actions
    if (actions.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        actions.forEach { a ->
            val color = when (a.state) {
                ControlState.ACKNOWLEDGED -> Color(0xFF81C784)
                ControlState.REQUESTED -> Color.LightGray
                else -> Accent
            }
            Text("${if (a.isRestore) "복구" else "준비"} · ${a.target.label} ${a.requestedValue} → ${a.state.label}${a.note?.let { " ($it)" } ?: ""}",
                fontSize = 16.sp, color = color)
        }
    }
}

@Composable
private fun Resting(ui: NapUi, c: NapController) {
    val s = ui.nap.session ?: return
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(if (s.status == NapStatus.SNOOZED) "다시 알림 대기 중" else "휴식 중", fontSize = 24.sp, color = Color.Gray)
        Text(Texts.remaining(ui.remainingMs ?: 0), fontSize = 120.sp, fontWeight = FontWeight.Light, color = Color(0xFFB0B0C0))
        Text("${c.time(System.currentTimeMillis() + (ui.remainingMs ?: 0))}에 알려 드려요${if (s.demoShortened) " · 시연용 시간 단축" else ""}", fontSize = 20.sp, color = Color.Gray)
        if (s.needsReconfirm) {
            Box(Modifier.background(Panel, RoundedCornerShape(12.dp)).padding(16.dp)) {
                Column {
                    Text("기기 시각이 바뀌었거나 재부팅됐어요. 이 시각이 맞나요?", fontSize = 18.sp, color = Accent)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = c::confirmSchedule) { Text("맞아요, 계속") }
                        OutlinedButton(onClick = c::stopEarly) { Text("휴식 끝내기") }
                    }
                }
            }
        }
        if (!ui.controlAllowed) Text("주차 상태가 아니에요 — 공조·시트 요청을 막았어요", color = Accent, fontSize = 18.sp)
        Actions(ui)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = c::stopEarly, Modifier.height(56.dp)) { Text("휴식 끝내기", fontSize = 18.sp) }
            MicButton(ui, c)
        }
        Message(ui)
    }
}

@Composable
private fun Ringing(ui: NapUi, c: NapController) {
    val s = ui.nap.session ?: return
    val overdue = s.status == NapStatus.OVERDUE
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(if (overdue) "예정된 알람 시각이 지났어요" else "휴식 시간이 끝났어요", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Accent)
        Text(if (overdue) Texts.OVERDUE else "'일어났어' 또는 '5분 더'라고 말하거나 버튼을 눌러 주세요.", fontSize = 20.sp)
        if (s.snoozeCount > 0) Text("다시 알림 ${s.snoozeCount}번", fontSize = 18.sp, color = Color.Gray)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = c::wake, Modifier.height(80.dp)) { Text("일어났어", fontSize = 26.sp) }
            if (!overdue) OutlinedButton(onClick = c::snooze, Modifier.height(80.dp)) { Text("${ui.nap.prefs.snoozeMinutes}분 더", fontSize = 26.sp) }
            MicButton(ui, c)
        }
        Message(ui)
    }
}

@Composable
private fun Restore(ui: NapUi, c: NapController) {
    val s = ui.nap.session ?: return
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("원래 설정으로 되돌릴까요?", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("종료: ${s.endReason?.label ?: "-"}", fontSize = 18.sp, color = Color.Gray)
        if (!ui.controlAllowed) Text("주차 상태가 아니에요 — 시트·공조를 움직이지 않아요", color = Accent, fontSize = 18.sp)
        ui.restorePlan.forEach { item ->
            Row(
                Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(10.dp)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(item.target.label, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Text("원래 ${item.original ?: "읽지 못함"} · 앱이 바꾼 값 ${item.appSet ?: "없음"} · 지금 ${item.current ?: "-"}", fontSize = 15.sp, color = Color.LightGray)
                    Text(item.decision.label, fontSize = 15.sp, color = if (item.decision == RestoreDecision.CONFIRM_MANUAL_CHANGE) Accent else Color.Gray)
                }
                when (item.decision) {
                    RestoreDecision.RESTORE -> Button(onClick = { c.restore(item) }, enabled = ui.controlAllowed) { Text("되돌리기") }
                    RestoreDecision.CONFIRM_MANUAL_CHANGE -> OutlinedButton(onClick = { c.restore(item) }, enabled = ui.controlAllowed) { Text("그래도 원래 값으로") }
                    RestoreDecision.NOTHING_TO_DO, RestoreDecision.ALREADY_RESTORED -> Unit
                }
            }
        }
        Actions(ui)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = c::finishRestore, Modifier.height(56.dp)) { Text("완료", fontSize = 18.sp) }
        }
        Text("시트는 실제로 움직이지 않아요(모의 차량). 실제 차량에서는 정차·사용자 확인·지원 여부를 확인한 뒤에만 움직여요.", fontSize = 14.sp, color = Color.Gray)
        Message(ui)
    }
}

@Composable
private fun History(ui: NapUi, c: NapController) {
    val list = ui.nap.history.asReversed().take(5)
    if (list.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider()
        Text("최근 기록(앱 세션 시간 — 수면 시간 측정 아님)", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        list.forEach { r ->
            val actual = r.actualMs / 1000
            Text("${c.time(r.startedWallMs)} · 계획 ${Texts.duration(r.requestedSeconds)} · 실제 ${Texts.duration(actual)} · ${r.endReason.label}" +
                (if (r.snoozeCount > 0) " · 다시 알림 ${r.snoozeCount}번" else "") + (if (r.demoShortened) " · 시연 단축" else ""), fontSize = 15.sp, color = Color.LightGray)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DemoPanel(ui: NapUi, c: NapController) {
    var typed by remember { mutableStateOf("") }
    Column(
        Modifier.width(360.dp).fillMaxHeight().background(Panel).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("시연 패널", color = BannerText, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("실제 사용자 화면이 아닌 시연·검증용 입력", fontSize = 12.sp, color = Color.Gray)
        HorizontalDivider()
        Button(onClick = { c.startRest(demo20s = true) }, enabled = ui.nap.session == null && ui.controlAllowed, modifier = Modifier.fillMaxWidth()) {
            Text("20초 타이머로 시작(시간 단축)")
        }
        Text("모의 차량 상태", fontSize = 15.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VehicleState.entries.forEach { v -> FilterChip(ui.vehicle == v, { c.setVehicle(v) }, { Text(v.label) }) }
        }
        Text("모의 차량: ${ui.car.settings.valueOf(com.cihmine.napalarm.core.ControlTarget.CLIMATE)} · ${ui.car.settings.seat?.label}", fontSize = 14.sp)
        Toggle("공조 요청 실패", ui.car.failClimate, c::setFailClimate)
        Toggle("시트 요청 실패", ui.car.failSeat, c::setFailSeat)
        Toggle("차량 응답 없음", ui.car.noResponse, c::setNoResponse)
        OutlinedButton(onClick = { c.manualTemperature(23.0) }, Modifier.fillMaxWidth()) { Text("사용자가 직접 온도 23℃로 변경") }
        OutlinedButton(onClick = c::phoneReservation, Modifier.fillMaxWidth()) { Text("휴대폰 '10분 전 준비' 예약(모의)") }
        HorizontalDivider()
        Text("글자 입력(음성 대체)", fontSize = 15.sp)
        OutlinedTextField(typed, { typed = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { c.onUtterance(typed); typed = "" }, enabled = typed.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("말한 것으로 보내기") }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(on, set)
    }
}
