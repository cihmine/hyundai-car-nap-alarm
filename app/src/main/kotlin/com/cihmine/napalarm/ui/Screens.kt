package com.cihmine.napalarm.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.graphics.lerp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.unit.min
import androidx.compose.foundation.Canvas
import com.cihmine.napalarm.core.ModeView
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.IntrinsicSize
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.window.DialogProperties
import com.cihmine.napalarm.core.modeViews
import com.cihmine.napalarm.core.activeView
import com.cihmine.napalarm.core.CustomModes
import androidx.compose.ui.unit.TextUnit
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cihmine.napalarm.NapController
import com.cihmine.napalarm.NapUi
import com.cihmine.napalarm.core.AlarmReason
import com.cihmine.napalarm.core.ControlState
import com.cihmine.napalarm.core.PowerSource
import com.cihmine.napalarm.core.ReserveSettings
import com.cihmine.napalarm.core.ReserveUnit
import com.cihmine.napalarm.core.RuntimeEstimate
import com.cihmine.napalarm.core.VehicleProfile
import com.cihmine.napalarm.core.VehicleKind
import com.cihmine.napalarm.core.NapStatus
import com.cihmine.napalarm.core.RestoreDecision
import com.cihmine.napalarm.core.RestMode
import com.cihmine.napalarm.core.ModeSetting
import com.cihmine.napalarm.core.SeatPreset
import com.cihmine.napalarm.core.Texts
import com.cihmine.napalarm.vehicle.MockEnergyState
import com.cihmine.napalarm.vehicle.VehicleState
import kotlinx.coroutines.delay

private val Banner = Color(0xFF3A2F00)
private val BannerText = Color(0xFFFFD54F)
private val Panel = Color(0xFF1E1E26)
private val Accent = Color(0xFFFFB74D)
private val GoodPower = Color(0xFF66BB6A)
private val LowPower = Color(0xFFEF5350)
private val Night = Color(0xFF07070C)

@Composable
fun NapApp(c: NapController) {
    val ui by c.ui.collectAsState()
    val status = ui.nap.session?.status
    val dark = status == NapStatus.RESTING || status == NapStatus.SNOOZED
    Surface(Modifier.fillMaxSize(), color = if (dark) Night else Color(0xFF121218), contentColor = Color.White) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().background(Banner).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("차량 연동 시뮬레이션 · ${when {
                    ui.realEvInUse && ui.realLoadInUse -> "시트·장치 제어: 모의 · 전기차 잔량: 실제 · 소비 전력: 공조 상태로 추정"
                    ui.realEvInUse -> "공조·시트·장치·소비 전력: 모의 · 전기차 잔량: 실제"
                    else -> "공조·시트·전원·장치: 모의"
                }} · 차량 상태: ${ui.gearLabel?.let { "실제 기어 $it" } ?: "모의 ${ui.vehicle.label}"}", color = BannerText, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    if (ui.demoPanel) "시연 패널 닫기 ▸" else "◂ 시연 패널",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClick = c::toggleDemoPanel),
                )
            }
            // 낮잠(휴식 중·원래대로 되돌리기)도 메인 화면의 옆 창에서 끝난다. 깨워야 하는 알람만 전체 화면
            val main = status != NapStatus.RINGING && status != NapStatus.OVERDUE
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (main) MainScreen(ui, c)
                    else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        ui.sceneCaption?.let { SceneCaption(it) }
                        when (status) {
                            NapStatus.RESTING, NapStatus.SNOOZED -> Resting(ui, c)
                            NapStatus.RINGING, NapStatus.OVERDUE -> Ringing(ui, c)
                            NapStatus.RESTORE_PENDING -> Restore(ui, c)
                            else -> Unit
                        }
                    }
                }
                if (ui.demoPanel) DemoPanel(ui, c)
            }
        }
    }
}

