package io.github.eliasmeireles.obdblackbox.ui.diagnostics

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.eliasmeireles.obdblackbox.ui.ScreenOrientation
import io.github.eliasmeireles.obdblackbox.ui.theme.AppColors

/** App settings shown at the top of the Dados tab. */
data class Settings(
    val simulation: Boolean,
    val onSimulationChange: (Boolean) -> Unit,
    val orientation: ScreenOrientation,
    val onOrientationChange: (ScreenOrientation) -> Unit,
    val adapter: AdapterSettings,
    val preferences: PreferenceSettings,
)

/** Simulation is opt-in: simulated data is tagged "sim" and never mixed with the car's data. */
fun LazyListScope.settingsSection(settings: Settings) {
    item { SectionTitle(text(R.string.msg_configuracoes)) }
    item { AdapterRow(settings.adapter) }
    item { SimulationRow(settings.simulation, settings.onSimulationChange) }
    item { OrientationRow(settings.orientation, settings.onOrientationChange) }
    preferencesSection(settings.preferences)
}

@Composable
private fun SettingRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AppColors.Surface).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun SimulationRow(enabled: Boolean, onChange: (Boolean) -> Unit) = SettingRow {
    Column(Modifier.weight(1f)) {
        Text(text(R.string.msg_modo_simulacao), color = AppColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(
            text(R.string.msg_gera_dados_de_um_carro_simulado_para_testar_o_app_ficam_ma),
            color = AppColors.Muted,
            fontSize = 13.sp,
        )
    }
    Spacer(Modifier.width(12.dp))
    Switch(
        checked = enabled,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(checkedTrackColor = AppColors.Warning, checkedThumbColor = AppColors.Background),
    )
}

@Composable
private fun OrientationRow(current: ScreenOrientation, onChange: (ScreenOrientation) -> Unit) =
    SettingCard(text(R.string.msg_orientacao_da_tela)) {
        ChoiceChips(ScreenOrientation.entries, current, { it.label }, onChange)
    }

/** A titled settings card, the same surface as the other rows of the Dados tab. */
@Composable
internal fun SettingCard(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(AppColors.Surface).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (title != null) Text(title, color = AppColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

/** One choice among a few, as pills that wrap on narrow screens. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> ChoiceChips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            val chosen = option == selected
            Text(
                label(option),
                color = if (chosen) AppColors.Background else AppColors.Text,
                fontSize = 14.sp,
                fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (chosen) AppColors.Accent else AppColors.SurfaceHigh)
                    .clickable { onSelect(option) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}
