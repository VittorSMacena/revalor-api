package com.revalor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RevalorApplication {

    public static void main(String[] args) {
        SpringApplication.run(RevalorApplication.class, args);
    }

    /**
     * Relógio de demonstração, que parte de 07/10/2026 10:00 para reproduzir os dados das telas.
     * revalor.relogio.modo=fixo    : o tempo não anda (resultados idênticos a cada execução);
     * revalor.relogio.modo=corrente: começa em 07/10/2026 10:00 e segue em tempo real (histórico de negociação com horas distintas).
     * Em produção, troque por Clock.systemDefaultZone().
     */
    @Bean
    Clock clock(@Value("${revalor.relogio.modo:fixo}") String modo) {
        ZoneId zona = ZoneId.of("America/Sao_Paulo");
        Instant inicio = LocalDateTime.of(2026, 10, 7, 10, 0).atZone(zona).toInstant();
        if ("corrente".equalsIgnoreCase(modo)) {
            return Clock.offset(Clock.system(zona), Duration.between(Instant.now(), inicio));
        }
        return Clock.fixed(inicio, zona);
    }
}