/** 시연 장면 진행 안내 — 심사자가 지금 무엇을 보고 있는지 */
@Composable
private fun SceneCaption(text: String) {
    Box(Modifier.fillMaxWidth().background(Banner, RoundedCornerShape(12.dp)).padding(16.dp)) {
        Text("▶ 시연  $text", color = BannerText, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * 전원·남은 사용 시간 카드. 계산식을 그대로 보여 주고, 계산할 수 없으면 숫자 대신 이유를 보여 준다.
 * 12V 시동 배터리는 전압만으로 계산하지 않는다는 점도 여기서 드러낸다.
 */
@Composable
private fun EnergyCard(ui: NapUi, big: Boolean) {
    val profile = ui.nap.guard?.profile ?: ui.nap.prefs.profile
    val reserve = ui.nap.prefs.reserve
    Box(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("전력 · ${profile.label}" + (profile.managed?.let { " · 계산 전원: ${it.label}" } ?: ""), fontSize = 17.sp, color = Color.LightGray)
            when (val e = ui.estimate) {
                is RuntimeEstimate.Available -> {
                    if (e.minutes <= 0.0) Text("보존량에 도달했어요 — 남은 사용 시간 없음", fontSize = if (big) 34.sp else 24.sp, fontWeight = FontWeight.Bold, color = Accent)
                    else Text("장치 사용 가능 예상  ${Texts.durationMinutes(e.minutes)}", fontSize = if (big) 34.sp else 24.sp, fontWeight = FontWeight.Bold, color = Accent)
                    Text("계산: ${e.formula}", fontSize = 17.sp)
                    Text("(남은 에너지 − 보존량) ÷ 지금 소비 전력 · 현재 소비 기준 예상${when {
                        ui.realEvInUse && ui.realLoadInUse -> " · 잔량은 실제 값, 소비 전력은 공조 상태로 추정"
                        ui.realEvInUse -> " · 잔량은 실제 값, 소비 전력은 모의"
                        e.simulated -> " · 모의 입력"
                        else -> ""
                    }}", fontSize = 14.sp, color = Color.Gray)
                }
                is RuntimeEstimate.Charging -> Text("충전/전원 공급 중 — 분리 후 다시 계산해요", fontSize = 22.sp, color = Accent)
                is RuntimeEstimate.NoDrain -> Text("소비 없음 — 시간을 계산하지 않아요", fontSize = 22.sp, color = Accent)
                is RuntimeEstimate.Unknown -> Text(e.reason.label, fontSize = 22.sp, color = Accent)
                null -> Text("계산 중…", fontSize = 20.sp)
            }
            when (profile.managed) {
                PowerSource.EV_TRACTION -> Text("보존량: ${reserve.evLabel} 남기기 · 기준 ${reserve.warningLeadMinutes}분 전에 먼저 깨움 · 끌 장치: 모의 공조", fontSize = 15.sp, color = Color.LightGray)
                PowerSource.CAMPING -> Text("보존량: 캠핑 배터리 ${reserve.campingReserveWh.toInt()}Wh 남기기 · 기준 ${reserve.warningLeadMinutes}분 전에 먼저 깨움 · 끌 장치: 모의 캠핑 조명", fontSize = 15.sp, color = Color.LightGray)
                else -> Unit
            }
            if (PowerSource.STARTER_12V in profile.sources) {
                Text("12V 시동 배터리: 모의 ${"%.1f".format(ui.energy.starterVoltage)}V · 전압만으로 남은 시간·재시동을 판단하지 않고 차량 경고 신호만 알려요", fontSize = 15.sp, color = Color.LightGray)
            }
            ui.nap.guard?.let { g ->
                Text("전력 보호: ${g.status.simpleLabel}" + if (g.shedLoads.isNotEmpty()) " (${g.shedLoads.joinToString { it.label }})" else "", fontSize = 18.sp,
                    color = if (g.status.shed || g.warned) Accent else Color(0xFF81C784))
            }
        }
    }
}

@Composable
private fun Message(ui: NapUi) {
    ui.message?.let { Text(it, fontSize = 18.sp, color = Accent) }
}

/** 상단 남은 시간·가운데 원이 같이 쓰는 전원 표시값. 계산할 수 없으면 ratio 가 null */
private data class PowerView(
    val ratio: Double?, val reserveAt: Double?, val big: String, val sub: String,
    val time: String, val watts: Double?, val color: Color,
    /** 전기차 주행 가능 거리(km). 모르면 null */
    val rangeKm: Double? = null,
)

/** 지금 전기차 충전률·주행 가능 거리. 실제 충전률을 쓰면 거리도 차량 값만 쓴다 */
private fun evNow(ui: NapUi): Pair<Double, Double?> =
    ui.realEv?.let { it.socPercent to it.rangeKm } ?: (ui.energy.evSoc to ui.energy.evRangeKm)

private fun powerView(ui: NapUi): PowerView {
    val reserve = ui.nap.prefs.reserve
    val mock = if (ui.realEvInUse) " · 잔량 실제 값" else " (모의)"
    data class P(val fill: Double?, val reserveAt: Double?, val big: String, val sub: String)
    val (evSoc, evRange) = evNow(ui)
    val p = when (ui.nap.prefs.profile.managed) {
        PowerSource.EV_TRACTION -> P(evSoc / 100, reserve.evSocFor(evSoc, evRange)?.let { it / 100 }, "${evSoc.toInt()}%", "전기차 배터리$mock")
        PowerSource.CAMPING -> {
            val full = MockEnergyState().campingWh
            val r = ui.energy.campingWh / full
            P(r, reserve.campingReserveWh / full, "${(r * 100).toInt()}%", "캠핑 배터리 ${ui.energy.campingWh.toInt()}Wh (모의)")
        }
        // 내연기관차: 시동을 끈 채 쓸 수 있는 시간은 12V 로 계산하지 않는다(기름 양·12V 원은 BatteryStage 에서 따로)
        else -> P(null, null, "12V", "남은 시간 계산 불가")
    }
    val time = when (val e = ui.estimate) {
        is RuntimeEstimate.Available -> if (e.minutes <= 0.0) "보존량 도달" else Texts.roughMinutes(e.minutes)
        is RuntimeEstimate.Charging -> "충전 중"
        is RuntimeEstimate.NoDrain -> "소비 없음"
        is RuntimeEstimate.Unknown -> "계산 불가"
        null -> "계산 중…"
    }
    val ratio = p.fill?.coerceIn(0.0, 1.0)
    val watts = (ui.estimate as? RuntimeEstimate.Available)?.let { ui.realLoad?.watts?.takeIf { ui.realLoadInUse } }
        ?: when (ui.nap.prefs.profile.managed) {
            PowerSource.EV_TRACTION -> ui.energy.evLoadW
            PowerSource.CAMPING -> ui.energy.campingLoadW
            else -> null
        }
    return PowerView(ratio, p.reserveAt, p.big, p.sub, time, watts, ratio?.let { batteryColor(it, p.reserveAt) } ?: Color.Gray,
        rangeKm = evRange.takeIf { ui.nap.prefs.profile.managed == PowerSource.EV_TRACTION })
}

private val MidPower = Color(0xFFFFD54F)
private val WarnPower = Color(0xFFFF9800)

/**
 * 배터리 색: 남길 보존량까지 쓸 수 있는 양 중 얼마나 남았는지(t)에 따라 초록 → 노랑 → 주황 → 빨강으로 부드럽게 바뀐다.
 * t 0.6 이상 초록, 0.35 노랑, 0.15 주황, 보존량에 닿으면 빨강.
 */
private fun batteryColor(ratio: Double, reserveAt: Double?): Color {
    val r = (reserveAt ?: 0.0).coerceIn(0.0, 0.95)
    val t = ((ratio - r) / (1 - r)).coerceIn(0.0, 1.0).toFloat()
    return when {
        t >= 0.6f -> GoodPower
        t >= 0.35f -> lerp(MidPower, GoodPower, (t - 0.35f) / 0.25f)
        t >= 0.15f -> lerp(WarnPower, MidPower, (t - 0.15f) / 0.2f)
        else -> lerp(LowPower, WarnPower, t / 0.15f)
    }
}

private const val PANEL_ADD = "add"

/**
 * 메인 화면: 가운데 초록 배터리 원, 오른쪽 위 남은 시간, 나머지는 비운다. 아래는 하단바.
 * 하단바를 누르면 오른쪽에서 창이 왼쪽으로 밀고 나오고, 원과 남은 시간은 그만큼 왼쪽으로 밀린다.
 * 같은 탭을 다시 누르거나 ✕ 를 누르면 창이 닫힌다.
 */
@Composable
private fun MainScreen(ui: NapUi, c: NapController) {
    var open by remember { mutableStateOf<String?>(null) }
    // 닫히는 동안에도 내용을 그리도록 마지막으로 연 창을 기억한다
    var last by remember { mutableStateOf<String?>(null) }
    if (open != null) last = open
    val keys = ui.nap.prefs.modeViews().map { it.key }
    // 지운 모드의 창은 닫는다
    LaunchedEffect(keys) { if (open != null && open != PANEL_ADD && open !in keys) open = null }
    // 낮잠이 시작되거나 알람 뒤 되돌리기 단계가 되면 낮잠 창을 연다
    val napStage = ui.nap.session?.status.takeIf { it == NapStatus.RESTING || it == NapStatus.SNOOZED || it == NapStatus.RESTORE_PENDING }
    LaunchedEffect(napStage) { if (napStage != null) open = RestMode.NAP.name }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            BatteryStage(ui, c, Modifier.weight(1f).fillMaxHeight())
            AnimatedVisibility(
                visible = open != null,
                enter = expandHorizontally(tween(320), expandFrom = Alignment.Start) + slideInHorizontally(tween(320)) { it },
                exit = shrinkHorizontally(tween(260), shrinkTowards = Alignment.Start) + slideOutHorizontally(tween(260)) { it },
            ) {
                SidePanel(ui, c, last.orEmpty()) { open = null }
            }
        }
        ModeBar(ui, open) { key ->
            if (key == PANEL_ADD) {
                open = if (open == key) null else key
                return@ModeBar
            }
            // 탭은 그 모드 창만 열고 닫는다. 켜기·끄기는 창 안의 큰 버튼으로만(누르기만 해도 차 설정이 바뀌지 않게, 2026-11-13).
            open = if (open == key) null else key
        }
    }
}

