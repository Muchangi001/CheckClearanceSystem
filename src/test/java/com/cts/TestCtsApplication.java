package com.cts;

import org.springframework.boot.SpringApplication;

public class TestCtsApplication {

	public static void main(String[] args) {
		SpringApplication.from(CtsApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
