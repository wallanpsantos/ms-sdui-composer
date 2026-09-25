package br.com.empresa.sdui.bootstrap

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Ponto de entrada executavel e classe principal de inicializacao do microsservico Server-Driven UI.
 *
 * ### 1. O que faz
 * Ponto de entrada executavel anotado com `@SpringBootApplication` do microsservico `ms-sdui-composer`,
 * atuando como o unico ponto de bootstrap do servico que realiza o component scan das demais camadas
 * arquiteturais (`sdui-app`, `sdui-core`, `sdui-contract`).
 *
 * ### 2. Para que serve
 * Inicializa o contexto Spring Boot 4.1, ativa o runtime de alta escalabilidade I/O bound em Virtual
 * Threads do Java 25 (`spring.threads.virtual.enabled: true`) e orquestra a subida do servidor web
 * embutido para exposicao dos endpoints REST de composicao de telas (`/v1/surfaces`) e governanca
 * (`/admin/v1`).
 *
 * ### 3. Como funciona
 * A anotacao `@SpringBootApplication(scanBasePackages = ["br.com.empresa.sdui"])` realiza a varredura
 * unificada de componentes a partir do pacote raiz do servico. A propriedade `excludeName` exclui
 * explicitamente as autoconfiguracoes do Spring Boot para MongoDB e Redis
 * (`MongoAutoConfiguration`, `MongoReactiveAutoConfiguration`, `MongoDataAutoConfiguration`,
 * `DataRedisAutoConfiguration`, `DataRedisReactiveAutoConfiguration` e
 * `MongoHealthContributorAutoConfiguration`).
 * Essa exclusao intencional garante que no modo padrao em memoria (`sdui.persistence.store=memory` e
 * `sdui.persistence.cache=memory`) o servico nao tente conectar em bancos externos. Quando o modo
 * duravel opt-in e habilitado (`ADR-021`), os clientes de MongoDB e Redis sao instanciados,
 * configurados e gerenciados manualmente com prazos de timeout, pooling de conexoes e politicas
 * de resiliencia especificas declaradas em `DurablePersistenceConfiguration`, sem dependencia de
 * defaults genericos de autoconfiguracao.
 */
@SpringBootApplication(
    scanBasePackages = ["br.com.empresa.sdui"],
    excludeName = [
        "org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration",
        "org.springframework.boot.mongodb.autoconfigure.MongoReactiveAutoConfiguration",
        "org.springframework.boot.data.mongodb.autoconfigure.MongoDataAutoConfiguration",
        "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
        "org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration",
        "org.springframework.boot.health.autoconfigure.mongo.MongoHealthContributorAutoConfiguration",
    ],
)
class SduiApplication

/**
 * Funcao principal responsavel pela inicializacao do processo da aplicacao.
 *
 * ### 1. O que faz
 * Ponto de partida de execucao (`entrypoint`) da JVM para o microsservico `ms-sdui-composer`.
 *
 * ### 2. Para que serve
 * Permite que a JVM inicialize a aplicacao atraves do runtime Spring Boot a partir da linha de comando,
 * imagem de container ou IDE de desenvolvimento.
 *
 * ### 3. Como funciona
 * Invoca a funcao de extensao utilitaria [runApplication] do Spring Boot passando [SduiApplication]
 * como classe alvo e repassando os argumentos de linha de comando (`args`). Essa chamada instancia
 * o `ApplicationContext`, processa os argumentos de ambiente, carrega as configuracoes e sobe o
 * servidor HTTP com Virtual Threads habilitadas.
 *
 * @param args Argumentos de linha de comando fornecidos durante a inicializacao da JVM.
 */
fun main(args: Array<String>) {
    runApplication<SduiApplication>(*args)
}
