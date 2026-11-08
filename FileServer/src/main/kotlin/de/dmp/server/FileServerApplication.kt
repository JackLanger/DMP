package de.dmp.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories

@SpringBootApplication
@EnableMongoRepositories
class FileServerApplication

fun main(args: Array<String>) {
    runApplication<FileServerApplication>(*args)
}