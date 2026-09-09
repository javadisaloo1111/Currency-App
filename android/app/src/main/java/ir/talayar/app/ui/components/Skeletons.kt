package ir.talayar.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import ir.talayar.app.ui.theme.Dimens

/** Animated shimmer modifier for skeleton loading states. */
fun Modifier.shimmer(): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerProgress",
    )
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.surfaceContainerHigh
    val brush = Brush.linearGradient(
        colors = listOf(base, highlight, base),
        start = Offset(x = -400f + 1400f * progress, y = 0f),
        end = Offset(x = 0f + 1400f * progress, y = 220f),
    )
    background(brush)
}

@Composable
fun SkeletonBox(modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.medium) {
    Box(modifier = modifier.clip(shape).shimmer())
}

/** Skeleton for the home screen while the first load is in flight. */
@Composable
fun HomeSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.SpaceL),
    ) {
        SkeletonBox(Modifier.size(width = 140.dp, height = 22.dp))
        SkeletonBox(Modifier.size(width = 90.dp, height = 16.dp))
        SkeletonBox(
            Modifier
                .fillMaxWidth()
                .height(150.dp),
            shape = MaterialTheme.shapes.extraLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.SpaceM)) {
            SkeletonBox(Modifier.weight(1f).height(84.dp))
            SkeletonBox(Modifier.weight(1f).height(84.dp))
            SkeletonBox(Modifier.weight(1f).height(84.dp))
        }
        SkeletonBox(Modifier.size(width = 110.dp, height = 18.dp))
        SkeletonItemRow()
        SkeletonItemRow()
        SkeletonItemRow()
    }
}

/** Skeleton for list rows (market / favorites). */
@Composable
fun SkeletonItemRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Dimens.SpaceS),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        SkeletonBox(Modifier.size(Dimens.CategoryIconSize), shape = CircleShape)
        Spacer(Modifier.width(Dimens.SpaceL))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            SkeletonBox(Modifier.size(width = 120.dp, height = 16.dp))
            SkeletonBox(Modifier.size(width = 70.dp, height = 12.dp))
        }
        SkeletonBox(Modifier.size(width = 90.dp, height = 18.dp))
    }
}

@Composable
fun ListSkeleton(items: Int = 8, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.ScreenPadding),
    ) {
        repeat(items) { SkeletonItemRow() }
    }
}
