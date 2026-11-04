package de.dmp.server.configuration;


import org.springframework.context.annotation.Bean;

import java.nio.file.Path;


public class UploadConfigBean {

  @Bean
  public Path uploadDir() {

    return Path.of("uploads");
  }

}
