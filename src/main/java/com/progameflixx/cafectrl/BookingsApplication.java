package com.progameflixx.cafectrl;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

@SpringBootApplication
public class BookingsApplication {
	@PostConstruct
	public void init() {
		// Force the entire Java server to run in Indian Standard Time
		TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
	}

	public static void main(String[] args) {
		SpringApplication.run(BookingsApplication.class, args);
	}

}
