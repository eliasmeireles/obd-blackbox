package io.github.eliasmeireles.obdblackbox.domain.obd

import io.github.eliasmeireles.obdblackbox.i18n.TextResources
import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import java.util.Locale

object DtcDescriptions {
    private var cached: Pair<Locale, Map<String, String>>? = null

    @Synchronized
    private fun descriptions(): Map<String, String> {
        val locale = TextResources.locale()
        cached?.takeIf { it.first == locale }?.let { return it.second }
        return buildDescriptions().also { cached = locale to it }
    }

    private fun buildDescriptions() = buildMap {
        fun codes(start: Int, descriptions: List<String>) = descriptions.forEachIndexed { i, text -> put("P%04d".format(Locale.ROOT, start + i), text) }
        fun circuit(start: Int, sensor: String) = codes(start, listOf(
            text(R.string.msg_value_falha_no_circuito, (sensor).toString()), text(R.string.msg_value_faixa_ou_desempenho, (sensor).toString()), text(R.string.msg_value_sinal_baixo, (sensor).toString()), text(R.string.msg_value_sinal_alto, (sensor).toString()), text(R.string.msg_value_sinal_intermitente, (sensor).toString()),
        ))
        circuit(100, text(R.string.msg_medidor_de_fluxo_de_ar_maf))
        circuit(105, text(R.string.msg_pressao_do_coletor_map))
        circuit(110, text(R.string.msg_temperatura_do_ar_de_admissao))
        circuit(115, text(R.string.msg_temperatura_do_liquido_de_arrefecimento))
        circuit(120, text(R.string.msg_posicao_da_borboleta_pedal_a))
        codes(125, listOf(text(R.string.msg_motor_frio_para_controle_da_mistura), text(R.string.msg_temperatura_insuficiente_para_funcionamento_estavel), text(R.string.msg_temperatura_do_ar_muito_alta), text(R.string.msg_motor_abaixo_da_temperatura_do_termostato), text(R.string.msg_pressao_barometrica_baixa)))
        listOf(130 to text(R.string.msg_banco_1_sensor_1), 136 to text(R.string.msg_banco_1_sensor_2), 142 to text(R.string.msg_banco_1_sensor_3), 150 to text(R.string.msg_banco_2_sensor_1), 156 to text(R.string.msg_banco_2_sensor_2), 162 to text(R.string.msg_banco_2_sensor_3)).forEach { (start, sensor) ->
            codes(start, listOf(text(R.string.msg_falha_no_circuito), text(R.string.msg_tensao_baixa), text(R.string.msg_tensao_alta), text(R.string.msg_resposta_lenta), text(R.string.msg_sem_atividade), text(R.string.msg_falha_no_aquecedor)).map { text(R.string.msg_sonda_lambda_value_value, (sensor).toString(), (it).toString()) })
        }
        put("P0148", text(R.string.msg_falha_no_fornecimento_de_combustivel))
        put("P0149", text(R.string.msg_falha_no_sincronismo_do_combustivel))
        codes(168, listOf(text(R.string.msg_temperatura_do_combustivel_muito_alta), text(R.string.msg_composicao_incorreta_do_combustivel), text(R.string.msg_correcao_da_mistura_banco_1), text(R.string.msg_mistura_pobre_banco_1), text(R.string.msg_mistura_rica_banco_1), text(R.string.msg_correcao_da_mistura_banco_2), text(R.string.msg_mistura_pobre_banco_2), text(R.string.msg_mistura_rica_banco_2)))
        put("P0200", text(R.string.msg_falha_no_circuito_dos_injetores))
        (1..6).forEach { put("P020$it", text(R.string.msg_falha_no_circuito_do_injetor_do_cilindro_value, (it).toString())) }
        put("P0300", text(R.string.msg_falhas_de_combustao_aleatorias_ou_em_varios_cilindros))
        (1..6).forEach { put("P030$it", text(R.string.msg_falha_de_combustao_no_cilindro_value, (it).toString())) }
        circuit(325, text(R.string.msg_sensor_de_detonacao_1_banco_1))
        circuit(330, text(R.string.msg_sensor_de_detonacao_2_banco_2))
        codes(335, listOf(text(R.string.msg_sensor_de_rotacao_a_falha_no_circuito), text(R.string.msg_sensor_de_rotacao_a_faixa_ou_desempenho), text(R.string.msg_sensor_de_rotacao_a_sinal_baixo), text(R.string.msg_sensor_de_rotacao_a_sinal_alto), text(R.string.msg_sensor_de_rotacao_a_sinal_intermitente), text(R.string.msg_sensor_de_fase_a_falha_no_circuito)))
        codes(400, listOf(text(R.string.msg_recirculacao_de_gases_egr_falha_de_fluxo), text(R.string.msg_egr_fluxo_insuficiente), text(R.string.msg_egr_fluxo_excessivo), text(R.string.msg_egr_falha_no_circuito), text(R.string.msg_egr_faixa_ou_desempenho), text(R.string.msg_egr_sensor_a_com_sinal_baixo), text(R.string.msg_egr_sensor_a_com_sinal_alto), text(R.string.msg_egr_sensor_b_com_sinal_baixo), text(R.string.msg_egr_sensor_b_com_sinal_alto), text(R.string.msg_egr_circuito_do_sensor_a)))
        codes(410, listOf(text(R.string.msg_injecao_de_ar_secundario_falha), text(R.string.msg_injecao_de_ar_secundario_fluxo_incorreto), text(R.string.msg_valvula_a_de_ar_secundario_circuito), text(R.string.msg_valvula_a_de_ar_secundario_circuito_aberto), text(R.string.msg_valvula_a_de_ar_secundario_curto), text(R.string.msg_valvula_b_de_ar_secundario_circuito), text(R.string.msg_valvula_b_de_ar_secundario_circuito_aberto), text(R.string.msg_valvula_b_de_ar_secundario_curto), text(R.string.msg_rele_a_de_ar_secundario_falha), text(R.string.msg_rele_b_de_ar_secundario_falha)))
        listOf(420 to text(R.string.msg_eficiencia_do_catalisador_abaixo_do_limite_banco_1), 421 to text(R.string.msg_catalisador_de_aquecimento_abaixo_do_limite_banco_1), 422 to text(R.string.msg_catalisador_principal_abaixo_do_limite_banco_1), 423 to text(R.string.msg_catalisador_aquecido_abaixo_do_limite_banco_1), 424 to text(R.string.msg_temperatura_do_catalisador_baixa_banco_1), 430 to text(R.string.msg_eficiencia_do_catalisador_abaixo_do_limite_banco_2), 431 to text(R.string.msg_catalisador_de_aquecimento_abaixo_do_limite_banco_2), 432 to text(R.string.msg_catalisador_principal_abaixo_do_limite_banco_2), 433 to text(R.string.msg_catalisador_aquecido_abaixo_do_limite_banco_2), 434 to text(R.string.msg_temperatura_do_catalisador_baixa_banco_2)).forEach { (code, text) -> put("P%04d".format(Locale.ROOT, code), text) }
        circuit(425, text(R.string.msg_sensor_de_temperatura_do_catalisador_banco_1))
        circuit(435, text(R.string.msg_sensor_de_temperatura_do_catalisador_banco_2))
        codes(440, listOf(text(R.string.msg_sistema_evaporativo_evap_falha), text(R.string.msg_evap_fluxo_incorreto_de_purga), text(R.string.msg_evap_vazamento_pequeno), text(R.string.msg_evap_circuito_da_valvula_de_purga), text(R.string.msg_evap_circuito_de_purga_aberto), text(R.string.msg_evap_curto_na_valvula_de_purga), text(R.string.msg_evap_circuito_da_ventilacao), text(R.string.msg_evap_circuito_de_ventilacao_aberto), text(R.string.msg_evap_curto_na_ventilacao), text(R.string.msg_evap_circuito_da_valvula_de_ventilacao)))
        circuit(450, text(R.string.msg_sensor_de_pressao_do_sistema_evaporativo))
        put("P0455", text(R.string.msg_sistema_evaporativo_vazamento_grande_ou_sem_fluxo_de_purga))
        codes(500, listOf(text(R.string.msg_sensor_de_velocidade_falha), text(R.string.msg_sensor_de_velocidade_faixa_ou_desempenho), text(R.string.msg_sensor_de_velocidade_sinal_baixo), text(R.string.msg_sensor_de_velocidade_sinal_intermitente), text(R.string.msg_interruptor_do_freio_sinais_incompativeis), text(R.string.msg_controle_da_marcha_lenta_falha), text(R.string.msg_marcha_lenta_abaixo_do_esperado), text(R.string.msg_marcha_lenta_acima_do_esperado)))
        codes(560, listOf(text(R.string.msg_tensao_do_sistema_falha), text(R.string.msg_tensao_do_sistema_instavel), text(R.string.msg_tensao_do_sistema_baixa)))
        codes(600, listOf(text(R.string.msg_comunicacao_serial_da_central_falha), text(R.string.msg_central_erro_de_memoria), text(R.string.msg_central_erro_de_programacao), text(R.string.msg_central_erro_na_memoria_de_aprendizado), text(R.string.msg_central_erro_na_memoria_ram), text(R.string.msg_central_erro_na_memoria_rom), text(R.string.msg_central_erro_no_processador)))
        put("P0700", text(R.string.msg_sistema_de_controle_do_cambio_falha))
    }

    fun describe(code: String): String {
        val normalized = code.trim().uppercase(Locale.ROOT)
        descriptions()[normalized]?.let { return it }
        return when {
            normalized.startsWith("C") -> text(R.string.msg_chassi)
            normalized.startsWith("B") -> text(R.string.msg_carroceria)
            normalized.startsWith("U") -> text(R.string.msg_rede_de_comunicacao_entre_centrais)
            normalized.take(3) in setOf("P00", "P01", "P02") -> text(R.string.msg_mistura_e_medicao_de_ar_combustivel)
            normalized.startsWith("P03") -> text(R.string.msg_ignicao_e_falhas_de_combustao)
            normalized.startsWith("P04") -> text(R.string.msg_controle_de_emissoes)
            normalized.startsWith("P05") -> text(R.string.msg_velocidade_marcha_lenta_e_entradas)
            normalized.startsWith("P06") -> text(R.string.msg_central_e_saidas)
            normalized.take(3) in setOf("P07", "P08", "P09") -> text(R.string.msg_cambio)
            else -> text(R.string.msg_codigo_especifico_do_fabricante_consulte_a_documentacao_da)
        }
    }
}
