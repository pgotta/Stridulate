package com.pgotta.stridulate.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pgotta.stridulate.data.DetectionTierSettings
import com.pgotta.stridulate.data.ReliabilityTier
import com.pgotta.stridulate.ui.theme.Biolume
import com.pgotta.stridulate.ui.theme.Fraunces
import com.pgotta.stridulate.ui.theme.Inter
import com.pgotta.stridulate.ui.theme.JetBrainsMono
import com.pgotta.stridulate.ui.theme.Line
import com.pgotta.stridulate.ui.theme.Mute
import com.pgotta.stridulate.ui.theme.Panel
import com.pgotta.stridulate.ui.theme.Parch
import com.pgotta.stridulate.ui.theme.ParchDim

@Composable
fun SettingsScreen(
    settings: DetectionTierSettings,
    onBack: () -> Unit,
    onTierChanged: (ReliabilityTier, Boolean) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        AppBarRow("Settings", "FROZEN J.1 · 88 SPECIES", onBack = onBack)
        Text(
            "All 88 frozen J.1 acoustic classes are available by default. These switches only control which accepted classes can appear in rolling identification; disabling a tier does not change the model scores.",
            fontFamily = Inter,
            fontSize = 13.sp,
            color = ParchDim,
            lineHeight = 19.sp
        )
        Spacer(Modifier.height(15.dp))
        TierSettingRow(
            tier = ReliabilityTier.VERIFIED,
            count = 14,
            enabled = settings.verified,
            description = "Strongest species-specific independent support in the prior Android reliability audit.",
            onChanged = { onTierChanged(ReliabilityTier.VERIFIED, it) }
        )
        Spacer(Modifier.height(9.dp))
        TierSettingRow(
            tier = ReliabilityTier.GOOD,
            count = 3,
            enabled = settings.good,
            description = "Useful species-specific support; still confirm with the field guide and call pattern.",
            onChanged = { onTierChanged(ReliabilityTier.GOOD, it) }
        )
        Spacer(Modifier.height(9.dp))
        TierSettingRow(
            tier = ReliabilityTier.EXPERIMENTAL,
            count = 71,
            enabled = settings.experimental,
            description = "Includes prior Experimental/Not Ready classes plus newly added J.1 classes. J.1 still requires its frozen per-species evidence threshold before showing a detection.",
            onChanged = { onTierChanged(ReliabilityTier.EXPERIMENTAL, it) }
        )
        Spacer(Modifier.height(18.dp))
        Text("Recommended default", fontFamily = Fraunces, fontSize = 17.sp, color = Parch)
        Spacer(Modifier.height(5.dp))
        Text(
            "Verified + Good + Experimental enabled. This exposes the full 88-species J.1 catalog while the frozen J.1 evidence thresholds remain the actual acceptance gate.",
            fontFamily = JetBrainsMono,
            fontSize = 10.5.sp,
            color = Mute,
            lineHeight = 16.sp
        )
    }
}

@Composable
private fun TierSettingRow(
    tier: ReliabilityTier,
    count: Int,
    enabled: Boolean,
    description: String,
    onChanged: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(13.dp))
            .border(BorderStroke(1.dp, Line), RoundedCornerShape(13.dp)).padding(13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("${tier.displayName} · $count species", fontFamily = Fraunces, fontSize = 16.sp, color = Parch)
            Text(description, fontFamily = Inter, fontSize = 12.sp, color = ParchDim, lineHeight = 17.sp)
        }
        Switch(
            checked = enabled,
            onCheckedChange = onChanged,
            colors = SwitchDefaults.colors(checkedThumbColor = Biolume, checkedTrackColor = Biolume.copy(alpha = 0.35f))
        )
    }
}
