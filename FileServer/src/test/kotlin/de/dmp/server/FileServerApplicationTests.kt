package de.dmp.server

import de.dmp.server.controller.FileController
import de.dmp.server.service.MetadataService
import org.junit.jupiter.api.Test
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.springframework.test.context.bean.override.mockito.MockitoBean

@SpringJUnitConfig(FileController::class)
class FileControllerContextTests {

    @MockitoBean
    lateinit var metadataService: MetadataService

    @Test
    fun contextLoads() {
    }

}
