package ai.petonboard.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------- 글자 ----------

@Composable
fun T(
    text: String,
    size: TextUnit = 16.sp,
    color: Color = C.Text,
    weight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
    lineHeight: TextUnit = TextUnit.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
) = Text(
    text = text,
    modifier = modifier,
    maxLines = maxLines,
    style = TextStyle(fontSize = size, color = color, fontWeight = weight, lineHeight = lineHeight, letterSpacing = (-0.2).sp),
)

@Composable
fun Ico(@DrawableRes id: Int, tint: Color = C.Soft, size: Dp = 22.dp) =
    Icon(painterResource(id), contentDescription = null, tint = tint, modifier = Modifier.size(size))

// ---------- 카드 ----------

private val CardShape = RoundedCornerShape(22.dp)

@Composable
fun Glass(
    modifier: Modifier = Modifier,
    shape: Shape = CardShape,
    padding: PaddingValues = PaddingValues(22.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(shape)
            .background(C.glass)
            .border(1.dp, C.GlassLine, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** 작은 칩: 아이콘 + 글자 (예: 뒷창문 잠금) */
@Composable
fun Chip(@DrawableRes icon: Int, text: String, tint: Color = C.BlueLight, active: Boolean = true) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier
            .clip(shape)
            .background(if (active) C.GhostFill else Color.Transparent)
            .border(1.dp, C.GhostLine, shape)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Ico(icon, if (active) tint else C.Muted, 19.dp)
        T(text, 17.sp, if (active) C.Text else C.Muted, FontWeight.Medium)
    }
}

/** 파란 안내 띠 (예: 뒷좌석 오른쪽에 탑승이 감지됐어요) */
@Composable
fun InfoTag(@DrawableRes icon: Int, text: String) {
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(C.BlueChip).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Ico(icon, C.BlueLight, 18.dp)
        T(text, 15.sp, C.BlueLight)
    }
}

/** 상태 점 + 글자 알약 (예: ● 펫 모드 켜짐) */
@Composable
fun Pill(text: String) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier.clip(shape).background(C.BlueChip).border(1.dp, Color(0x595B95FF), shape).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFF6FA3FF)))
        T(text, 14.sp, Color(0xFFA9C6FF), FontWeight.Medium)
    }
}

// ---------- 버튼 ----------

@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    height: Dp = 64.dp,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier
            .height(height)
            .shadow(18.dp, shape, ambientColor = Color(0xFF2F63F0), spotColor = Color(0xFF2F63F0))
            .clip(shape)
            .background(C.primary)
            .clickable(onClick = onClick)
            .padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { Ico(icon, Color.White, 22.dp); Spacer(Modifier.width(12.dp)) }
        T(text, 21.sp, Color.White, FontWeight.Bold)
    }
}

@Composable
fun GhostButton(
    text: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    sub: String? = null,
    height: Dp = 64.dp,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier
            .height(height)
            .clip(shape)
            .background(C.GhostFill)
            .border(1.dp, C.GhostLine, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) { Ico(icon, C.Soft, 22.dp); Spacer(Modifier.width(12.dp)) }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            T(text, 19.sp, C.Text, FontWeight.Medium)
            if (sub != null) T(sub, 13.sp, C.Muted)
        }
    }
}

// ---------- 반려견 사진 ----------

/** 그라데이션 테두리 동그란 사진 */
@Composable
fun Avatar(@DrawableRes img: Int, size: Dp, ring: Dp = 4.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(C.ring).padding(ring)) {
        Image(
            painterResource(img), contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().clip(CircleShape).border(ring, C.Bg, CircleShape),
        )
    }
}

/** 원 안 아이콘 (상태 표시) */
@Composable
fun IconDot(@DrawableRes icon: Int, tint: Color, size: Dp = 46.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Ico(icon, tint, size * 0.5f)
    }
}

/** 진행 막대 */
@Composable
fun Progress(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x29A0AFC8))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(C.bar))
    }
}

@Composable
fun Divider() = Box(Modifier.fillMaxWidth().padding(vertical = 16.dp).height(1.dp).background(Color(0x2EA0AFC8)))

@Composable
fun RowScope.Gap(w: Dp) = Spacer(Modifier.width(w))

@Composable
fun ColumnScope.VGap(h: Dp) = Spacer(Modifier.height(h))
