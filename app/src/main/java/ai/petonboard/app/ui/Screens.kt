package ai.petonboard.app.ui

import ai.petonboard.app.R
import ai.petonboard.app.logic.ExitStep
import ai.petonboard.app.logic.PetModeViewModel
import ai.petonboard.app.logic.Screen
import ai.petonboard.app.logic.TripEventKind
import ai.petonboard.app.logic.TripInsights
import ai.petonboard.app.logic.TripReport
import ai.petonboard.app.logic.TripSummary
import ai.petonboard.app.logic.Tuning
import ai.petonboard.app.logic.UiState
import ai.petonboard.app.vehicle.Gear
import ai.petonboard.app.vehicle.SeatSide
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ======================================================================
// 루트
// ======================================================================

@Composable
fun PetOnboardApp(vm: PetModeViewModel) {
    // 화면 크기에 맞춰 전체 UI를 키움 (기준 1000 x 720 dp)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val scale = minOf(maxWidth.value / 1000f, maxHeight.value / 720f).coerceIn(0.8f, 2f)
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale)) {
            AppContent(vm)
        }
    }
}

@Composable
private fun AppContent(vm: PetModeViewModel) {
    val s by vm.ui.collectAsState()

    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(C.Bg)
                drawCircle(
                    Brush.radialGradient(listOf(C.BgGlow, Color.Transparent), center = Offset(size.width * 0.8f, -size.height * 0.15f), radius = size.width * 0.55f),
                    radius = size.width * 0.55f, center = Offset(size.width * 0.8f, -size.height * 0.15f),
                )
            }
    ) {
        Column(Modifier.fillMaxSize()) {
            // 테스트 줄(실시간 차량 값·테스트 버튼)은 개발 빌드에서만. 제출용 release APK에서는 눌러도 안 나옴
            // 심사·시연용: release에서도 평소엔 숨기고, 왼쪽 위 '펫온보드'를 누르면 테스트 줄이 열림
            TopBar(s, onBrand = { vm.toggleDebug() })
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (s.screen) {
                    Screen.PROFILE -> ProfileScreen(s, vm, firstRun = s.firstRun)
                    Screen.HOME -> HomeScreen(s, vm)
                    Screen.BOARDING -> BoardingScreen(s, vm)
                    Screen.DRIVING -> DrivingScreen(s, vm)
                    Screen.EXIT -> ExitScreen(s, vm)
                    Screen.REPORT -> ReportScreen(s, vm)
                    Screen.HISTORY -> HistoryScreen(s, vm)
                }
            }
            if (s.showDebug) DebugStrip(s, vm)
        }

        // 겹쳐 뜨는 카드
        if (s.screen == Screen.DRIVING && s.windSuggestLeft > 0) WindDialog(s, vm)
        else if (s.screen == Screen.DRIVING && s.showRestCard) RestDialog(s, vm)

        Toast(s.toast, vm::clearToast)
    }
}

@Composable
private fun TopBar(s: UiState, onBrand: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.clickable(onClick = onBrand), verticalAlignment = Alignment.CenterVertically) {
            Ico(R.drawable.ic_paw, C.BlueLight, 24.dp)
            Gap(10.dp)
            T("펫온보드", 20.sp, C.Text, FontWeight.Bold)
        }
        Gap(18.dp)
        if (s.petModeOn) Pill("펫 모드 켜짐")
        Gap(18.dp)
        if (s.screen != Screen.HOME && s.screen != Screen.REPORT && s.screen != Screen.PROFILE && s.screen != Screen.HISTORY) {
            T("${s.car.speedKmh.toInt()}", 19.sp, C.Text, FontWeight.Medium)
            T(" km/h", 15.sp, C.Muted)
            Gap(18.dp)
            T("기어 ", 15.sp, C.Muted)
            T(s.car.gear.label(), 19.sp, C.Text, FontWeight.Medium)
        }
        Spacer(Modifier.weight(1f))
        if (!s.car.connected) T("차량 연결 안 됨", 15.sp, C.Orange)
    }
}

// ======================================================================
// F0 홈
// ======================================================================

