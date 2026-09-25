package br.com.empresa.sdui.core.model

/**
 * Validador e garantidor de segurança para identificadores de revisão de especificações e skeletons.
 *
 * ### 1. O que faz
 * Inspeciona cadeias de caracteres destinadas a identificar revisões de tela, assegurando conformidade
 * sintática e ausência de identificadores sensíveis de usuário.
 *
 * ### 2. Para que serve
 * Garante que os identificadores de revisão possam ser seguramente interpolados em chaves de cache do Redis,
 * cabeçalhos HTTP ETag e filtros de banco de dados, protegendo contra vulnerabilidades de injeção de comandos
 * e violações regulatórias de privacidade.
 *
 * ### 3. Como funciona
 * Avalia o identificador contra uma expressão regular restritiva (`^[A-Za-z0-9][A-Za-z0-9._:#-]{0,127}$`) e
 * valida que a cadeia não contenha marcadores de identificação de usuário através de `RedisKeys.containsUserId`.
 */
object RevisionIds {
    private val FORMAT = Regex("^[A-Za-z0-9][A-Za-z0-9._:#-]{0,127}$")

    /**
     * Valida se uma string é um identificador de revisão aceito e seguro.
     *
     * ### 1. O que faz
     * Executa a checagem dupla de formato sintático e ausência de termos de usuário na string informada.
     *
     * ### 2. Para que serve
     * Impede que identificadores inválidos, caracteres de controle ou dados de usuário sejam aceitos na governança
     * ou entrem em chaves de cache compartilhadas.
     *
     * ### 3. Como funciona
     * Compara a string com o padrão [FORMAT] e verifica se `RedisKeys.containsUserId` retorna falso.
     *
     * @param value Identificador de revisão a ser validado.
     * @return `true` se for válido e seguro, `false` caso contrário.
     */
    fun isValid(value: String): Boolean = FORMAT.matches(value) && !RedisKeys.containsUserId(value)
}
