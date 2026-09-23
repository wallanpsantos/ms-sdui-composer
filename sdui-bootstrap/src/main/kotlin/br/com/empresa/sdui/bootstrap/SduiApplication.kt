package br.com.empresa.sdui.bootstrap

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * O unico modulo executavel: sobe o servico e faz o component scan das demais camadas.
 *
 * As autoconfiguracoes de MongoDB e Redis sao excluidas de proposito. O modo padrao e em memoria
 * e nao deve tentar conectar em banco nenhum; quando `sdui.persistence.store=mongo` ou
 * `sdui.persistence.cache=redis`, os clientes sao montados pela configuracao do proprio servico,
 * com prazos, pool e retry declarados (ADR-021), e nao pelos defaults da autoconfiguracao. Ver
 * AGENTS.md secao 17 para o que o modo em memoria implica em producao.
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

fun main(args: Array<String>) {
    runApplication<SduiApplication>(*args)
}