/** 원 하나의 표시값. ratio 가 null 이면 빈 회색 원(값 없음) */
private data class GaugeSpec(val ratio: Double?, val reserveAt: Double?, val color: Color, val big: String, val sub: String)

/** 배터리·연료 원. 채운 호 = 남은 양, 흰 눈금 = 남길 보존량. 값이 바뀌면 길이와 색이 부드럽게 바뀐다 */
@Composable
private fun Gauge(g: GaugeSpec, size: androidx.compose.ui.unit.Dp, stroke: androidx.compose.ui.unit.Dp, bigSize: TextUnit, subSize: TextUnit, extra: @Composable () -> Unit = {}) {
    val sweep by animateFloatAsState((g.ratio ?: 0.0).toFloat(), tween(900), label = "gauge")
    val ringColor by animateColorAsState(g.color, tween(900), label = "gaugeColor")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx()
            val inset = w / 2
            val arc = androidx.compose.ui.geometry.Size(this.size.width - w, this.size.height - w)
            drawArc(ringColor.copy(alpha = 0.16f), 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(w))
            if (g.ratio != null) drawArc(ringColor, -90f, 360 * sweep, false, Offset(inset, inset), arc, style = Stroke(w, cap = StrokeCap.Round))
            g.reserveAt?.takeIf { g.ratio != null && it in 0.0..1.0 }?.let { at ->
                val ang = Math.toRadians(360 * at - 90)
                val rOut = this.size.width / 2
                val rIn = rOut - w
                val cx = this.size.width / 2
                val cy = this.size.height / 2
                drawLine(Color.White, Offset(cx + (rIn * cos(ang)).toFloat(), cy + (rIn * sin(ang)).toFloat()),
                    Offset(cx + (rOut * cos(ang)).toFloat(), cy + (rOut * sin(ang)).toFloat()), strokeWidth = 5.dp.toPx())
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(g.big, fontSize = bigSize, fontWeight = FontWeight.Bold, color = if (g.ratio == null) Color.Gray else Color.White)
            Text(g.sub, fontSize = subSize, color = Color.LightGray, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
            Spacer(Modifier.height(8.dp))
            extra()
        }
    }
}

/** 가운데 배터리 원과 오른쪽 위 남은 시간. 안내 글은 왼쪽 아래에 작게 */
@Composable
private fun BatteryStage(ui: NapUi, c: NapController, modifier: Modifier) {
    val pv = powerView(ui)
    val active = ui.nap.prefs.activeView()
    var reserveDialog by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier.padding(28.dp)) {
        // 왼쪽 위: 무엇을 하는 앱인지 한 줄
        Text("주차한 차에서 쉬기", fontSize = 20.sp, color = Color.Gray, modifier = Modifier.align(Alignment.TopStart))
        // 오른쪽 위: 남길 배터리까지 쓸 수 있는 예상 시간(낮잠 타이머가 아니다)
        Column(Modifier.align(Alignment.TopEnd), horizontalAlignment = Alignment.End) {
            Text("설정한 잔량까지 예상", fontSize = 18.sp, color = Color.Gray)
            Text(pv.time, fontSize = 44.sp, fontWeight = FontWeight.Bold, color = pv.color, maxLines = 1, softWrap = false)
            Text("예상 · 현재 소비 기준" + (pv.watts?.let { " ${RuntimeEstimate.w(it)}" } ?: ""), fontSize = 17.sp, color = Color.Gray)
            // 남은 주행 가능 거리(전기차). 실제 충전률을 쓰는데 차량이 거리를 안 주면 숨기지 않고 '확인 불가'
            if (ui.nap.prefs.profile.managed == PowerSource.EV_TRACTION) {
                Spacer(Modifier.height(14.dp))
                Text("지금 주행 가능", fontSize = 18.sp, color = Color.Gray)
                Text(pv.rangeKm?.let { "${it.toInt()}km" } ?: "확인 불가", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(if (ui.realEvInUse) "차량 표시 값" else "모의 · 전비 5km/kWh 어림", fontSize = 17.sp, color = Color.Gray)
            }
            if (ui.nap.prefs.profile.managed == PowerSource.CAMPING) Text("캠핑 배터리 기준 (모의)", fontSize = 17.sp, color = Accent)
        }
        val modeLine: @Composable () -> Unit = {
            val st = ui.nap.session?.status
            val napping = st == NapStatus.RESTING || st == NapStatus.SNOOZED || st == NapStatus.RESTORE_PENDING
            Text(when {
                st == NapStatus.RESTORE_PENDING -> "😴 낮잠 끝 · 되돌리기"
                // 원 안은 좁아 짧게(알림 시각은 옆 낮잠 창에 있다)
                napping -> "😴 알람까지 ${Texts.remaining(ui.remainingMs ?: 0)}"
                else -> active?.let { "${it.icon} ${it.name} 모드 켜짐" } ?: "모드 꺼짐"
            }, fontSize = if (napping) 20.sp else 22.sp,
                color = if (active != null || napping) Accent else Color.Gray, fontWeight = if (active != null || napping) FontWeight.Bold else FontWeight.Normal)
        }
        val profile = ui.nap.prefs.profile
        if (profile.managed == PowerSource.EV_TRACTION) {
            // 전기차: 가운데 큰 배터리 원 하나(흰 눈금 = 남길 보존량) + 원 아래 '30% 남기기 · 변경'
            val big = min(maxHeight * 0.8f, maxWidth * 0.62f)
            // 옆 창이 열려 원이 작아지면 '· 변경'을 빼고 글씨를 줄여 원 안에 들어가게 한다
            val narrow = big < maxHeight * 0.7f
            Box(Modifier.align(Alignment.Center)) {
                Gauge(GaugeSpec(pv.ratio, pv.reserveAt, pv.color, pv.big, pv.sub), big, 30.dp, 84.sp, 18.sp) {
                    modeLine()
                    Spacer(Modifier.height(10.dp))
                    Text("│ ${ui.nap.prefs.reserve.evLabel} 남기기" + if (narrow) "" else " · 변경", fontSize = if (narrow) 17.sp else 19.sp, color = Color.White,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.12f))
                            .clickable { reserveDialog = true }.padding(horizontal = if (narrow) 14.dp else 18.dp, vertical = 8.dp))
                }
            }
        } else {
            // 기름차: 작은 원 두 개 — 남은 기름 양, 12V 전력량. 12V 잔량은 지금 SDK 로 읽을 방법이 없어 "정보 없음"(임시)
            val fuel = ui.fuelPercent?.let { f -> (f / 100.0).let { r -> GaugeSpec(r, null, batteryColor(r, null), "$f%", "남은 기름 · 실제 값") } }
                ?: GaugeSpec(null, null, Color.Gray, "확인 불가", "남은 기름 · 값을 받지 못함")
            val starter = GaugeSpec(null, null, Color.Gray, "정보 없음", "12V · 잔량 확인 미지원")
            // 캠핑 배터리는 차와 따로인 기기라 읽을 방법이 없어 원으로 보여 주지 않는다(시연 장면 2 의 모의 값만 남은 시간에 쓰고 그렇다고 표시)
            val gauges = listOf(fuel, starter)
            val each = min(maxHeight * 0.58f, maxWidth * (0.86f / gauges.size))
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    gauges.forEach { Gauge(it, each, 20.dp, 52.sp, 15.sp) }
                }
                modeLine()
            }
        }
        // 왼쪽 아래: 시연 장면·알림 문구
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth(0.5f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ui.sceneCaption?.let { SceneCaption(it) }
            if (!ui.controlAllowed) Text("주차 상태가 아니에요 — 모드·휴식을 시작할 수 없어요", fontSize = 17.sp, color = Accent)
            ui.message?.let { Text(it, fontSize = 17.sp, color = Accent) }
        }
        // 남길 배터리 바꾸기: 대화창(별도 창)은 앱 화면 배율을 받지 않아 글자가 작아져서, 화면 안 카드로 띄운다
        if (reserveDialog) Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable { reserveDialog = false },
            contentAlignment = Alignment.Center,
        ) { ReserveCard(ui, c) { reserveDialog = false } }
    }
}