@Composable
private fun HomeScreen(s: UiState, vm: PetModeViewModel) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 세로 공간이 모자라면(테스트 줄 등) 글자·간격을 줄여 아래 카드가 잘리지 않게 함
        val compact = maxHeight < 560.dp
        // 오른쪽 아래 큰 사진 + 왼쪽으로 어둡게
        Image(
            painterResource(R.drawable.kongi_hero), null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxHeight().fillMaxWidth(0.62f).align(Alignment.CenterEnd),
        )
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0.30f to C.Bg, 0.58f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.65f to Color.Transparent, 1f to C.Bg)))

        Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = if (compact) 14.dp else 28.dp)) {
            Spacer(Modifier.weight(1f))
            T("${s.profile.name}와 함께하는\n안전하고 편안한 주행", if (compact) 34.sp else 44.sp, C.Text, FontWeight.Bold, lineHeight = if (compact) 44.sp else 56.sp)
            VGap(if (compact) 8.dp else 14.dp)
            T(
                if (s.car.occupancySupported) "${s.profile.name}가 타면 자동으로 알아채요.\n바로 시작하려면 버튼을 눌러 주세요"
                else "${s.profile.name}가 타면 펫 모드를 시작해 주세요",
                if (compact) 17.sp else 20.sp, C.Muted, lineHeight = if (compact) 25.sp else 30.sp,
            )
            VGap(if (compact) 14.dp else 30.dp)
            PrimaryButton("펫 모드 시작", Modifier.width(400.dp), R.drawable.ic_play, if (compact) 62.dp else 76.dp, onClick = vm::startFromHome)
            Spacer(Modifier.weight(1f))
            VGap(if (compact) 12.dp else 0.dp)
            // 두 카드를 같은 높이로 맞추고 내용은 세로 가운데 (높이가 달라 보이지 않게)
            val cardH = if (compact) 112.dp else 132.dp
            val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 22.dp, vertical = if (compact) 12.dp else 18.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Glass(Modifier.width(400.dp).height(cardH), padding = pad, onClick = vm::openHistory) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Ico(R.drawable.ic_flag, C.BlueLight, 22.dp); Gap(10.dp)
                            T("${s.profile.name}의 주행 기록", 17.sp, C.Muted, FontWeight.Medium)
                        }
                        VGap(8.dp)
                        val last = s.trips.firstOrNull()
                        if (last == null) {
                            T("아직 주행 기록이 없어요", 22.sp, C.Text, FontWeight.Bold)
                        } else {
                            T("${s.trips.size}번 함께 탔어요", 24.sp, C.Text, FontWeight.Bold)
                            VGap(2.dp)
                            T("최근 ${fmtDuration(last.durationMs)} · 흔들림 ${last.shakes}회 · 휴식 ${last.restCount}회", 16.sp, C.Muted, maxLines = 1)
                        }
                    }
                }
                Glass(Modifier.width(400.dp).height(cardH), padding = pad, onClick = vm::openProfile) {
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(R.drawable.kongi, 64.dp, 3.dp)
                        Gap(16.dp)
                        Column {
                            T("${s.profile.name} 프로필", 22.sp, C.Text, FontWeight.Bold)
                            T(listOf(s.profile.breed, s.profile.size.label, s.profile.seat.label()).filter { it.isNotBlank() }.joinToString(" · "), 16.sp, C.Muted, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

// ======================================================================
// F1 탑승 확인
// ======================================================================

@Composable
private fun BoardingScreen(s: UiState, vm: PetModeViewModel) {
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 28.dp), verticalArrangement = Arrangement.Center) {
        InfoTag(R.drawable.ic_seat, "${s.petSide.label()}에 탑승이 감지됐어요")
        VGap(18.dp)
        T("누가 탔나요?", 48.sp, C.Text, FontWeight.Bold)
        VGap(10.dp)
        T("반려견을 고르면 펫 모드가 켜지고 창문 잠금, 온도, 조명을 한 번에 맞춰요", 20.sp, C.Muted)
        if (s.listening) { VGap(10.dp); MicHint(Modifier, "${s.profile.name}면 '맞아'라고 말해도 돼요") }
        VGap(36.dp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            ChoiceCard(Modifier.weight(1f), s.profile.name, "반려견", selected = true, onClick = vm::choosePet) {
                Avatar(R.drawable.kongi, 140.dp)
            }
            ChoiceCard(Modifier.weight(1f), "아이", "어린이 탑승", onClick = vm::chooseNotPet) {
                IconCircle(R.drawable.ic_child)
            }
            ChoiceCard(Modifier.weight(1f), "짐 또는 없음", "펫 모드 안 켬", onClick = vm::chooseNotPet) {
                IconCircle(R.drawable.ic_box)
            }
        }
    }
}

@Composable
private fun ChoiceCard(
    modifier: Modifier, title: String, sub: String, selected: Boolean = false,
    onClick: () -> Unit, top: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(26.dp)
    Column(
        modifier
            .height(320.dp)
            .clip(shape)
            .background(C.glass)
            .border(if (selected) 3.dp else 1.dp, if (selected) C.Blue else C.GlassLine, shape)
            .clickable(onClick = onClick)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        top()
        VGap(24.dp)
        T(title, 26.sp, C.Text, FontWeight.Bold)
        VGap(6.dp)
        T(sub, 17.sp, C.Muted)
    }
}

@Composable
private fun IconCircle(icon: Int) {
    Box(Modifier.size(140.dp).clip(CircleShape).background(C.GhostFill), contentAlignment = Alignment.Center) {
        Ico(icon, C.Soft, 64.dp)
    }
}

// ======================================================================
// F2·F3·F5 주행 중
// ======================================================================

@Composable
private fun DrivingScreen(s: UiState, vm: PetModeViewModel) = BoxWithConstraints(Modifier.fillMaxSize()) {
    // 세로 공간이 모자라면(경고 띠, 테스트 줄 등) 위쪽 사진과 간격을 줄여서 아래 카드가 잘리지 않게 함
    val compact = maxHeight < 600.dp
    val avatar = if (compact) 140.dp else 200.dp
    val gap = if (compact) 14.dp else 24.dp
    val cardH = 150.dp  // 아이콘 52 + 버튼 48 + 안쪽 여백 44가 들어가야 함
    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = if (compact) 12.dp else 24.dp), verticalArrangement = Arrangement.Center) {
        // 안전벨트 경고 (달리는데 반려견 자리 버클이 비어 있음)
        if (s.beltWarning) {
            val shape = RoundedCornerShape(20.dp)
            Row(
                Modifier.fillMaxWidth().clip(shape).background(C.Orange.copy(alpha = 0.14f)).border(1.dp, C.Orange.copy(alpha = 0.5f), shape).padding(horizontal = 22.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconDot(R.drawable.ic_seat, C.Orange, 48.dp)
                Gap(16.dp)
                Column {
                    T("${s.profile.name} 안전벨트가 풀려 있어요", 24.sp, C.Orange, FontWeight.Bold)
                    T("${s.petSide.label()} 버클이 비어 있어요. 안전한 곳에 세운 뒤 반려견 안전벨트를 채워 주세요", 16.sp, C.Soft)
                }
            }
            VGap(18.dp)
        }
        // 위: 반려견 상태
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(R.drawable.kongi, avatar, 5.dp)
            Gap(36.dp)
            Column(Modifier.weight(1f)) {
                T("${s.profile.name}와 주행 중", 22.sp, C.Muted)
                VGap(2.dp)
                val (mood, color) = when {
                    s.soundRecent >= 2 -> "${s.profile.name}가 불안해해요" to C.Red
                    s.soundRecent == 1 -> (if (s.soundLabel == "짖음") "${s.profile.name}가 짖었어요" else "${s.profile.name}가 낑낑거렸어요") to C.Orange
                    s.shakeLevel == 0 && s.car.speedKmh < 1f -> "정차 중이에요" to C.Green
                    s.shakeLevel == 0 -> "부드럽게 달리고 있어요" to C.Green
                    s.shakeLevel == 1 -> "조금 흔들렸어요" to C.Orange
                    else -> "많이 흔들렸어요" to C.Red
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(mood, if (compact) 38.sp else 46.sp, C.Text, FontWeight.Bold)
                    Gap(14.dp)
                    Box(Modifier.size(16.dp).clip(CircleShape).background(color))
                }
                if (s.soundRecent > 0) {
                    VGap(4.dp)
                    T("최근 3분 동안 ${s.soundRecent}번 들렸어요. 안전한 곳에 세울 때 살펴봐 주세요", 17.sp, C.Muted)
                } else if (s.shakeLevel > 0) {
                    VGap(4.dp)
                    T("최근 1분 동안 급커브·급제동이 있었어요. 부드럽게 운전해 주세요", 17.sp, C.Muted)
                }
                VGap(if (compact) 8.dp else 16.dp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Chip(R.drawable.ic_lock, "창문 잠금")
                    Chip(R.drawable.ic_thermo, "${s.profile.tempC.toInt()}°C")
                    Chip(R.drawable.ic_sun, "은은한 조명")
                    if (s.soundListening) Chip(R.drawable.ic_paw, "소리 듣는 중")
                }
            }
        }
        VGap(gap)
        // 가운데: 바람 쐬기
        if (s.windOpen) {
            // 안내 문구를 버튼 옆에 두어 세로 공간을 더 쓰지 않게 함 (아래 카드가 잘리지 않도록)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton("바람 쐬기 창문 닫기", Modifier.weight(1f), R.drawable.ic_window, 72.dp, onClick = vm::closeWindManually)
                Gap(20.dp)
                T("${Tuning.WIND_CLOSE_KMH.toInt()}km/h를 넘거나\n와이퍼가 켜지면 자동으로 닫혀요", 16.sp, C.Muted)
            }
        } else {
            GhostButton("바람 쐬기 창문 열기", Modifier.fillMaxWidth(), R.drawable.ic_wind, height = 72.dp, onClick = vm::acceptWind)
        }
        VGap(gap)
        // 아래: 휴식, 하차 안전
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Glass(Modifier.weight(1f).height(cardH)) {
                Row(verticalAlignment = Alignment.Top) {
                    Ico(R.drawable.ic_clock, C.Soft, 30.dp)
                    Gap(14.dp)
                    Column {
                        if (s.restRemainingMs > 0L) {
                            T("다음 휴식까지", 16.sp, C.Muted)
                            T("약 ${fmtDuration(s.restRemainingMs)}", 30.sp, C.Text, FontWeight.Bold)
                        } else {
                            T(if (s.anxietyRest) "불안 소리가 잦아요" else "충분히 달렸어요", 16.sp, C.Muted)
                            T("쉬어 갈 시간이에요", 28.sp, C.Text, FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                Progress(1f - s.restRemainingMs.toFloat() / s.profile.restIntervalMs)
            }
            Glass(Modifier.weight(1f).height(cardH)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(C.Green.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                        Ico(R.drawable.ic_shield, C.Green, 28.dp)
                    }
                    Gap(16.dp)
                    Column {
                        T("하차 안전 대기 중", 22.sp, C.Text, FontWeight.Bold)
                        T("P단에 두면 문을 잠가 둘게요", 16.sp, C.Muted)
                    }
                }
                Spacer(Modifier.weight(1f))
                GhostButton("펫 모드 끝내기", Modifier.fillMaxWidth(), height = 48.dp, onClick = vm::endPetMode)
            }
        }
    }
}

