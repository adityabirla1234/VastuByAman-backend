package com.vastu;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

@SpringBootApplication
@EnableRetry
public class VastuByAmanRestFulApplication {

	public static void main(String[] args) {
		SpringApplication.run(VastuByAmanRestFulApplication.class, args);
	}

}