/** 남길 배터리를 지금 단위로 한 칸(5% 또는 20km) 올리거나 내린다. 범위는 컨트롤러가 자른다 */
private fun stepReserve(r: ReserveSettings, dir: Int): ReserveSettings =
    if (r.evUnit == ReserveUnit.KM) r.copy(evReserveKm = r.evReserveKm + 20 * dir) else r.copy(evReserveSoc = r.evReserveSoc + 5 * dir)

/** 남길 배터리(보존량) 바꾸기. 휴식 중에는 보호 기준이 바뀌지 않게 막고 이유를 보여 준다 */
@Composable
private fun ReserveCard(ui: NapUi, c: NapController, close: () -> Unit) {
    val r = ui.nap.prefs.reserve
    val locked = ui.nap.active
    Column(
        Modifier.width(620.dp).background(Panel, RoundedCornerShape(24.dp)).clickable(enabled = false) {}.padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("남길 배터리", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("배터리가 이만큼 남으면 깨우고 정해 둔 장치를 꺼요. 원의 흰 눈금이 이 위치예요.", fontSize = 19.sp, color = Color.LightGray)
        // 단위: 퍼센트 또는 주행 가능 거리(km)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(ReserveUnit.PERCENT to "% 로 정하기", ReserveUnit.KM to "km 로 정하기").forEach { (unit, label) ->
                FilterChip(r.evUnit == unit, { c.setReserve(r.copy(evUnit = unit)) }, { Text(label, fontSize = 20.sp) },
                    enabled = !locked, modifier = Modifier.height(56.dp))
            }
        }
        val step = if (r.evUnit == ReserveUnit.KM) 20 else 5
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            OutlinedButton(onClick = { c.setReserve(stepReserve(r, -1)) }, enabled = !locked, modifier = Modifier.height(68.dp)) { Text("−$step", fontSize = 24.sp) }
            Text(r.evLabel, fontSize = 48.sp, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = { c.setReserve(stepReserve(r, +1)) }, enabled = !locked, modifier = Modifier.height(68.dp)) { Text("+$step", fontSize = 24.sp) }
        }
        // 다른 단위로 지금 얼마인지 함께 보여 준다
        val (soc, range) = evNow(ui)
        val other = when (r.evUnit) {
            ReserveUnit.PERCENT -> range?.takeIf { soc > 0 }?.let { "지금 기준 약 ${(r.evReserveSoc * it / soc).toInt()}km 남겨요" }
            ReserveUnit.KM -> r.evSocFor(soc, range)?.let { "지금 기준 배터리 약 ${it.toInt()}% 남겨요" }
                ?: "주행 가능 거리를 읽지 못해 km 기준을 계산할 수 없어요 — % 로 정해 주세요"
        }
        other?.let { Text(it, fontSize = 19.sp, color = if (r.evUnit == ReserveUnit.KM && r.evSocFor(soc, range) == null) Accent else Color.White) }
        if (locked) Text("휴식 종료 후 변경할 수 있어요", fontSize = 19.sp, color = Accent)
        else Text(if (r.evUnit == ReserveUnit.KM) "20km~400km 사이로 정할 수 있어요 · 주행 가능 거리는 차량 계산값이라 바뀔 수 있어요" else "10%~80% 사이로 정할 수 있어요",
            fontSize = 16.sp, color = Color.Gray)
        Button(onClick = close, modifier = Modifier.fillMaxWidth().height(64.dp)) { Text("확인", fontSize = 22.sp) }
    }
}

/** 하단바를 누르면 오른쪽에서 나오는 창: 모드 켜기·설정, 모드 추가 */
@Composable
private fun SidePanel(ui: NapUi, c: NapController, key: String, close: () -> Unit) {
    Column(
        Modifier.width(560.dp).fillMaxHeight()
            .background(Panel, RoundedCornerShape(topStart = 24.dp))
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (key) {
            // 주행·상태 불명: 이름 입력 같은 편집 화면을 열지 않는다
            PANEL_ADD -> if (!ui.controlAllowed) { PanelHeader("＋  모드 추가", close); ParkFirst() }
                else AddModeForm(close) { name, icon, setting -> if (c.addCustomMode(name, icon, setting)) close() }
            else -> ui.nap.prefs.modeViews().firstOrNull { it.key == key }?.let { ModeForm(ui, c, it, close) }
        }
    }
}

@Composable
private fun ParkFirst() {
    Text(com.cihmine.napalarm.PARK_FIRST, fontSize = 24.sp, color = Accent, fontWeight = FontWeight.Bold)
}