@Composable
private fun WindDialog(s: UiState, vm: PetModeViewModel) = Overlay {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(80.dp).clip(RoundedCornerShape(24.dp)).background(C.BlueChip), contentAlignment = Alignment.Center) {
            Ico(R.drawable.ic_wind, C.BlueLight, 42.dp)
        }
        Gap(24.dp)
        Column {
            T("${s.profile.name}가 바람 쐬기 좋은 구간이에요", 30.sp, C.Text, FontWeight.Bold)
            VGap(6.dp)
            T("머리가 나가지 않는 높이까지만 창문을 열고, 속도가 오르면 다시 닫아요", 17.sp, C.Muted)
        }
    }
    VGap(28.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        GhostButton("괜찮아요", Modifier.weight(0.8f), sub = "${Tuning.WIND_COOLDOWN_MS / 60000}분 동안 다시 묻지 않아요", height = 76.dp, onClick = vm::dismissWind)
        PrimaryButton("창문 열기", Modifier.weight(1f), R.drawable.ic_window, 76.dp, onClick = vm::acceptWind)
    }
    VGap(14.dp)
    if (s.listening) MicHint(Modifier.align(Alignment.CenterHorizontally), "'좋아' 또는 '괜찮아'라고 말해도 돼요")
    T("${s.windSuggestLeft}초 동안 답이 없으면 창문은 그대로 둘게요", 15.sp, C.Muted, modifier = Modifier.align(Alignment.CenterHorizontally))
}

