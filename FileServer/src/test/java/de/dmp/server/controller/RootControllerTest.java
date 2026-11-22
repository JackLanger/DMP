package de.dmp.server.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(classes = RootControllerTest.TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class RootControllerTest {
    @Autowired private ApplicationContext context;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).build();
    }

    @Test
    void rootRedirectsToFilesWithoutAuthentication() {
        client.get().uri("/").exchange().expectStatus().isFound().expectHeader().location("/files/all");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(RootController.class)
    static class TestApplication {
    }
}