@Composable
private fun PanelHeader(title: String, close: () -> Unit, onDelete: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        // 추가한 모드만: 제목 옆 휴지통(누르면 아래에서 한 번 더 묻는다)
        onDelete?.let {
            Text("🗑", fontSize = 28.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = it).padding(horizontal = 14.dp, vertical = 4.dp))
        }
        Text("✕", fontSize = 30.sp, color = Color.LightGray,
            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = close).padding(horizontal = 14.dp, vertical = 4.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowChips(items: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach {
            Text(it, fontSize = 16.sp, modifier = Modifier.background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp))
        }
    }
}

/** 모드 창: 큰 켜기/끄기 버튼과 이 모드가 바꾸는 것. 바꾸면 바로 저장된다. 추가한 모드는 여기서 지운다 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeForm(ui: NapUi, c: NapController, m: ModeView, close: () -> Unit) {
    // 낮잠은 꺼짐·휴식 중 모두 이 창 하나에서: 큰 버튼과 휴식 시간 칸만 상태에 따라 바뀐다
    val napOn = m.mode == RestMode.NAP && ui.nap.session?.status.let { it == NapStatus.RESTING || it == NapStatus.SNOOZED }
    // 낮잠 뒤 되돌리기도 같은 창: 큰 버튼이 '복구 완료'로, 휴식 시간 칸이 되돌릴 항목으로 바뀐다
    val napEnd = m.mode == RestMode.NAP && ui.nap.session?.status == NapStatus.RESTORE_PENDING
    val on = ui.nap.prefs.activeMode?.key == m.key || napOn || napEnd
    // 낮잠이 끝나 되돌리기 전까지는 다른 모드를 켜지 않는다(되돌릴 원래 값이 섞이지 않게)
    val napBusy = ui.nap.session?.status.let { it != null && it != NapStatus.FINISHED }
    val enabled = ui.controlAllowed && !ui.modeBusy && !napBusy
    val s = m.setting
    val save: (ModeSetting) -> Unit = { next -> m.mode?.let { c.setModeSetting(it, next) } ?: m.custom?.let { c.setCustomSetting(it.id, next) } }
    var askDelete by remember(m.key) { mutableStateOf(false) }
    PanelHeader("${m.icon}  ${m.name} 모드", close, m.custom?.takeIf { ui.controlAllowed }?.let { { askDelete = true } })
    m.custom?.takeIf { askDelete }?.let { cm ->
        Column(Modifier.fillMaxWidth().background(LowPower.copy(alpha = 0.18f), RoundedCornerShape(14.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${m.name} 모드를 지울까요?" + if (on) " 켜져 있어서 먼저 끄고 원래대로 되돌린 뒤 지워요." else "", fontSize = 19.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { askDelete = false; c.deleteCustomMode(cm.id) }, enabled = !ui.modeBusy, modifier = Modifier.height(60.dp)) { Text("지우기", fontSize = 20.sp) }
                OutlinedButton(onClick = { askDelete = false }, modifier = Modifier.height(60.dp)) { Text("취소", fontSize = 20.sp) }
            }
        }
    }
    Text(when {
        napOn -> "켜져 있어요 · " + if (ui.nap.session?.status == NapStatus.SNOOZED) "다시 알림 대기 중" else "휴식 중"
        napEnd -> "낮잠 끝 · 원래대로 되돌리는 중"
        on -> "켜져 있어요"
        else -> "꺼져 있어요"
    }, fontSize = 20.sp, color = if (on) Accent else Color.Gray)
    // 보호 범위를 모드마다 분명히: 배터리 보호 감시는 낮잠 세션에서만 돈다
    Text(
        if (m.mode == RestMode.NAP) "🛡 타이머 + 배터리 보호 감시(휴식 중)" else "환경 설정만 적용 · 배터리 보호 감시는 낮잠에서만",
        fontSize = 17.sp, color = if (m.mode == RestMode.NAP) GoodPower else Color.LightGray,
    )
    if (napEnd) RestoreFinishButton(ui, c)
    else Button(onClick = { if (napOn) c.stopEarly() else c.pressModeKey(m.key) }, enabled = napOn || enabled, modifier = Modifier.fillMaxWidth().height(76.dp)) {
        Text(
            when {
                napOn -> "끄고 원래대로"
                on -> "끄고 원래대로"
                m.mode == RestMode.NAP -> "😴 낮잠 시작(타이머)"
                else -> "${m.icon} 켜기"
            },
            fontSize = 26.sp,
        )
    }
    if (!ui.controlAllowed) Text(if (napOn) "주차 상태가 아니에요 — 공조·시트 요청을 막았어요" else "주차 상태가 아니라 켤 수 없어요", fontSize = 17.sp, color = Accent)
    if (napBusy && !napOn && !napEnd) Text("낮잠을 끝내고 되돌리기를 마친 뒤에 켤 수 있어요", fontSize = 17.sp, color = Accent)
    if (on && !napOn && ui.modeResult.isNotEmpty()) FlowRowChips(ui.modeResult)
    HorizontalDivider()
    if (m.mode == RestMode.NAP) {
        if (napEnd) Restore(ui, c, top = false) else NapTime(ui, c, napOn)
        HorizontalDivider()
    }
    // 주행·상태 불명: 설정 편집을 닫는다(켜진 모드 끄기는 위 버튼이 주차 여부로 따로 막음)
    if (!ui.controlAllowed) return ParkFirst()
    Text("이 모드에서 바꾸는 것", fontSize = 20.sp, color = Color.LightGray)
    BigToggle("💡 실내등 끄기", s.lightsOff) { save(s.copy(lightsOff = it)) }
    BigToggle("📻 라디오 켜기(모의)", s.radioOn) { save(s.copy(radioOn = it)) }
    Text("💺 시트 자세(모의)", fontSize = 20.sp, color = Color.LightGray)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SeatPreset.entries.forEach { p ->
            FilterChip(s.seat == p, { save(s.copy(seat = p)) }, { Text(p.label, fontSize = 18.sp) }, modifier = Modifier.height(52.dp))
        }
    }
    if (on) Text("지금 켜진 상태에는 바로 반영되지 않아요 — 설정은 다음에 켤 때 적용돼요.", fontSize = 16.sp, color = Accent)
    Text("바꾸면 바로 저장돼요. 실내등은 실제 기어 P 이고 차량이 지원할 때 실제 명령을 보내요.", fontSize = 15.sp, color = Color.Gray)
    if (napOn) {
        HorizontalDivider()
        EnergyCard(ui, big = false)
    }
}

/**
 * 낮잠 창의 휴식 시간 칸. 꺼져 있으면 다음 낮잠 길이를 고르고, 휴식 중이면 남은 시간·알림 시각을 보여 주고 길이를 고친다.
 * 같은 칸이 상태만 바꿔 보이므로 낮잠 창은 하나다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NapTime(ui: NapUi, c: NapController, napOn: Boolean) {
    val s = ui.nap.session?.takeIf { napOn }
    if (s != null) {
        Text(Texts.remaining(ui.remainingMs ?: 0), fontSize = 88.sp, fontWeight = FontWeight.Light, color = Color(0xFFB0B0C0))
        Text("${c.time(System.currentTimeMillis() + (ui.remainingMs ?: 0))}에 알려 드려요${if (s.demoShortened) " · 시연용 시간 단축" else ""}", fontSize = 18.sp, color = Color.Gray)
        if (s.needsReconfirm) {
            Column(Modifier.background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("기기 시각이 바뀌었거나 재부팅됐어요. 이 시각이 맞나요?", fontSize = 17.sp, color = Accent)
                Button(onClick = c::confirmSchedule) { Text("맞아요, 계속") }
            }
        }
        Actions(ui)
    }
    // 다시 알림 대기·시연 단축 중에는 길이를 고치지 않는다
    val editable = s == null || (s.status == NapStatus.RESTING && !s.demoShortened)
    if (!editable) return
    val total = s?.let { (it.requestedSeconds / 60).toInt() } ?: ui.nap.prefs.lastMinutes
    val set: (Int) -> Unit = { if (s != null) c.adjustRest(it) else c.setMinutes(it) }
    Text("휴식 시간", fontSize = 20.sp, color = Color.LightGray)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(15, 30, 60).forEach { m -> FilterChip(total == m, { set(m) }, { Text("${m}분", fontSize = 18.sp) }, modifier = Modifier.height(52.dp)) }
        OutlinedButton(onClick = { set(total - 5) }, Modifier.height(52.dp)) { Text("−5", fontSize = 18.sp) }
        Text("${total}분", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterVertically))
        OutlinedButton(onClick = { set(total + 5) }, Modifier.height(52.dp)) { Text("+5", fontSize = 18.sp) }
    }
}


/** ＋ 창: 이름·아이콘·실내등·라디오·시트 자세 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddModeForm(close: () -> Unit, onAdd: (String, String, ModeSetting) -> Unit) {
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf(CustomModes.ICONS.first()) }
    var setting by remember { mutableStateOf(ModeSetting(lightsOff = false, radioOn = false, seat = SeatPreset.UPRIGHT)) }
    PanelHeader("＋  모드 추가", close)
    OutlinedTextField(name, { name = it.take(CustomModes.NAME_MAX) }, singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 24.sp),
        label = { Text("모드 이름(${CustomModes.NAME_MAX}글자까지)", fontSize = 18.sp) }, modifier = Modifier.fillMaxWidth())
    Text("아이콘", fontSize = 20.sp, color = Color.LightGray)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CustomModes.ICONS.forEach { i ->
            FilterChip(icon == i, { icon = i }, { Text(i, fontSize = 32.sp, modifier = Modifier.padding(4.dp)) }, modifier = Modifier.height(62.dp))
        }
    }
    BigToggle("💡 실내등 끄기", setting.lightsOff) { setting = setting.copy(lightsOff = it) }
    BigToggle("📻 라디오 켜기(모의)", setting.radioOn) { setting = setting.copy(radioOn = it) }
    Text("💺 시트 자세(모의)", fontSize = 20.sp, color = Color.LightGray)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SeatPreset.entries.forEach { p ->
            FilterChip(setting.seat == p, { setting = setting.copy(seat = p) }, { Text(p.label, fontSize = 18.sp) }, modifier = Modifier.height(52.dp))
        }
    }
    Button(onClick = { onAdd(name, icon, setting) }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth().height(72.dp)) {
        Text("추가", fontSize = 24.sp)
    }
}

/** 시연 패널 안의 차량·전원 설정: 차량 종류, 남길 배터리, 전력 계산식, 모의 공조 온도, 기록(심사·시연용이라 메인 화면에 두지 않는다) */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DemoSettings(ui: NapUi, c: NapController) {
    val prefs = ui.nap.prefs
    Text("차량·전원 설정", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    Text(
        "차종 자동 인식: ${ui.vehicleKind.label}" + when (ui.vehicleKind) {
            VehicleKind.EV -> " (구동 배터리 읽힘 · 연료량 없음)"
            VehicleKind.ICE -> " (연료량 읽힘 · 구동 배터리 없음)"
            VehicleKind.HYBRID -> " (둘 다 읽힘 — 자동으로 바꾸지 않아요)"
            VehicleKind.UNKNOWN -> " (차량 정보가 모자라 아래 선택값을 써요)"
        } + (ui.fuelPercent?.let { " · 연료 $it%" } ?: ""),
        fontSize = 13.sp, color = if (ui.vehicleKind == VehicleKind.UNKNOWN) Color.Gray else GoodPower,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        VehicleProfile.entries.forEach { p -> FilterChip(prefs.profile == p, { c.setProfile(p) }, { Text(p.label, fontSize = 14.sp) }) }
    }
    when (prefs.profile.managed) {
        PowerSource.EV_TRACTION -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("남길 배터리", fontSize = 14.sp)
            OutlinedButton(onClick = { c.setReserve(stepReserve(prefs.reserve, -1)) }) { Text("−") }
            Text(prefs.reserve.evLabel, fontSize = 16.sp)
            OutlinedButton(onClick = { c.setReserve(stepReserve(prefs.reserve, +1)) }) { Text("+") }
        }
        PowerSource.CAMPING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("남길 캠핑 배터리", fontSize = 14.sp)
            OutlinedButton(onClick = { c.setReserve(prefs.reserve.copy(campingReserveWh = prefs.reserve.campingReserveWh - 50)) }) { Text("−") }
            Text("${prefs.reserve.campingReserveWh.toInt()}Wh", fontSize = 16.sp)
            OutlinedButton(onClick = { c.setReserve(prefs.reserve.copy(campingReserveWh = prefs.reserve.campingReserveWh + 50)) }) { Text("+") }
        }
        else -> Unit
    }
    EnergyCard(ui, big = false)
    HorizontalDivider()
    Text("휴식 설정(모의 차량)", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("공조 온도", fontSize = 14.sp)
        OutlinedButton(onClick = { c.setTemperature(prefs.temperatureC - 0.5) }) { Text("−") }
        Text("%.1f℃".format(prefs.temperatureC), fontSize = 16.sp)
        OutlinedButton(onClick = { c.setTemperature(prefs.temperatureC + 0.5) }) { Text("+") }
    }
    History(ui, c)
    Text(Texts.NOT_GUARANTEE, fontSize = 14.sp, color = Color.Gray)
}

