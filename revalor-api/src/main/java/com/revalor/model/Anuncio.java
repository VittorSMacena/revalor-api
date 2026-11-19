package com.revalor.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Oferta de um lote liberado no marketplace (lado do vendedor, Rafael). */
public class Anuncio {

    private final String loteCodigo;
    private final BigDecimal precoKg;
    private final BigDecimal quantidadeMinimaKg;
    private final String responsavel;
    private final LocalDateTime publicadoEm;
    private BigDecimal quantidadeKg;
    private boolean ativo = true;

    public Anuncio(String loteCodigo, BigDecimal precoKg, BigDecimal quantidadeKg,
                   BigDecimal quantidadeMinimaKg, String responsavel, LocalDateTime publicadoEm) {
        this.loteCodigo = loteCodigo;
        this.precoKg = precoKg;
        this.quantidadeKg = quantidadeKg;
        this.quantidadeMinimaKg = quantidadeMinimaKg;
        this.responsavel = responsavel;
        this.publicadoEm = publicadoEm;
    }

    /** Abate a quantidade vendida; a oferta se encerra quando acaba. */
    public void consumir(BigDecimal kg) {
        quantidadeKg = quantidadeKg.subtract(kg);
        if (quantidadeKg.signum() <= 0) ativo = false;
    }

    public void encerrar() { ativo = false; }

    public String loteCodigo() { return loteCodigo; }
    public BigDecimal precoKg() { return precoKg; }
    public BigDecimal quantidadeKg() { return quantidadeKg; }
    public BigDecimal quantidadeMinimaKg() { return quantidadeMinimaKg; }
    public String responsavel() { return responsavel; }
    public LocalDateTime publicadoEm() { return publicadoEm; }
    public boolean ativo() { return ativo; }
}
