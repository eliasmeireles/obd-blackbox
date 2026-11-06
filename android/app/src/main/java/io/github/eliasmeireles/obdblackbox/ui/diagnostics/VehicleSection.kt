package io.github.eliasmeireles.obdblackbox.ui.diagnostics

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import io.github.eliasmeireles.obdblackbox.ui.theme.AppColors
import io.github.eliasmeireles.obdblackbox.domain.fuel.FuelType
import io.github.eliasmeireles.obdblackbox.domain.vehicle.VehicleProfile
import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R

data class VehicleSettings(val profile: VehicleProfile, val onSave: (VehicleProfile) -> Unit)

internal fun LazyListScope.vehicleSection(settings: VehicleSettings) {
    item { SectionTitle(text(R.string.msg_veiculo)) }
    item { VehicleEditor(settings) }
}

@Composable
private fun VehicleEditor(settings: VehicleSettings) {
    val profile = settings.profile
    var name by rememberSaveable(profile) { mutableStateOf(profile.name) }
    var tank by rememberSaveable(profile) { mutableStateOf(profile.tankCapacityL.toString()) }
    var displacement by rememberSaveable(profile) { mutableStateOf(profile.displacementL.toString()) }
    var fuel by rememberSaveable(profile) { mutableStateOf(profile.fuel) }
    val tankValue = positive(tank)
    val engineValue = positive(displacement)
    SettingCard(title = null) {
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(text(R.string.msg_nome)) },
            placeholder = { Text(text(R.string.my_car)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = tank, onValueChange = { tank = it }, label = { Text(text(R.string.msg_capacidade_do_tanque_l)) },
            isError = tankValue == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = displacement, onValueChange = { displacement = it }, label = { Text(text(R.string.msg_cilindrada_l)) },
            isError = engineValue == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Text(text(R.string.msg_combustivel_467), color = AppColors.Text, fontSize = 14.sp)
        ChoiceChips(FuelType.entries, fuel, { it.label }) { fuel = it }
        Text(text(R.string.msg_flex_usa_a_estequiometria_da_gasolina_os_calculos_sao_esti), color = AppColors.Muted, fontSize = 13.sp)
        Text(text(R.string.msg_salvar_inicia_uma_nova_sessao_e_recalcula_a_autonomia_com_), color = AppColors.Muted, fontSize = 13.sp)
        val valid = tankValue != null && engineValue != null
        OutlinedButton(
            onClick = { if (tankValue != null && engineValue != null) settings.onSave(VehicleProfile(name.trim(), tankValue, engineValue, fuel)) },
            enabled = valid,
            shape = RoundedCornerShape(10.dp),
        ) { Text(text(R.string.msg_salvar_veiculo), color = if (valid) AppColors.Accent else AppColors.Muted) }
    }
}

private fun positive(value: String) = value.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