@Composable
private fun RestDialog(s: UiState, vm: PetModeViewModel) = Overlay {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(80.dp).clip(RoundedCornerShape(24.dp)).background(C.Green.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Ico(R.drawable.ic_parking, C.Green, 42.dp)
        }
        Gap(24.dp)
        Column {
            T(if (s.anxietyRest) "${s.profile.name}가 계속 불안해해요" else "${s.profile.name}가 쉴 시간이에요", 30.sp, C.Text, FontWeight.Bold)
            VGap(6.dp)
            val minText = s.profile.restIntervalMin.let { if (it % 60 == 0) "${it / 60}시간" else "${it}분" }
            T(
                if (s.anxietyRest) "최근 10분 동안 여러 번 낑낑거리거나 짖었어요. 휴식 시간 전이지만 잠깐 쉬어 가요"
                else "${minText} 동안 달렸어요. 반려견 놀이터가 있는 휴게소에서 물 마시고 걷게 해 주세요",
                17.sp, C.Muted,
            )
        }
    }
    s.restPick?.let { p ->
        VGap(20.dp)
        Glass(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconDot(R.drawable.ic_paw, C.Green, 44.dp)
                Gap(14.dp)
                Column(Modifier.weight(1f)) {
                    T("${p.stop.name} · ${p.stop.direction}", 20.sp, C.Text, FontWeight.Bold)
                    T("${p.stop.road} · ${p.stop.facility}", 15.sp, C.Muted)
                }
                T(String.format(Locale.KOREA, "%.0f km", p.distanceKm), 20.sp, C.BlueLight, FontWeight.Bold)
            }
        }
    }
    VGap(24.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        GhostButton("15분 뒤에 다시 알려줘", Modifier.weight(1f), height = 76.dp, onClick = vm::snoozeRest)
        if (s.restPick != null) PrimaryButton("휴게소로 안내해 줘", Modifier.weight(1f), R.drawable.ic_check, 76.dp, onClick = vm::goRestStop)
        else PrimaryButton("알겠어요, 쉬어 갈게요", Modifier.weight(1f), R.drawable.ic_check, 76.dp, onClick = vm::snoozeRest)
    }
    VGap(14.dp)
    if (s.listening) MicHint(Modifier.align(Alignment.CenterHorizontally), "'좋아' 또는 '나중에'라고 말해도 돼요")
    T("정차해서 ${Tuning.REST_MIN_PARK_MS / 60000}분 넘게 쉬면 휴식으로 기록돼요", 15.sp, C.Muted, modifier = Modifier.align(Alignment.CenterHorizontally))
}

