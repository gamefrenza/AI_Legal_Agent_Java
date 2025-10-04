package com.legalai.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

@SpringBootApplication
@EnableJpaRepositories(basePackages = "com.legalai.agent.repository")
@EnableMethodSecurity(prePostEnabled = true, securedEnabled = true)
public class LegalAiAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(LegalAiAgentApplication.class, args);
    }
}

