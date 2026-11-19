package com.revalor.dto;

import com.revalor.model.Dominio.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** DTOs do marketplace (lado do comprador) e da logística em tempo real. */
public final class DtosMercado {

    private DtosMercado() {}

    // ---------- Entrada ----------

    /** Vendedor oferta um lote liberado no marketplace. */
    public record PublicarOfertaRequest(
            @NotNull @Positive BigDecimal precoKg,
            @NotNull @Positive BigDecimal quantidadeKg,
            BigDecimal quantidadeMinimaKg,
            @NotBlank String responsavel) {}

    /** Comprador abre a negociação com uma proposta. */
    public record PropostaRequest(
            @NotBlank String compradorCodigo,
            @NotNull @Positive BigDecimal quantidadeKg,
            @NotNull @Positive BigDecimal precoKg,
            String mensagem) {}

    public record ContrapropostaRequest(
            @NotNull Autor autor,
            @NotNull @Positive BigDecimal quantidadeKg,
            @NotNull @Positive BigDecimal precoKg,
            String mensagem) {}

    /** Aceite, recusa ou cancelamento. */
    public record DecisaoRequest(@NotNull Autor autor, String mensagem) {}

    /** Telemetria do veículo (app do motorista ou rastreador). */
    public record TelemetriaRequest(
            @NotNull Double latitude,
            @NotNull Double longitude,
            @NotNull BigDecimal temperaturaC,
            LocalDateTime registradoEm) {}

    public record ColetaRequest(BigDecimal temperaturaC) {}

    public record SimulacaoRequest(boolean ativa) {}

    // ---------- Saída ----------

    public record OfertaMercado(String loteCodigo, String materiaPrima, String categoria, String vendedor,
                                String origem, BigDecimal precoKg, BigDecimal quantidadeOfertadaKg,
                                BigDecimal quantidadeMinimaKg, LocalDateTime limiteUso, long horasRestantes,
                                String condicaoConservacao, String laudo, String processoLiberado,
                                String responsavelQualidade, LocalDateTime publicadoEm,
                                List<EtapaRastreio> rastreabilidade, List<ItemAvaliacao> avaliacao) {}

    /** Valores da última proposta, já com a comissão da plataforma (take-rate). */
    public record ValorNegociado(BigDecimal totalBruto, BigDecimal percentualComissao,
                                 BigDecimal comissaoRevalor, BigDecimal liquidoVendedor) {}

    public record NegociacaoDetalhe(String codigo, String loteCodigo, String materiaPrima, Comprador comprador,
                                    StatusNegociacao status, Autor vezDe, BigDecimal precoAnunciadoKg,
                                    Rodada ultimaProposta, ValorNegociado valores, List<Rodada> rodadas,
                                    String entregaCodigo, LocalDateTime criadaEm, LocalDateTime atualizadaEm) {}

    public record EntregaDetalhe(String codigo, String negociacaoCodigo, String loteCodigo, String materiaPrima,
                                 BigDecimal quantidadeKg, Comprador comprador, Ponto origem, Ponto destino,
                                 Ponto posicao, StatusEntrega status, int progresso,
                                 BigDecimal distanciaTotalKm, BigDecimal distanciaRestanteKm,
                                 BigDecimal temperaturaC, BigDecimal temperaturaMaxC, boolean alertaTemperatura,
                                 LocalDateTime limiteUso, LocalDateTime eta, boolean riscoValidade,
                                 boolean simulacaoAtiva, LocalDateTime atualizadaEm, List<EventoEntrega> eventos) {}
}
