package br.com.empresa.sdui.contract.component

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Destino de uma action. [route] e uma rota app:// do proprio app; [sheet] identifica um
 * bottom sheet nativo. Nenhum dos dois carrega URL externa ou dado sensivel.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ActionPayload(
    val route: String? = null,
    val sheet: String? = null,
)

/**
 * Intencao serializada que o dispatcher nativo executa quando o usuario interage.
 *
 * O servidor descreve o que deve acontecer, nunca como navegar: [type] vem do catalogo fechado
 * (navigate, open_bottom_sheet, track, noop) e o cliente decide a implementacao. As props de uma
 * section referenciam a action por id, o que ActionGuard valida na publicacao.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ActionResponse(
    val id: String,
    val type: String,
    val label: String? = null,
    val payload: ActionPayload? = null,
)