/**
 * 휴대폰 아래 탭 같은 바. 누르면 그 모드 창이 오른쪽에서 열리고, 다시 누르면 닫힌다(켜기·끄기는 창 안 버튼).
 * 모드 다음에 ＋(모드 추가). 차량·전원 설정은 시연용이라 시연 패널에 있다. 칸은 모두 같은 비율이라 모드가 늘면 한 칸이 그만큼 좁아진다.
 */
@Composable
private fun ModeBar(ui: NapUi, open: String?, onTab: (String) -> Unit) {
    val activeKey = ui.nap.prefs.activeMode?.key
    val views = ui.nap.prefs.modeViews()
    val canAdd = ui.nap.prefs.customModes.size < CustomModes.MAX
    val cells = views.size + (if (canAdd) 1 else 0)
    // 칸이 많아지면 글자도 조금 줄인다
    val labelSize = if (cells <= 5) 22.sp else 18.sp
    val iconSize = if (cells <= 5) 36.sp else 30.sp
    Row(Modifier.fillMaxWidth().background(Color.Black).height(112.dp)) {
        views.forEach { m ->
            val on = m.key == activeKey
            // 시작 전 낮잠 탭에는 저장된 휴식 시간을 보여 준다(누르면 바로 그 시간으로 시작하므로)
            val napBusy = ui.nap.active
            val label = when {
                on -> "${m.name} 켜짐"
                m.mode == RestMode.NAP && napBusy -> "${m.name} 중"
                m.mode == RestMode.NAP -> "${m.name} · ${ui.nap.prefs.lastMinutes}분"
                else -> m.name
            }
            Tab(on, open == m.key, m.icon, label, iconSize, labelSize) { onTab(m.key) }
        }
        if (canAdd) Tab(false, open == PANEL_ADD, "＋", "모드 추가", iconSize, labelSize) { onTab(PANEL_ADD) }
    }
}

