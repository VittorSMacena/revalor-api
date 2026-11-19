package com.revalor.model;

import com.revalor.model.Dominio.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Entrega de um lote vendido, da unidade geradora até o comprador.
 * Recebe telemetria (posição e temperatura) e mantém ETA e alertas atualizados.
 */
public class Entrega {

    /** Velocidade média usada para estimar a chegada. */
    public static final double VELOCIDADE_KMH = 55.0;
    /** A partir deste avanço da rota a entrega passa para "chegando". */
    public static final int PROGRESSO_CHEGANDO = 85;

    private final String codigo;
    private final String negociacaoCodigo;
    private final String loteCodigo;
    private final String materiaPrima;
    private final BigDecimal quantidadeKg;
    private final Comprador comprador;
    private final Ponto origem;
    private final Ponto destino;
    private final double distanciaKm;
    private final LocalDateTime limiteUso;
    private final BigDecimal temperaturaMaxC;
    private final List<EventoEntrega> eventos = new ArrayList<>();

    private StatusEntrega status = StatusEntrega.AGUARDANDO_COLETA;
    private double latitude;
    private double longitude;
    private BigDecimal temperaturaC;
    private int progresso;
    private LocalDateTime momentoAtual;
    private LocalDateTime eta;
    private boolean alertaTemperatura;
    private boolean simulacaoAtiva;
    private int passosSimulados;

    public Entrega(String codigo, String negociacaoCodigo, String loteCodigo, String materiaPrima,
                   BigDecimal quantidadeKg, Comprador comprador, Ponto origem, LocalDateTime limiteUso,
                   BigDecimal temperaturaMaxC, LocalDateTime criadaEm) {
        this.codigo = codigo;
        this.negociacaoCodigo = negociacaoCodigo;
        this.loteCodigo = loteCodigo;
        this.materiaPrima = materiaPrima;
        this.quantidadeKg = quantidadeKg;
        this.comprador = comprador;
        this.origem = origem;
        this.destino = new Ponto(comprador.empresa() + " • " + comprador.cidade(),
                comprador.latitude(), comprador.longitude());
        this.distanciaKm = distanciaKm(origem.latitude(), origem.longitude(), destino.latitude(), destino.longitude());
        this.limiteUso = limiteUso;
        this.temperaturaMaxC = temperaturaMaxC;
        this.latitude = origem.latitude();
        this.longitude = origem.longitude();
        this.momentoAtual = criadaEm;
        this.eta = criadaEm.plus(minutosPara(distanciaKm));
        eventos.add(new EventoEntrega(criadaEm, status, "Entrega criada",
                "Venda " + negociacaoCodigo + " • " + quantidadeKg.stripTrailingZeros().toPlainString() + " kg para "
                        + comprador.empresa(), latitude, longitude, null, false));
    }

    // ---------- Transições ----------

    /** Motorista confirma a coleta na origem. */
    public void coletar(LocalDateTime momento, BigDecimal temperatura) {
        if (status != StatusEntrega.AGUARDANDO_COLETA) {
            throw new RegraNegocioException("A coleta já foi registrada (situação: " + status + ").");
        }
        status = StatusEntrega.EM_TRANSITO;
        avancarRelogio(momento);
        temperaturaC = temperatura;
        alertaTemperatura = temperatura != null && temperatura.compareTo(temperaturaMaxC) > 0;
        recalcularEta();
        eventos.add(new EventoEntrega(momentoAtual, status, "Coletado na origem • saída para entrega",
                "Origem: " + origem.nome(), latitude, longitude, temperatura, alertaTemperatura));
    }

