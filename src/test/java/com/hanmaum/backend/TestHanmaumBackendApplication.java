package com.hanmaum.backend;

import org.springframework.boot.SpringApplication;

public class TestHanmaumBackendApplication {

  public static void main(String[] args) {
    SpringApplication.from(HanmaumBackendApplication::main)
        .with(TestcontainersConfiguration.class)
        .run(args);
  }
}
