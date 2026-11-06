package io.github.eliasmeireles.obdblackbox.data.elm

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import android.bluetooth.BluetoothManager
import android.content.Context
import io.github.eliasmeireles.obdblackbox.data.bridge.BluetoothTarget
import io.github.eliasmeireles.obdblackbox.data.bridge.LinkMode
import java.io.IOException

/** Outcome of trying a device as the OBD adapter. */
sealed interface ProbeResult {
    /** Connected and answered as an ELM327; [target] says how (classic or BLE). */
    data class Compatible(val target: BluetoothTarget, val identity: String) : ProbeResult

    /** Why it did not work, and what the user can do about it. */
    data class Failed(val reason: String, val hint: String) : ProbeResult
}

/**
 * Tries a device picked from the list: connects (Bluetooth classic first when available, then BLE), resets it and
 * asks for its identity (`ATI`). Only an answer that names an ELM327-compatible chip counts as compatible.
 * Nothing is sent to the car. Blocking: run it off the main thread, with the current adapter released.
 */
class AdapterProbe(
    private val context: Context,
    private val trace: ElmTrace,
    private val connect: (LinkMode, String) -> ElmLink = { mode, address -> openLink(context, mode, address, trace) },
) {
    fun probe(found: FoundAdapter): ProbeResult {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) return ProbeResult.Failed(text(R.string.msg_este_celular_nao_tem_bluetooth), "")
        if (!hasBluetoothPermission(context)) return ProbeResult.Failed(text(R.string.msg_o_app_nao_tem_permissao_de_bluetooth), text(R.string.msg_permita_o_acesso_ao_bluetooth_e_tente_de_novo))
        if (!adapter.isEnabled) return ProbeResult.Failed(text(R.string.msg_o_bluetooth_do_celular_esta_desligado), text(R.string.msg_ligue_o_bluetooth_e_tente_de_novo))
        return ProbeLogic(trace) { mode -> connect(mode, found.address) }.probe(found)
    }

    private companion object {
        fun openLink(context: Context, mode: LinkMode, address: String, trace: ElmTrace): ElmLink {
            val adapter = checkNotNull(context.getSystemService(BluetoothManager::class.java)?.adapter)
            return when (mode) {
                LinkMode.SPP -> SppLink.open(adapter, address, trace)
                LinkMode.BLE -> BleLink.open(context, adapter, address, trace)
            }
        }
    }
}

/** The decision part of [AdapterProbe], without Android: which links to try, how to read the identity, what to say. */
class ProbeLogic(private val trace: ElmTrace, private val connect: (LinkMode) -> ElmLink) {
    fun probe(found: FoundAdapter): ProbeResult {
        val modes = buildList {
            if (found.classic) add(LinkMode.SPP)
            if (found.ble) add(LinkMode.BLE)
            if (isEmpty()) addAll(listOf(LinkMode.SPP, LinkMode.BLE))
        }
        trace.log("connecting to ${found.displayName} ${found.address} (test: ${modes.joinToString { it.tag }})")
        var failure: ProbeResult.Failed? = null
        for (mode in modes) {
            val link = try {
                connect(mode)
            } catch (e: IOException) {
                failure = notConnected(mode, e, found)
                continue
            }
            try {
                val identity = identify(link)
                if (identity != null) {
                    trace.log("test ok: $identity over ${mode.tag}")
                    return ProbeResult.Compatible(BluetoothTarget(found.address, found.displayName, mode), identity)
                }
                failure = ProbeResult.Failed(
                    text(R.string.msg_conectou_pelo_value_mas_nao_respondeu_como_adaptador_obd_e, (mode.label).toString()),
                    text(R.string.msg_este_aparelho_nao_e_compativel_escolha_o_adaptador_que_fic),
                )
            } catch (e: IOException) {
                failure = ProbeResult.Failed(
                    text(R.string.msg_conectou_pelo_value_mas_a_conexao_caiu_durante_o_teste_val, (mode.label).toString(), (e.message).toString()),
                    text(R.string.msg_feche_outros_apps_de_obd_car_scanner_e_tente_de_novo),
                )
            } finally {
                runCatching { link.close() }
            }
        }
        trace.log("! test failed: ${failure?.reason}")
        return failure ?: ProbeResult.Failed(text(R.string.msg_nao_foi_possivel_testar_o_aparelho), "")
    }

    /** The adapter's identity ("ELM327 v1.5"), or null when the device answers but is not an ELM327. */
    private fun identify(link: ElmLink): String? {
        val elm = Elm327(link, trace)
        val reset = runCatching { elm.send("ATZ", RESET_TIMEOUT_MS) }.getOrDefault(emptyList())
        Thread.sleep(SETTLE_MS)
        val answer = try {
            elm.send("ATI", IDENTITY_TIMEOUT_MS)
        } catch (e: IOException) {
            if (e.message?.contains(text(R.string.msg_nao_respondeu)) == true) return null
            throw e
        }
        return (answer + reset).firstOrNull { line -> CHIPS.any { line.contains(it, ignoreCase = true) } }?.trim()
    }

    private fun notConnected(mode: LinkMode, error: IOException, found: FoundAdapter): ProbeResult.Failed {
        val message = error.message.orEmpty()
        val pairing = if (!found.paired && mode == LinkMode.SPP) text(R.string.msg_se_o_android_pedir_um_codigo_de_pareamento_tente_1234_ou_0) else ""
        return when {
            text(R.string.msg_canal_serial) in message -> ProbeResult.Failed(
                text(R.string.msg_nao_tem_o_canal_de_dados_usado_pelos_adaptadores_obd_ble),
                text(R.string.msg_este_aparelho_nao_e_compativel_escolha_o_adaptador_que_fic),
            )
            text(R.string.msg_nao_respondeu_pelo_ble) in message -> ProbeResult.Failed(
                text(R.string.msg_nao_respondeu_pelo_bluetooth_ble),
                text(R.string.msg_confira_se_o_adaptador_esta_ligado_luz_acesa_ignicao_ligad),
            )
            else -> ProbeResult.Failed(
                text(R.string.msg_nao_aceitou_a_conexao_pelo_value, (mode.label).toString()),
                text(R.string.msg_confira_se_o_adaptador_esta_na_tomada_obd_com_a_ignicao_li) +
                    text(R.string.msg_outro_app_de_obd_conectado_feche_o_car_scanner_value, (pairing).toString()),
            )
        }
    }

    private companion object {
        val CHIPS = listOf("ELM", "STN", "OBDLINK", "VLINK", "V-LINK")
        const val RESET_TIMEOUT_MS = 4_000L
        const val IDENTITY_TIMEOUT_MS = 3_000L
        const val SETTLE_MS = 300L
    }
}
