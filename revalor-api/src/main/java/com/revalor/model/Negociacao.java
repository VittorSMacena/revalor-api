package com.revalor.model;

import com.revalor.model.Dominio.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Negociação de um lote entre comprador (Beatriz) e vendedor (Rafael).
 * As partes alternam propostas; só quem tem a vez pode aceitar, recusar ou contrapropor.
 */
public class Negociacao {

    private final String codigo;
    private final String loteCodigo;
    private final String materiaPrima;
    private final Comprador comprador;
    private final BigDecimal precoAnunciadoKg;
    private final LocalDateTime criadaEm;
    private final List<Rodada> rodadas = new ArrayList<>();

    private LocalDateTime atualizadaEm;
    private StatusNegociacao status = StatusNegociacao.ABERTA;
    private Autor vezDe = Autor.VENDEDOR;
    private String entregaCodigo;

    public Negociacao(String codigo, String loteCodigo, String materiaPrima, Comprador comprador,
                      BigDecimal precoAnunciadoKg, LocalDateTime criadaEm) {
        this.codigo = codigo;
        this.loteCodigo = loteCodigo;
        this.materiaPrima = materiaPrima;
        this.comprador = comprador;
        this.precoAnunciadoKg = precoAnunciadoKg;
        this.criadaEm = criadaEm;
        this.atualizadaEm = criadaEm;
    }

    /** Registra proposta (primeira rodada do comprador) ou contraproposta e passa a vez para o outro lado. */
    public void proporOuContrapropor(Autor autor, BigDecimal precoKg, BigDecimal quantidadeKg,
                                     String mensagem, LocalDateTime momento) {
        TipoRodada tipo = rodadas.isEmpty() ? TipoRodada.PROPOSTA : TipoRodada.CONTRAPROPOSTA;
        rodadas.add(new Rodada(rodadas.size() + 1, tipo, autor, precoKg, quantidadeKg, mensagem, momento));
        vezDe = autor.outro();
        atualizadaEm = momento;
    }

    public void aceitar(Autor autor, String mensagem, LocalDateTime momento, String entregaCodigo) {
        rodadas.add(new Rodada(rodadas.size() + 1, TipoRodada.ACEITE, autor, null, null, mensagem, momento));
        status = StatusNegociacao.ACEITA;
        this.entregaCodigo = entregaCodigo;
        atualizadaEm = momento;
    }

    public void recusar(Autor autor, String mensagem, LocalDateTime momento) {
        rodadas.add(new Rodada(rodadas.size() + 1, TipoRodada.RECUSA, autor, null, null, mensagem, momento));
        status = StatusNegociacao.RECUSADA;
        atualizadaEm = momento;
    }

    public void cancelar(Autor autor, String mensagem, LocalDateTime momento) {
        rodadas.add(new Rodada(rodadas.size() + 1, TipoRodada.CANCELAMENTO, autor, null, null, mensagem, momento));
        status = StatusNegociacao.CANCELADA;
        atualizadaEm = momento;
    }

    /** Última proposta ou contraproposta (é o que o outro lado pode aceitar). */
    public Rodada ultimaProposta() {
        for (int i = rodadas.size() - 1; i >= 0; i--) {
            TipoRodada t = rodadas.get(i).tipo();
            if (t == TipoRodada.PROPOSTA || t == TipoRodada.CONTRAPROPOSTA) return rodadas.get(i);
        }
        return null;
    }

    public long totalPropostas() {
        return rodadas.stream()
                .filter(r -> r.tipo() == TipoRodada.PROPOSTA || r.tipo() == TipoRodada.CONTRAPROPOSTA).count();
    }

    public boolean aberta() { return status == StatusNegociacao.ABERTA; }

    public String codigo() { return codigo; }
    public String loteCodigo() { return loteCodigo; }
    public String materiaPrima() { return materiaPrima; }
    public Comprador comprador() { return comprador; }
    public BigDecimal precoAnunciadoKg() { return precoAnunciadoKg; }
    public LocalDateTime criadaEm() { return criadaEm; }
    public LocalDateTime atualizadaEm() { return atualizadaEm; }
    public StatusNegociacao status() { return status; }
    public Autor vezDe() { return vezDe; }
    public String entregaCodigo() { return entregaCodigo; }
    public List<Rodada> rodadas() { return Collections.unmodifiableList(rodadas); }
}