@Composable
private fun RowScope.Tab(on: Boolean, selected: Boolean, icon: String, label: String, iconSize: TextUnit, labelSize: TextUnit, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).fillMaxHeight().background(if (selected) Panel else Color.Transparent).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 켜진 모드 탭 위의 강조 선
        Box(Modifier.fillMaxWidth(0.6f).height(4.dp).background(if (on) Accent else Color.Transparent, RoundedCornerShape(2.dp)))
        Spacer(Modifier.weight(1f))
        Text(icon, fontSize = iconSize, color = if (on) Accent else Color.LightGray)
        Text(label, fontSize = labelSize, color = if (on) Accent else if (selected) Color.White else Color.LightGray,
            fontWeight = if (on || selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
        Spacer(Modifier.weight(1f))
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
        if (s.status == NapStatus.RESTING && !s.demoShortened) {
            val total = (s.requestedSeconds / 60).toInt()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("휴식 시간", fontSize = 18.sp, color = Color.LightGray)
                listOf(15, 30, 60).forEach { m -> FilterChip(total == m, { c.adjustRest(m) }, { Text("${m}분", fontSize = 20.sp) }) }
                OutlinedButton(onClick = { c.adjustRest(total - 5) }) { Text("−5") }
                Text("${total}분", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { c.adjustRest(total + 5) }) { Text("+5") }
            }
        }
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
        EnergyCard(ui, big = true)
        if (!ui.controlAllowed) Text("주차 상태가 아니에요 — 공조·시트 요청을 막았어요", color = Accent, fontSize = 18.sp)
        Actions(ui)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = c::stopEarly, Modifier.height(56.dp)) { Text("휴식 끝내기", fontSize = 18.sp) }
        }
        Message(ui)
    }
}

@Composable
private fun Ringing(ui: NapUi, c: NapController) {
    val s = ui.nap.session ?: return
    val overdue = s.status == NapStatus.OVERDUE
    // 깬 사람이 읽을 순서: 알람 이유 → 지금 할 일 → 큰 버튼 두 개 → (그 아래) 자세한 전력 정보
    val protection = !overdue && s.alarmReason != AlarmReason.WAKE_TIME
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(if (overdue) "예정된 알람 시각이 지났어요" else s.alarmReason.title, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Accent)
        Text(when {
            overdue -> Texts.OVERDUE
            protection -> "배터리 보호 때문에 기상 시각보다 먼저 깨웠어요. 확인하고 휴식을 끝내 주세요."
            else -> "'일어났어' 또는 '${ui.nap.prefs.snoozeMinutes}분 더'를 눌러 주세요."
        }, fontSize = 22.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = c::wake, Modifier.height(88.dp)) { Text(if (protection) "확인하고 휴식 끝내기" else "일어났어", fontSize = 26.sp) }
            if (!overdue) OutlinedButton(onClick = c::snooze, Modifier.height(88.dp)) {
                Text(if (protection) "소리만 ${ui.nap.prefs.snoozeMinutes}분 미루기" else "${ui.nap.prefs.snoozeMinutes}분 더", fontSize = 26.sp)
            }
        }
        if (s.snoozeCount > 0) Text("다시 알림 ${s.snoozeCount}번", fontSize = 18.sp, color = Color.Gray)
        Message(ui)
        if (protection) {
            Text("미뤄도 소리만 늦춰져요 — 전력 보호 한도는 늘어나지 않아요.", fontSize = 18.sp, color = Color.LightGray)
            EnergyCard(ui, big = false)
        }
    }
}

@Composable
private fun pendingRestore(ui: NapUi) =
    ui.restorePlan.any { it.decision == RestoreDecision.RESTORE || it.decision == RestoreDecision.CONFIRM_MANUAL_CHANGE }

/** '종료'와 '차량 설정 복구 완료'를 섞지 않는다: 되돌릴 항목이 남아 있으면 지금 설정을 유지하고 끝내는 버튼으로 보인다 */
@Composable
private fun RestoreFinishButton(ui: NapUi, c: NapController) {
    val m = Modifier.fillMaxWidth().height(76.dp)
    if (pendingRestore(ui)) OutlinedButton(onClick = c::finishRestore, m) { Text("지금 설정 그대로 끝내기", fontSize = 26.sp) }
    else Button(onClick = c::finishRestore, m) { Text("복구 완료", fontSize = 26.sp) }
}

