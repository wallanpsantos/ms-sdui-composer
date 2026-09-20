package br.com.empresa.sdui.bootstrap

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * O unico modulo executavel: sobe o servico e faz o component scan das demais camadas.
 *
 * As autoconfiguracoes de MongoDB e Redis sao excluidas de proposito. Os starters estao no
 * classpath como preparacao, mas nenhum adapter persistente e cabeado; sem a exclusao o Boot
 * tentaria conectar em bancos que o servico nao usa e o health ficaria DOWN. Ver AGENTS.md
 * secao 17 para o que essa ausencia de persistencia implica em producao.
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