/** 마이크가 듣고 있음을 알리는 작은 표시 */
@Composable
private fun MicHint(modifier: Modifier, text: String) {
    Row(
        modifier.padding(bottom = 8.dp).clip(RoundedCornerShape(999.dp)).background(C.BlueChip).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(C.Red))
        Gap(10.dp)
        T("듣고 있어요 · $text", 16.sp, C.BlueLight, FontWeight.Medium)
    }
}

@Composable
private fun Overlay(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(C.Scrim).pointerInput(Unit) { detectTapGestures { } }, contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(28.dp)
        Column(
            Modifier.widthIn(max = 900.dp).clip(shape).background(C.dialog).border(1.dp, C.GlassLine, shape).padding(36.dp),
            content = content,
        )
    }
}

// ======================================================================
// F6 하차 안전
// ======================================================================

@Composable
private fun ExitScreen(s: UiState, vm: PetModeViewModel) {
    val unlocked = s.exitStep == ExitStep.UNLOCKED
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(0.75f).fillMaxHeight()) {
            Image(painterResource(R.drawable.kongi_exit), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0.6f to Color.Transparent, 1f to C.Bg)))
        }
        Column(Modifier.weight(1.25f).fillMaxHeight().padding(start = 28.dp, end = 40.dp), verticalArrangement = Arrangement.Center) {
            InfoTag(R.drawable.ic_parking, "정차했어요")
            VGap(16.dp)
            T(if (unlocked) "문을 열 수 있어요" else "리드줄을 채우셨나요?", 44.sp, C.Text, FontWeight.Bold)
            VGap(8.dp)
            T(
                if (unlocked) "${s.profile.name}가 내리면 펫 모드를 끝내고 리포트를 보여 드려요" else "확인을 누를 때까지 문 잠금을 유지해요",
                19.sp, C.Muted,
            )
            VGap(24.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatusCard(
                    Modifier.weight(1f), if (unlocked) R.drawable.ic_unlock else R.drawable.ic_lock, "문",
                    if (unlocked) "잠금 풀림" else "잠겨 있어요", if (unlocked) C.BlueLight else C.Green, check = !unlocked,
                )
                StatusCard(
                    Modifier.weight(1f), R.drawable.ic_paw, "리드줄",
                    if (unlocked) "확인했어요" else "확인 전이에요", if (unlocked) C.Green else C.Orange, check = unlocked,
                )
            }
            VGap(20.dp)
            if (!unlocked) {
                PrimaryButton("리드줄 채웠어요, 문 열기", Modifier.fillMaxWidth(), R.drawable.ic_check, 76.dp, onClick = vm::confirmLeash)
                VGap(12.dp)
                GhostButton("여기서 펫 모드 끝내기", Modifier.fillMaxWidth(), height = 60.dp, onClick = vm::endPetMode)
            } else {
                PrimaryButton("펫 모드 끝내기", Modifier.fillMaxWidth(), R.drawable.ic_flag, 76.dp, onClick = vm::endPetMode)
                VGap(12.dp)
                T("다시 출발하면 펫 모드가 그대로 이어져요", 17.sp, C.Muted, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            VGap(14.dp)
            T("차량 잠금 버튼으로도 언제든 문을 열 수 있어요", 16.sp, C.Muted)
        }
    }
}

