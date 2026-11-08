package de.dmp.portal

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class PortalApp

fun main(args: Array<String>) {
    runApplication<PortalApp>(*args)
}