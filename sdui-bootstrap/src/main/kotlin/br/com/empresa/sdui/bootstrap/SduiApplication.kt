package br.com.empresa.sdui.bootstrap

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class SduiApplication

fun main(args: Array<String>) {
    runApplication<SduiApplication>(*args)
}