@Composable
private fun StatusCard(modifier: Modifier, icon: Int, label: String, value: String, color: Color, check: Boolean) {
    Glass(modifier, padding = androidx.compose.foundation.layout.PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconDot(icon, color, 50.dp)
            Gap(14.dp)
            Column(Modifier.weight(1f)) {
                T(label, 15.sp, C.Muted)
                T(value, 21.sp, color, FontWeight.Bold)
            }
            if (check) Ico(R.drawable.ic_check, color, 24.dp)
        }
    }
}

// ======================================================================
// F7 주행 리포트
// ======================================================================

@Composable
private fun ReportScreen(s: UiState, vm: PetModeViewModel) {
    val r: TripReport = s.report ?: s.lastReport ?: return
    Column(Modifier.fillMaxSize().padding(horizontal = 44.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("${s.profile.name}와 함께한 주행", 32.sp, C.Text, FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            T("${fmtClock(r.startMs)} – ${fmtClock(r.endMs)}", 17.sp, C.Muted)
        }
        VGap(18.dp)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            // 숫자는 2칸씩 3줄로 (화면이 낮아도 잘리지 않게)
            Column(Modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MiniStat(fmtDuration(r.durationMs), "주행 시간", Modifier.weight(1f))
                    MiniStat(String.format(Locale.KOREA, "%.1f km", r.distanceKm), "이동 거리", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MiniStat("${r.windCount}회", "바람 쐬기", Modifier.weight(1f))
                    MiniStat("${r.restCount}회", "휴식", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MiniStat("${r.sharpTurns + r.hardBrakes}회", "급커브·급제동", Modifier.weight(1f), if (r.sharpTurns + r.hardBrakes > 0) C.Orange else C.Text)
                    MiniStat("${r.whineCount + r.barkCount}회", "불안 소리", Modifier.weight(1f), if (r.whineCount + r.barkCount > 0) C.Orange else C.Text)
                }
            }
            Glass(Modifier.weight(1f).fillMaxHeight()) {
                T("주요 기록", 20.sp, C.Text, FontWeight.Bold)
                VGap(14.dp)
                val items = r.timeline.let { if (it.size <= 6) it else it.take(2) + it.takeLast(4) }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items.forEach { e ->
                        val (icon, color) = when (e.kind) {
                            TripEventKind.START -> R.drawable.ic_play to C.BlueLight
                            TripEventKind.WIND -> R.drawable.ic_wind to C.BlueLight
                            TripEventKind.SHAKE -> R.drawable.ic_shield to C.Orange
                            TripEventKind.REST -> R.drawable.ic_parking to C.BlueLight
                            TripEventKind.BELT -> R.drawable.ic_seat to C.Orange
                            TripEventKind.SOUND -> R.drawable.ic_paw to C.Orange
                            TripEventKind.END -> R.drawable.ic_flag to C.Green
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconDot(icon, color, 42.dp)
                            Gap(14.dp)
                            T(fmtClock(e.atMs), 17.sp, C.Muted, modifier = Modifier.width(62.dp))
                            T(e.title, 18.sp, C.Text, FontWeight.Bold, modifier = Modifier.width(110.dp))
                            T(e.sub, 16.sp, C.Muted, maxLines = 1)
                        }
                    }
                }
            }
        }
        VGap(14.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 지난 기록과 비교 (쌓인 기록이 있을 때)
            val ins = TripInsights.of(s.trips, s.profile.name)
            if (ins != null) {
                Column(Modifier.weight(1f)) {
                    val lines = listOfNotNull(s.adjustNote) + ins.lines
                    lines.take(2).forEachIndexed { i, l -> T("• $l", 16.sp, if (i == 0 && s.adjustNote != null) C.BlueLight else C.Soft, maxLines = if (i == 0 && s.adjustNote != null) 2 else 1) }
                }
                Gap(16.dp)
                GhostButton("기록 모두 보기", Modifier.width(200.dp), height = 56.dp, onClick = vm::openHistory)
                Gap(12.dp)
            } else Spacer(Modifier.weight(1f))
            PrimaryButton("홈으로", Modifier.width(220.dp), height = 56.dp, onClick = vm::goHome)
        }
    }
}