/** [top] 이 false 면 제목·끝내기 버튼은 부르는 쪽(옆 창)이 위에 그린다 */
@Composable
private fun Restore(ui: NapUi, c: NapController, top: Boolean = true) {
    val s = ui.nap.session ?: return
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (top) {
            Text("원래 설정으로 되돌릴까요?", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("종료: ${s.endReason?.label ?: "-"}", fontSize = 18.sp, color = Color.Gray)
        }
        ui.nap.guard?.takeIf { it.status.shed }?.let { g ->
            Text("전력 보호: ${g.status.simpleLabel} — 보호로 끈 장치는 자동으로 다시 켜지 않아요. 필요하면 전원을 확인하고 새로 시작해 주세요.", fontSize = 18.sp, color = Accent)
        }
        if (!ui.controlAllowed) Text("주차 상태가 아니에요 — 시트·공조를 움직이지 않아요", color = Accent, fontSize = 18.sp)
        ui.restorePlan.forEach { item ->
            Row(
                Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(item.target.label, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Text("원래 ${item.original ?: "읽지 못함"} · 앱이 바꾼 값 ${item.appSet ?: "없음"} · 지금 ${item.current ?: "-"}", fontSize = 15.sp, color = Color.LightGray)
                    Text(item.decision.label, fontSize = 15.sp, color = if (item.decision == RestoreDecision.CONFIRM_MANUAL_CHANGE || item.decision == RestoreDecision.BLOCKED_BY_PROTECTION) Accent else Color.Gray)
                }
                when (item.decision) {
                    RestoreDecision.RESTORE -> Button(onClick = { c.restore(item) }, enabled = ui.controlAllowed) { Text("되돌리기") }
                    RestoreDecision.CONFIRM_MANUAL_CHANGE -> OutlinedButton(onClick = { c.restore(item) }, enabled = ui.controlAllowed) { Text("그래도 원래 값으로") }
                    RestoreDecision.NOTHING_TO_DO, RestoreDecision.ALREADY_RESTORED, RestoreDecision.BLOCKED_BY_PROTECTION -> Unit
                }
            }
        }
        Actions(ui)
        if (top) RestoreFinishButton(ui, c)
        if (pendingRestore(ui)) Text("되돌리지 않은 항목은 지금 값 그대로 남아요.", fontSize = 15.sp, color = Color.Gray)
        Text("시트는 실제로 움직이지 않아요(모의 차량). 실제 차량에서는 정차·사용자 확인·지원 여부를 확인한 뒤에만 움직여요.", fontSize = 14.sp, color = Color.Gray)
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
                (if (r.snoozeCount > 0) " · 다시 알림 ${r.snoozeCount}번" else "") + (if (r.demoShortened) " · 시연 단축" else "") +
                (r.profile?.let { " · ${it.label}" } ?: "") + (r.guardOutcome?.takeIf { it.shed }?.let { " · 전력 보호: ${it.simpleLabel}" } ?: ""), fontSize = 15.sp, color = Color.LightGray)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DemoPanel(ui: NapUi, c: NapController) {
    Column(
        Modifier.width(360.dp).fillMaxHeight().background(Panel).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("시연 패널", color = BannerText, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text("실제 사용자 화면이 아닌 시연·검증용 입력", fontSize = 12.sp, color = Color.Gray)
        HorizontalDivider()
        Text("버튼 하나로 장면 재현", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        val idle = ui.nap.session == null && ui.controlAllowed
        Button(onClick = c::sceneEv, enabled = idle, modifier = Modifier.fillMaxWidth()) { Text("장면 1 · 전기차 전력 보호") }
        Button(onClick = c::sceneCamping, enabled = idle, modifier = Modifier.fillMaxWidth()) { Text("장면 2 · 내연기관차 + 캠핑 배터리") }
        Button(onClick = c::sceneStarter, enabled = idle, modifier = Modifier.fillMaxWidth()) { Text("장면 3 · 12V 만 있는 차(계산 불가)") }
        HorizontalDivider()
        DemoSettings(ui, c)
        HorizontalDivider()
        Text("모의 전원", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        val e = ui.energy
        val evLine = ui.realEv.takeIf { ui.realEvInUse }?.let { "전기차 SOC ${it.socPercent.toInt()}%(실제)" } ?: "전기차 SOC ${"%.2f".format(e.evSoc)}%"
        val loadLine = ui.realLoad.takeIf { ui.realLoadInUse }?.let { "추정 ${RuntimeEstimate.w(it.watts)}(${it.parts.joinToString(" + ")})" }
            ?: "${RuntimeEstimate.w(e.evLoadW)}${if (e.evClimateOn) "" else " (공조 꺼짐)"}"
        Text("$evLine · $loadLine", fontSize = 14.sp)
        // 에뮬에서는 SDK 공조 값을 바꿀 방법이 없어 시연 입력으로 대신한다(실제 값 쓰는 중일 때만 의미 있음)
        Toggle("공조 켬(시연 입력 · 에어컨 포함)", ui.demoClimateOn, c::setDemoClimate)
        Text("캠핑 배터리 ${"%.0f".format(e.campingWh)}Wh · ${RuntimeEstimate.w(e.campingLoadW)}${if (e.campingLightOn) "" else " (조명 꺼짐)"}", fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { c.changeLoad(-500.0) }, Modifier.weight(1f)) { Text("소비 −") }
            OutlinedButton(onClick = { c.changeLoad(500.0) }, Modifier.weight(1f)) { Text("소비 +") }
        }
        Toggle("배터리 소모 30배속(시간 단축)", e.drainSpeed > 1) { on -> c.energy { copy(drainSpeed = if (on) 30 else 1) } }
        Toggle("측정값 끊김", e.dataCut) { on -> c.energy { copy(dataCut = on) } }
        Toggle("충전/전원 공급 중", e.charging) { on -> c.energy { copy(charging = on) } }
        Toggle("12V 경고 신호(차량)", e.starterWarning) { on -> c.energy { copy(starterWarning = on) } }
        Toggle("장치 종료 실패", e.failShed) { on -> c.energy { copy(failShed = on) } }
        Toggle("장치 종료 응답 없음", e.noResponseShed) { on -> c.energy { copy(noResponseShed = on) } }
        HorizontalDivider()
        Text("타이머·공조·시트", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Button(onClick = { c.startRest(demo20s = true) }, enabled = ui.nap.session == null && ui.controlAllowed, modifier = Modifier.fillMaxWidth()) {
            Text("20초 타이머로 시작(시간 단축)")
        }
        Text("모의 차량 상태(실제 기어 값이 바뀌면 그 값이 우선해요)", fontSize = 15.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VehicleState.entries.forEach { v -> FilterChip(ui.vehicle == v, { c.setVehicle(v) }, { Text(v.label) }) }
        }
        Text("모의 차량: ${ui.car.settings.valueOf(com.cihmine.napalarm.core.ControlTarget.CLIMATE)} · ${ui.car.settings.seat?.label}", fontSize = 14.sp)
        Toggle("공조 요청 실패", ui.car.failClimate, c::setFailClimate)
        Toggle("시트 요청 실패", ui.car.failSeat, c::setFailSeat)
        Toggle("차량 응답 없음", ui.car.noResponse, c::setNoResponse)
        OutlinedButton(onClick = { c.manualTemperature(23.0) }, Modifier.fillMaxWidth()) { Text("사용자가 직접 온도 23℃로 변경") }
        OutlinedButton(onClick = c::phoneReservation, Modifier.fillMaxWidth()) { Text("휴대폰 '10분 전 준비' 예약(모의)") }
    }
}

@Composable
private fun BigToggle(label: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { set(!on) }.padding(vertical = 4.dp)) {
        Text(label, fontSize = 22.sp, modifier = Modifier.weight(1f))
        Switch(on, set)
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(on, set)
    }
}
