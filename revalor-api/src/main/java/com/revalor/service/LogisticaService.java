package com.revalor.service;

import com.revalor.dto.DtosMercado.*;
import com.revalor.model.Dominio.*;
import com.revalor.model.Entrega;
import com.revalor.model.Lote;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Sincronização logística em tempo real: acompanha cada venda fechada da coleta à entrega.
 * A telemetria (posição e temperatura) chega pela API; o simulador gera a mesma telemetria para a demonstração.
 */
@Service
public class LogisticaService {

    /** Unidade geradora (origem de todas as entregas na demonstração). */
    public static final Ponto ORIGEM = new Ponto("Unidade Campinas", -22.9056, -47.0608);
    /** Cada passo simulado equivale a este tempo de viagem. */
    public static final int PASSO_MINUTOS = 15;

    private static final BigDecimal TEMPERATURA_MAX_PADRAO = new BigDecimal("8");
    private static final Pattern LIMITE_FRIO = Pattern.compile("0\\s*a\\s*(\\d+)");
    private static final Pattern LIMITE_ATE = Pattern.compile("at[ée]\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    private final EstoqueService estoque;
    private final BarramentoEventos eventos;
    private final Map<String, Entrega> entregas = new LinkedHashMap<>();
    private final AtomicInteger seq = new AtomicInteger(1);

    public LogisticaService(EstoqueService estoque, BarramentoEventos eventos) {
        this.estoque = estoque;
        this.eventos = eventos;
    }

    // ---------- Criação (chamada pelo fechamento da negociação) ----------

    /** Deve ser chamado dentro de synchronized (estoque). */
    public Entrega criar(String negociacaoCodigo, Lote lote, BigDecimal quantidadeKg, Comprador comprador) {
        String codigo = "ENT-%03d".formatted(seq.getAndIncrement());
        Entrega e = new Entrega(codigo, negociacaoCodigo, lote.codigo(), lote.materiaPrima(), quantidadeKg,
                comprador, ORIGEM, lote.limiteUso(), limiteTemperatura(lote.condicaoConservacao()), estoque.agora());
        entregas.put(codigo, e);
        lote.registrarEtapa(estoque.agora(), "Entrega criada", codigo + " • destino " + comprador.empresa());
        eventos.publicar("ENTREGA_ATUALIZADA", codigo, estoque.agora());
        return e;
    }

    // ---------- Consultas ----------

    public List<EntregaDetalhe> listar(String compradorCodigo) {
        synchronized (estoque) {
            return entregas.values().stream()
                    .filter(e -> compradorCodigo == null || e.comprador().codigo().equals(compradorCodigo))
                    .map(this::detalhe).toList();
        }
    }

    public EntregaDetalhe detalhe(String codigo) {
        synchronized (estoque) {
            return detalhe(buscar(codigo));
        }
    }

    // ---------- Operações do motorista / rastreador ----------

    public EntregaDetalhe coletar(String codigo, BigDecimal temperaturaC) {
        synchronized (estoque) {
            Entrega e = buscar(codigo);
            BigDecimal temp = temperaturaC != null ? temperaturaC : temperaturaNormal(e, 0);
            e.coletar(estoque.agora().isAfter(e.momentoAtual()) ? estoque.agora() : e.momentoAtual(), temp);
            estoque.obterLote(e.loteCodigo()).registrarEtapa(e.momentoAtual(), "Saída para entrega",
                    e.codigo() + " • " + temp.toPlainString() + " °C");
            return publicar(e);
        }
    }

    public EntregaDetalhe telemetria(String codigo, TelemetriaRequest r) {
        synchronized (estoque) {
            Entrega e = buscar(codigo);
            e.registrarTelemetria(r.latitude(), r.longitude(), r.temperaturaC(), r.registradoEm());
            return publicar(e);
        }
    }

    public EntregaDetalhe confirmar(String codigo) {
        synchronized (estoque) {
            Entrega e = buscar(codigo);
            e.confirmarEntrega(null);
            estoque.obterLote(e.loteCodigo()).registrarEtapa(e.momentoAtual(), "Entregue ao comprador",
                    e.comprador().empresa() + " • " + e.codigo());
            return publicar(e);
        }
    }

    // ---------- Simulação (demonstração) ----------

    /** Avança um passo: coleta, desloca o veículo ao longo da rota ou confirma a chegada. */
    public EntregaDetalhe avancar(String codigo) {
        synchronized (estoque) {
            Entrega e = buscar(codigo);
            LocalDateTime proximo = e.momentoAtual().plusMinutes(PASSO_MINUTOS);
            int passo = e.proximoPassoSimulado();
            switch (e.status()) {
                case AGUARDANDO_COLETA -> {
                    e.coletar(proximo, temperaturaNormal(e, passo));
                    estoque.obterLote(e.loteCodigo()).registrarEtapa(e.momentoAtual(), "Saída para entrega",
                            e.codigo() + " • " + e.temperaturaC().toPlainString() + " °C");
                }
                case EM_TRANSITO, CHEGANDO -> {
                    if (e.status() == StatusEntrega.CHEGANDO && e.distanciaRestanteKm() < 0.05) {
                        e.confirmarEntrega(proximo);
                        estoque.obterLote(e.loteCodigo()).registrarEtapa(e.momentoAtual(), "Entregue ao comprador",
                                e.comprador().empresa() + " • " + e.codigo());
                        e.definirSimulacao(false);
                    } else {
                        double[] pos = proximaPosicao(e);
                        e.registrarTelemetria(pos[0], pos[1], temperaturaNormal(e, passo), proximo);
                    }
                }
                case ENTREGUE -> throw new RegraNegocioException("Entrega já concluída.");
            }
            return publicar(e);
        }
    }

    public EntregaDetalhe simulacao(String codigo, boolean ativa) {
        synchronized (estoque) {
            Entrega e = buscar(codigo);
            if (ativa && e.status() == StatusEntrega.ENTREGUE) {
                throw new RegraNegocioException("Entrega já concluída.");
            }
            e.definirSimulacao(ativa);
            return publicar(e);
        }
    }

    /** Chamado periodicamente pelo SimuladorLogistica. */
    public void avancarSimulacoesAtivas() {
        List<String> ativas;
        synchronized (estoque) {
            ativas = entregas.values().stream().filter(Entrega::simulacaoAtiva).map(Entrega::codigo).toList();
        }
        for (String codigo : ativas) {
            try {
                avancar(codigo);
            } catch (RegraNegocioException ex) {
                synchronized (estoque) { buscar(codigo).definirSimulacao(false); }
            }
        }
    }

    // ---------- Auxiliares ----------

    private double[] proximaPosicao(Entrega e) {
        double restante = e.distanciaRestanteKm();
        double passoKm = Entrega.VELOCIDADE_KMH * PASSO_MINUTOS / 60.0;
        if (restante <= passoKm) return new double[] {e.destino().latitude(), e.destino().longitude()};
        double f = passoKm / restante;
        return new double[] {
                e.latitude() + (e.destino().latitude() - e.latitude()) * f,
                e.longitude() + (e.destino().longitude() - e.longitude()) * f};
    }

    /** Temperatura estável, um pouco abaixo do limite, com pequena oscilação. */
    private BigDecimal temperaturaNormal(Entrega e, int passo) {
        double base = e.temperaturaMaxC().doubleValue() * 0.55;
        return BigDecimal.valueOf(base + 0.4 * Math.sin(passo)).setScale(1, RoundingMode.HALF_UP);
    }

    static BigDecimal limiteTemperatura(String condicao) {
        if (condicao != null) {
            Matcher m = LIMITE_FRIO.matcher(condicao);
            if (m.find()) return new BigDecimal(m.group(1));
            m = LIMITE_ATE.matcher(condicao);
            if (m.find()) return new BigDecimal(m.group(1));
        }
        return TEMPERATURA_MAX_PADRAO;
    }

    private EntregaDetalhe publicar(Entrega e) {
        eventos.publicar("ENTREGA_ATUALIZADA", e.codigo(), estoque.agora());
        return detalhe(e);
    }

    private Entrega buscar(String codigo) {
        Entrega e = entregas.get(codigo);
        if (e == null) throw new NaoEncontradoException("Entrega não encontrada: " + codigo);
        return e;
    }

    private EntregaDetalhe detalhe(Entrega e) {
        return new EntregaDetalhe(e.codigo(), e.negociacaoCodigo(), e.loteCodigo(), e.materiaPrima(),
                e.quantidadeKg(), e.comprador(), e.origem(), e.destino(),
                new Ponto("Veículo", e.latitude(), e.longitude()), e.status(), e.progresso(),
                Entrega.arredondar(e.distanciaKm()), Entrega.arredondar(e.distanciaRestanteKm()),
                e.temperaturaC(), e.temperaturaMaxC(), e.alertaTemperatura(), e.limiteUso(), e.eta(),
                e.riscoValidade(), e.simulacaoAtiva(), e.momentoAtual(), e.eventos());
    }
}