// ======================================================================
// 주행 기록 (쌓인 기록 + 우리 개 패턴)
// ======================================================================

@Composable
private fun HistoryScreen(s: UiState, vm: PetModeViewModel) {
    val ins = TripInsights.of(s.trips, s.profile.name)
    Column(Modifier.fillMaxSize().padding(horizontal = 44.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("${s.profile.name}의 주행 기록", 32.sp, C.Text, FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (s.lastReport != null) { GhostButton("최근 리포트", Modifier.width(180.dp), height = 52.dp, onClick = vm::showLastReport); Gap(12.dp) }
            PrimaryButton("홈으로", Modifier.width(180.dp), height = 52.dp, onClick = vm::goHome)
        }
        VGap(16.dp)
        if (ins == null) {
            T("아직 기록이 없어요. 펫 모드로 한 번 달리면 여기에 쌓여요", 20.sp, C.Muted)
            return@Column
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.width(300.dp)) {
                Stat("${ins.count}번", "함께 탄 횟수")
                Stat(fmtDuration((ins.avgMinutes * 60_000).toLong()), "평균 주행 시간")
                Stat(String.format(Locale.KOREA, "%.1f회", ins.avgShakesPerHour), "시간당 급커브·급제동 (평균)", last = true)
                VGap(10.dp)
                ins.lines.forEach { T("• $it", 15.sp, C.Soft, lineHeight = 22.sp); VGap(6.dp) }
            }
            Glass(Modifier.weight(1f).fillMaxHeight()) {
                T("최근 주행", 20.sp, C.Text, FontWeight.Bold)
                VGap(12.dp)
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    s.trips.take(20).forEach { TripRow(it) }
                }
            }
        }
    }
}

@Composable
private fun TripRow(t: TripSummary) {
    // 칸 너비를 비율로 나눠 화면이 좁아도 줄이 넘어가지 않게 함
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        T(SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(Date(t.startMs)), 16.sp, C.Muted, maxLines = 1, modifier = Modifier.weight(1.3f))
        T(fmtDuration(t.durationMs), 17.sp, C.Text, FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1.3f))
        T(String.format(Locale.KOREA, "%.1fkm", t.distanceKm), 15.sp, C.Muted, maxLines = 1, modifier = Modifier.weight(1f))
        T("흔들림 ${t.shakes}", 15.sp, if (t.shakes > 0) C.Orange else C.Muted, maxLines = 1, modifier = Modifier.weight(1f))
        T("불안 ${t.soundCount}", 15.sp, if (t.soundCount > 0) C.Orange else C.Muted, maxLines = 1, modifier = Modifier.weight(0.9f))
        T("휴식 ${t.restCount}", 15.sp, C.Muted, maxLines = 1, modifier = Modifier.weight(0.8f))
    }
}

@Composable
private fun MiniStat(value: String, label: String, modifier: Modifier = Modifier, color: Color = C.Text) {
    val shape = RoundedCornerShape(18.dp)
    Column(modifier.clip(shape).background(Color(0x14A0AFC8)).padding(horizontal = 16.dp, vertical = 10.dp)) {
        T(value, 24.sp, color, FontWeight.Bold, maxLines = 1)
        T(label, 14.sp, C.Muted, maxLines = 1)
    }
}

