package br.com.empresa.sdui.bootstrap

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

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
