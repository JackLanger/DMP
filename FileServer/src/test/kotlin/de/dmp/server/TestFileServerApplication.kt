package de.dmp.server

import de.dmp.server.FileServer.TestcontainersConfiguration
import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
    fromApplication<FileServerApplication>().with(TestcontainersConfiguration::class).run(*args)
}