@Composable
private fun Stat(value: String, label: String, color: Color = C.Text, last: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        T(value, 26.sp, color, FontWeight.Bold)
        T(label, 15.sp, C.Muted)
        if (!last) Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(1.dp).background(Color(0x2EA0AFC8)))
    }
}

// ======================================================================
// 공통: 알림, 디버그
// ======================================================================

@Composable
private fun BoxScope.Toast(msg: String?, onDone: () -> Unit) {
    LaunchedEffect(msg) { if (msg != null) { delay(3000); onDone() } }
    AnimatedVisibility(
        visible = msg != null, enter = fadeIn(), exit = fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
    ) {
        val shape = RoundedCornerShape(999.dp)
        Row(
            Modifier.clip(shape).background(Color(0xF01C283E)).border(1.dp, C.GlassLine, shape).padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Ico(R.drawable.ic_check, C.Green, 20.dp)
            Gap(10.dp)
            T(msg ?: "", 17.sp, C.Text)
        }
    }
}

/** 브랜드(펫온보드)를 누르면 열리는 개발용 줄: 실시간 차량 값 + 데모 버튼 */
@Composable
private fun DebugStrip(s: UiState, vm: PetModeViewModel) {
    val c = s.car
    Column(Modifier.fillMaxWidth().background(Color(0xFF060B14)).padding(horizontal = 24.dp, vertical = 8.dp)) {
        // 테스트 버튼을 맨 앞 줄에 (긴 값 줄에 밀려 화면 밖으로 나가지 않게)
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("테스트", 13.sp, C.Muted)
            DebugBtn("탑승", vm::demoBoarding)
            DebugBtn("바람 제안", vm::demoWindNow)
            DebugBtn("휴식 알림", vm::demoRestNow)
            DebugBtn("소리", vm::demoSound)
            DebugBtn("샘플 소리", vm::demoSoundSample)
            DebugBtn("급제동", vm::demoHardBrake)
            DebugBtn("기록 지우기", vm::demoClearHistory)
            DebugBtn(if (s.demoBeltOff) "벨트 풀림 끄기" else "벨트 풀림", vm::demoToggleBelt)
        }
        VGap(4.dp)
        T(
            "연결 ${c.connected.oX()} · ${"%.1f".format(c.speedKmh)}km/h · 기어 ${c.gear}(${c.rawGear}) · 핸들 ${c.steeringDeg.toInt()}° · 브레이크 ${c.brakePressed.oX()} · 와이퍼 ${c.wiperOn.oX()} · 문잠금 ${c.rearDoorsLocked?.oX() ?: "?"}",
            13.sp, C.Soft,
        )
        T(
            "착석지원 ${c.occupancySupported.oX()} · 뒷좌석 L ${c.rearLeftOccupied.oX()}(${c.rawRearLeft}) R ${c.rearRightOccupied.oX()}(${c.rawRearRight}) · 벨트지원 ${c.beltSupported.oX()} · 벨트 L(${c.rawBeltLeft}) R(${c.rawBeltRight})",
            13.sp, C.Soft,
        )
        // 최근 기록을 한 줄에 하나씩 (차량 영역 정보 줄은 길어서 뺌)
        s.log.filterNot { it.startsWith("[정보]") }.take(8).forEach { T(it, 13.sp, C.Muted, maxLines = 1) }
    }
}

@Composable
private fun DebugBtn(text: String, onClick: () -> Unit) {
    Box(
        Modifier.padding(start = 8.dp).clip(RoundedCornerShape(8.dp)).background(C.GhostFill).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp)
    ) { T(text, 13.sp, C.Text) }
}

// ======================================================================
// 형식 도우미
// ======================================================================

private fun Boolean.oX() = if (this) "O" else "X"

private fun Gear.label() = when (this) {
    Gear.PARK -> "P"; Gear.REVERSE -> "R"; Gear.NEUTRAL -> "N"; Gear.DRIVE -> "D"; Gear.UNKNOWN -> "-"
}

private fun SeatSide.label() = if (this == SeatSide.RIGHT) "뒷좌석 오른쪽" else "뒷좌석 왼쪽"

fun fmtDuration(ms: Long): String {
    val totalMin = (ms / 60000).toInt()
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 -> String.format(Locale.KOREA, "%d시간 %02d분", h, m)
        totalMin > 0 -> "${m}분"
        else -> "${(ms / 1000).coerceAtLeast(0)}초"
    }
}

private fun fmtClock(ms: Long): String = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(ms))
