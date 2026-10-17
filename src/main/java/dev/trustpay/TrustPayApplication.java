package dev.trustpay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TrustPayApplication {
  public static void main(String[] args) {
    SpringApplication.run(TrustPayApplication.class, args);
  }
}
