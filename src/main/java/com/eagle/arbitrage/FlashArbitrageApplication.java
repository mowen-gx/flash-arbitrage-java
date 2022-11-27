package com.eagle.arbitrage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class FlashArbitrageApplication {

    public static void main(String[] args) {
        System.out.println("0x758d9170672c9558ca3cc944511cacf74683bd93".length());
        SpringApplication.run(FlashArbitrageApplication.class, args);
    }

}
