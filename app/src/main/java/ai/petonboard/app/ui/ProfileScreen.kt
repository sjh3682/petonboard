package ai.petonboard.app.ui

import ai.petonboard.app.R
import ai.petonboard.app.logic.Breeds
import ai.petonboard.app.logic.PetModeViewModel
import ai.petonboard.app.logic.PetProfile
import ai.petonboard.app.logic.PetSize
import ai.petonboard.app.logic.UiState
import ai.petonboard.app.vehicle.SeatSide
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import kotlin.math.roundToInt

/** 반려견 프로필: 이름, 견종, 크기, 앉는 자리, 바람 쐬기 창문 높이(직접 맞추기) */
@Composable
fun ProfileScreen(s: UiState, vm: PetModeViewModel, firstRun: Boolean) {
    val p = s.profile
    var name by remember { mutableStateOf(p.name) }
    var breed by remember { mutableStateOf(p.breed) }
    var size by remember { mutableStateOf(p.size) }
    var seat by remember { mutableStateOf(p.seat) }
    var wind by remember { mutableStateOf(p.windPercent.toFloat()) }
    var brachy by remember { mutableStateOf(p.brachy) }
    var temp by remember { mutableStateOf(p.tempC) }
    var restMin by remember { mutableStateOf(p.restIntervalMin) }

    Row(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        // 왼쪽: 기본 정보
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            T(if (firstRun) "반려견을 알려 주세요" else "반려견 프로필", 38.sp, C.Text, FontWeight.Bold)
            VGap(6.dp)
            T("한 번만 입력하면 펫 모드가 이 정보로 맞춰져요", 18.sp, C.Muted)
            VGap(22.dp)
            Field("이름", name) { name = it }
            VGap(14.dp)
            Field("견종 (선택)", breed) {
                breed = it
                // 견종 이름으로 단두종이면 자동으로 켜고 온도를 낮춤 (보호자가 다시 끌 수 있음)
                if (!brachy && Breeds.isBrachy(it)) { brachy = true; if (temp > Breeds.BRACHY_TEMP_C) temp = Breeds.BRACHY_TEMP_C }
            }
            VGap(20.dp)
            T("크기", 17.sp, C.Muted)
            VGap(8.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PetSize.entries.forEach { sz ->
                    Choice(sz.label, size == sz) {
                        size = sz
                        wind = sz.defaultWindPercent.toFloat()
                    }
                }
            }
            VGap(20.dp)
            T("앉는 자리", 17.sp, C.Muted)
            VGap(8.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Choice("뒷좌석 왼쪽", seat == SeatSide.LEFT) { seat = SeatSide.LEFT }
                Choice("뒷좌석 오른쪽", seat == SeatSide.RIGHT) { seat = SeatSide.RIGHT }
            }
            VGap(20.dp)
            T("펫 모드 실내 온도", 17.sp, C.Muted)
            VGap(8.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(20f, 21f, 22f, 23f).forEach { t -> Choice("${t.toInt()}°C", temp == t) { temp = t } }
            }
            VGap(12.dp)
            Choice(if (brachy) "코가 짧은 견종이에요 (퍼그·불독 등)" else "코가 짧은 견종인가요? (퍼그·불독 등)", brachy) {
                brachy = !brachy
                if (brachy && temp > Breeds.BRACHY_TEMP_C) temp = Breeds.BRACHY_TEMP_C
            }
            if (brachy) {
                VGap(8.dp)
                T("코가 짧은 견종은 21~22°C에서도 더위를 탈 수 있어요. 21°C 이하를 권해요", 15.sp, C.Muted, lineHeight = 22.sp)
            }
            VGap(20.dp)
            T("휴식 알림 간격", 17.sp, C.Muted)
            VGap(8.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(40, 60, 90, 120).forEach { m -> Choice(if (m % 60 == 0) "${m / 60}시간" else "${m}분", restMin == m) { restMin = m } }
            }
            if (restMin !in listOf(40, 60, 90, 120)) { VGap(6.dp); T("지금 ${restMin}분 (기록으로 자동 조정됨)", 15.sp, C.Muted) }
            VGap(6.dp)
            T("주행 기록에서 ${name.ifBlank { "반려견" }}가 일찍 불안해하면 자동으로 당겨요", 15.sp, C.Muted)
        }

        // 오른쪽: 바람 쐬기 창문 높이
        Column(Modifier.width(420.dp).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            Glass(Modifier.fillMaxWidth(), padding = PaddingValues(26.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Ico(R.drawable.ic_window, C.BlueLight, 26.dp); Gap(10.dp)
                    T("바람 쐬기 창문 높이", 20.sp, C.Text, FontWeight.Bold)
                }
                VGap(10.dp)
                T("${wind.roundToInt()}%", 44.sp, C.Text, FontWeight.Bold)
                Slider(
                    value = wind,
                    onValueChange = { wind = it },
                    onValueChangeFinished = { if (s.previewWindow) vm.previewWindow(seat, wind.roundToInt()) },
                    valueRange = 5f..60f,
                    colors = SliderDefaults.colors(thumbColor = C.Blue, activeTrackColor = C.Blue, inactiveTrackColor = Color(0x33A0AFC8)),
                )
                T(
                    "${name.ifBlank { "반려견" }}를 태운 채로 창문을 열어 보고, 머리가 밖으로 나가지 않는 높이로 맞춰 주세요. 차마다 창문 크기가 달라서 직접 맞추는 게 가장 정확해요.",
                    15.sp, C.Muted, lineHeight = 22.sp,
                )
                VGap(16.dp)
                if (s.previewWindow) {
                    GhostButton("창문 닫기", Modifier.fillMaxWidth(), R.drawable.ic_window, height = 56.dp, onClick = vm::stopWindowPreview)
                } else {
                    GhostButton("이 높이로 창문 열어 보기", Modifier.fillMaxWidth(), R.drawable.ic_wind, height = 56.dp) {
                        vm.previewWindow(seat, wind.roundToInt())
                    }
                }
                VGap(6.dp)
                T("정차(P) 중에만 열려요", 14.sp, C.Muted, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            VGap(18.dp)
            PrimaryButton("저장", Modifier.fillMaxWidth(), R.drawable.ic_check, 66.dp) {
                vm.saveProfile(
                    PetProfile(
                        name = name.trim().ifBlank { "반려견" },
                        breed = breed.trim(),
                        size = size,
                        seat = seat,
                        tempC = temp,
                        windPercent = wind.roundToInt(),
                        brachy = brachy,
                        restIntervalMin = restMin,
                    )
                )
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(20)) },
        label = { Text(label) },
        singleLine = true,
        textStyle = TextStyle(fontSize = 20.sp, color = C.Text),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = C.Text,
            unfocusedTextColor = C.Text,
            focusedBorderColor = C.Blue,
            unfocusedBorderColor = C.GhostLine,
            focusedLabelColor = C.BlueLight,
            unfocusedLabelColor = C.Muted,
            cursorColor = C.Blue,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier
            .clip(shape)
            .background(if (selected) C.BlueChip else C.GhostFill)
            .border(if (selected) 2.dp else 1.dp, if (selected) C.Blue else C.GhostLine, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) { Ico(R.drawable.ic_check, C.BlueLight, 18.dp); Spacer(Modifier.width(8.dp)) }
        T(text, 18.sp, if (selected) C.Text else C.Soft, FontWeight.Medium)
    }
}
