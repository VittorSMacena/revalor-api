package com.revalor.model;

import com.revalor.model.Dominio.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Lote de excedente rastreado da origem até a transformação. */
public class Lote {

    private final String codigo;
    private final String materiaPrima;
    private final String categoria;
    private final String linhaOrigem;
    private final String setorOrigem;
    private final String loteProducaoOrigem;
    private final String fornecedor;
    private final String localArmazenamento;
    private final String condicaoConservacao;

    private BigDecimal saldoKg;
    private BigDecimal reservadoKg = BigDecimal.ZERO;
    private LocalDateTime limiteUso;
    private Qualidade qualidade = Qualidade.QUARENTENA;
    private String ordemReserva;
    private String laudo;
    private String responsavelQualidade;
    private String processoLiberado;

    private final List<EtapaRastreio> etapas = new ArrayList<>();
    private final List<ItemAvaliacao> avaliacao = new ArrayList<>();

    public Lote(String codigo, String materiaPrima, String categoria, BigDecimal saldoKg,
                String linhaOrigem, String setorOrigem, String loteProducaoOrigem, String fornecedor,
                String localArmazenamento, String condicaoConservacao, LocalDateTime limiteUso) {
        this.codigo = codigo;
        this.materiaPrima = materiaPrima;
        this.categoria = categoria;
        this.saldoKg = saldoKg;
        this.linhaOrigem = linhaOrigem;
        this.setorOrigem = setorOrigem;
        this.loteProducaoOrigem = loteProducaoOrigem;
        this.fornecedor = fornecedor;
        this.localArmazenamento = localArmazenamento;
        this.condicaoConservacao = condicaoConservacao;
        this.limiteUso = limiteUso;
    }

    public BigDecimal disponivelKg() {
        return saldoKg.subtract(reservadoKg);
    }

    public Situacao situacao() {
        if (qualidade == Qualidade.QUARENTENA) return Situacao.QUARENTENA;
        return disponivelKg().signum() > 0 ? Situacao.DISPONIVEL : Situacao.RESERVADO;
    }

    public void registrarEtapa(LocalDateTime momento, String titulo, String detalhe) {
        etapas.add(new EtapaRastreio(momento, titulo, detalhe));
    }

    public void liberar(String responsavel, String laudo, String processo, LocalDateTime limiteValidado,
                        List<ItemAvaliacao> itens, LocalDateTime momento) {
        this.qualidade = Qualidade.LIBERADO;
        this.responsavelQualidade = responsavel;
        this.laudo = laudo;
        this.processoLiberado = processo;
        this.limiteUso = limiteValidado;
        this.avaliacao.clear();
        this.avaliacao.addAll(itens);
        registrarEtapa(momento, "Liberação pela qualidade",
                responsavel + " • laudo " + laudo + " • processo " + processo);
    }

    public void reservar(BigDecimal kg, String ordem, LocalDateTime momento) {
        this.reservadoKg = reservadoKg.add(kg);
        this.ordemReserva = ordem;
        registrarEtapa(momento, "Reserva para upcycling", kg.stripTrailingZeros().toPlainString() + " kg • " + ordem);
    }

    /** Reserva de saldo para uma venda fechada no marketplace. */
    public void reservarParaVenda(BigDecimal kg, String negociacao, String comprador, LocalDateTime momento) {
        this.reservadoKg = reservadoKg.add(kg);
        this.ordemReserva = negociacao;
        registrarEtapa(momento, "Venda fechada no marketplace",
                kg.stripTrailingZeros().toPlainString() + " kg • " + comprador + " • " + negociacao);
    }

    public String codigo() { return codigo; }
    public String materiaPrima() { return materiaPrima; }
    public String categoria() { return categoria; }
    public String linhaOrigem() { return linhaOrigem; }
    public String setorOrigem() { return setorOrigem; }
    public String loteProducaoOrigem() { return loteProducaoOrigem; }
    public String fornecedor() { return fornecedor; }
    public String localArmazenamento() { return localArmazenamento; }
    public String condicaoConservacao() { return condicaoConservacao; }
    public BigDecimal saldoKg() { return saldoKg; }
    public BigDecimal reservadoKg() { return reservadoKg; }
    public LocalDateTime limiteUso() { return limiteUso; }
    public Qualidade qualidade() { return qualidade; }
    public String ordemReserva() { return ordemReserva; }
    public String laudo() { return laudo; }
    public String responsavelQualidade() { return responsavelQualidade; }
    public String processoLiberado() { return processoLiberado; }
    public List<EtapaRastreio> etapas() { return Collections.unmodifiableList(etapas); }
    public List<ItemAvaliacao> avaliacao() { return Collections.unmodifiableList(avaliacao); }

    /** Usado apenas na carga inicial dos dados demonstrativos. */
    public void definirReserva(BigDecimal kg, String ordem) {
        this.reservadoKg = kg;
        this.ordemReserva = ordem;
    }
}
