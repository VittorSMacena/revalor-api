package com.revalor.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Gera telemetria automática para as entregas com simulação ativa (demonstração do Demoday). */
@Component
public class SimuladorLogistica {

    private final LogisticaService logistica;

    public SimuladorLogistica(LogisticaService logistica) {
        this.logistica = logistica;
    }

    @Scheduled(fixedDelayString = "${revalor.logistica.intervalo-ms:3000}")
    public void tick() {
        logistica.avancarSimulacoesAtivas();
    }
}
