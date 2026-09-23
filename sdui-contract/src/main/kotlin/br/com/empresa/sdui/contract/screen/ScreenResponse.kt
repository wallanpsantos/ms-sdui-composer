package br.com.empresa.sdui.contract.screen

/**
 * Corpo de GET /v1/surfaces/home: a arvore de UI pronta para o cliente montar.
 *
 * Raiz do contrato JSON com iOS e Android. A forma canonica esta fixada em
 * fixtures/contrato-sdui-home-definitivo.json e guardada pelos testes de sdui-contract;
 * qualquer mudanca de campo aqui e mudanca de contrato e exige acordo com as equipes moveis.
 *
 * Sao tres partes: [envelope] com os metadados da composicao, [skeleton] com os slots que o
 * cliente vai preencher e [sections] com o conteudo de cada um.
 */
data class ScreenResponse(
    val envelope: ScreenEnvelope,
    val skeleton: SkeletonResponse,
    val sections: List<SectionResponse>,
)
