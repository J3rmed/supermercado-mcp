package com.udea.supermercado.mcp;

import org.springframework.boot.SpringApplication;

public class TestSupermercadoMcpApplication {

	public static void main(String[] args) {
		SpringApplication.from(SupermercadoMcpApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
