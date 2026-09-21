package br.com.empresa.sdui

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.ComponentScan

@SpringBootConfiguration
@EnableAutoConfiguration(
    excludeName = [
        "org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration",
        "org.springframework.boot.mongodb.autoconfigure.MongoReactiveAutoConfiguration",
        "org.springframework.boot.data.mongodb.autoconfigure.MongoDataAutoConfiguration",
        "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
        "org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration",
    ],
)
@ComponentScan("br.com.empresa.sdui")
class SduiAppTestConfiguration
