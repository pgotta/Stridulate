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
        AppBarRow("Settings", "DETECTION RELIABILITY", onBack = onBack)
        Text(
            "Only tiers enabled here can appear in rolling identification or be saved as detections. The full model still runs so hidden classes cannot inflate another species' confidence.",
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
            description = "Strongest independent support in this model release.",
            onChanged = { onTierChanged(ReliabilityTier.VERIFIED, it) }
        )
        Spacer(Modifier.height(9.dp))
        TierSettingRow(
            tier = ReliabilityTier.GOOD,
            count = 3,
            enabled = settings.good,
            description = "Useful support, but still requires field-guide confirmation.",
            onChanged = { onTierChanged(ReliabilityTier.GOOD, it) }
        )
        Spacer(Modifier.height(9.dp))
        TierSettingRow(
            tier = ReliabilityTier.EXPERIMENTAL,
            count = 32,
            enabled = settings.experimental,
            description = "Limited or uneven validation. Disabled by default.",
            onChanged = { onTierChanged(ReliabilityTier.EXPERIMENTAL, it) }
        )
        Spacer(Modifier.height(9.dp))
        TierSettingRow(
            tier = ReliabilityTier.NOT_READY,
            count = 17,
            enabled = false,
            description = "Never returned as a primary detection.",
            onChanged = {},
            locked = true
        )
        Spacer(Modifier.height(18.dp))
        Text("Recommended default", fontFamily = Fraunces, fontSize = 17.sp, color = Parch)
        Spacer(Modifier.height(5.dp))
        Text(
            "Verified + Good. Experimental remains available in the field guide even when detection is disabled.",
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
    onChanged: (Boolean) -> Unit,
    locked: Boolean = false
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
            enabled = !locked,
            colors = SwitchDefaults.colors(checkedThumbColor = Biolume, checkedTrackColor = Biolume.copy(alpha = 0.35f))
        )
    }
}