    /** Telemetria do veículo: posição e temperatura da carga. */
    public void registrarTelemetria(double lat, double lng, BigDecimal temperatura, LocalDateTime momento) {
        if (status == StatusEntrega.AGUARDANDO_COLETA) {
            throw new RegraNegocioException("Aguardando coleta: confirme a coleta antes de enviar telemetria.");
        }
        if (status == StatusEntrega.ENTREGUE) {
            throw new RegraNegocioException("Entrega já concluída.");
        }
        avancarRelogio(momento);
        latitude = lat;
        longitude = lng;
        boolean estavaEmAlerta = alertaTemperatura;
        temperaturaC = temperatura;
        alertaTemperatura = temperatura.compareTo(temperaturaMaxC) > 0;
        progresso = calcularProgresso();
        if (progresso >= PROGRESSO_CHEGANDO) status = StatusEntrega.CHEGANDO;
        recalcularEta();

        String titulo;
        if (alertaTemperatura) {
            titulo = "ALERTA: temperatura acima do limite (" + temperaturaMaxC.toPlainString() + " °C)";
        } else if (estavaEmAlerta) {
            titulo = "Temperatura normalizada";
        } else {
            titulo = status == StatusEntrega.CHEGANDO ? "Chegando ao destino" : "Em trânsito";
        }
        eventos.add(new EventoEntrega(momentoAtual, status, titulo,
                "%.1f km restantes • chegada prevista %02d:%02d".formatted(distanciaRestanteKm(),
                        eta.getHour(), eta.getMinute()),
                lat, lng, temperatura, alertaTemperatura));
    }

    /** Recebimento confirmado no destino. */
    public void confirmarEntrega(LocalDateTime momento) {
        if (status == StatusEntrega.ENTREGUE) {
            throw new RegraNegocioException("Entrega já concluída.");
        }
        if (status != StatusEntrega.CHEGANDO) {
            throw new RegraNegocioException("A entrega só pode ser confirmada quando o veículo estiver chegando ao destino.");
        }
        avancarRelogio(momento);
        status = StatusEntrega.ENTREGUE;
        latitude = destino.latitude();
        longitude = destino.longitude();
        progresso = 100;
        eta = momentoAtual;
        eventos.add(new EventoEntrega(momentoAtual, status, "Entrega confirmada",
                "Recebido por " + comprador.nome() + " • " + comprador.empresa(),
                latitude, longitude, temperaturaC, false));
    }

    public void definirSimulacao(boolean ativa) {
        this.simulacaoAtiva = ativa && status != StatusEntrega.ENTREGUE;
    }

    public int proximoPassoSimulado() { return ++passosSimulados; }

    // ---------- Cálculos ----------

    public double distanciaRestanteKm() {
        return status == StatusEntrega.ENTREGUE ? 0
                : distanciaKm(latitude, longitude, destino.latitude(), destino.longitude());
    }

    /** Há risco quando a chegada prevista ultrapassa o limite de uso validado do lote. */
    public boolean riscoValidade() {
        return status != StatusEntrega.ENTREGUE && eta.isAfter(limiteUso);
    }

    private int calcularProgresso() {
        if (distanciaKm <= 0) return 100;
        double p = 1.0 - distanciaRestanteKm() / distanciaKm;
        return (int) Math.max(0, Math.min(100, Math.round(p * 100)));
    }

    private void recalcularEta() {
        eta = momentoAtual.plus(minutosPara(distanciaRestanteKm()));
    }

    private void avancarRelogio(LocalDateTime momento) {
        if (momento != null && momento.isAfter(momentoAtual)) momentoAtual = momento;
    }

    public static Duration minutosPara(double km) {
        return Duration.ofMinutes(Math.round(km / VELOCIDADE_KMH * 60.0));
    }

    /** Distância em linha reta (haversine). */
    public static double distanciaKm(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public static BigDecimal arredondar(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP);
    }

    public String codigo() { return codigo; }
    public String negociacaoCodigo() { return negociacaoCodigo; }
    public String loteCodigo() { return loteCodigo; }
    public String materiaPrima() { return materiaPrima; }
    public BigDecimal quantidadeKg() { return quantidadeKg; }
    public Comprador comprador() { return comprador; }
    public Ponto origem() { return origem; }
    public Ponto destino() { return destino; }
    public double distanciaKm() { return distanciaKm; }
    public LocalDateTime limiteUso() { return limiteUso; }
    public BigDecimal temperaturaMaxC() { return temperaturaMaxC; }
    public StatusEntrega status() { return status; }
    public double latitude() { return latitude; }
    public double longitude() { return longitude; }
    public BigDecimal temperaturaC() { return temperaturaC; }
    public int progresso() { return progresso; }
    public LocalDateTime momentoAtual() { return momentoAtual; }
    public LocalDateTime eta() { return eta; }
    public boolean alertaTemperatura() { return alertaTemperatura; }
    public boolean simulacaoAtiva() { return simulacaoAtiva; }
    public List<EventoEntrega> eventos() { return Collections.unmodifiableList(eventos); }
}
