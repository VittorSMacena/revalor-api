package com.revalor.dto;

import com.revalor.model.Dominio.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class Dtos {

    private Dtos() {}

    // ---------- Entrada ----------

    /** Tela "Registrar matéria-prima excedente". */
    public record RegistroExcedenteRequest(
            @NotBlank String materiaPrima,
            @NotBlank String categoria,
            @NotBlank String linhaOrigem,
            String setorOrigem,
            @NotBlank String loteProducaoOrigem,
            @NotNull @Positive BigDecimal quantidadeLiquidaKg,
            @NotBlank String motivo,
            @NotBlank String condicaoObservada,
            @NotNull BigDecimal temperaturaColeta,
            @NotBlank String acondicionamento,
            @NotBlank String localArmazenamento,
            @NotNull LocalDateTime coletadoEm,
            @NotNull LocalDateTime limiteUsoInformado,
            @NotBlank String responsavel) {}

    /** Avaliação feita pela qualidade. */
    public record LiberacaoRequest(
            @NotBlank String responsavel,
            @NotBlank String laudo,
            @NotBlank String processoCodigo,
            @NotNull LocalDateTime limiteUsoValidado,
            @NotEmpty List<ItemAvaliacao> itens) {}

    /** Painel "Destinar para upcycling". */
    public record DestinoRequest(
            @NotBlank String processoCodigo,
            @NotNull @Positive BigDecimal quantidadeKg,
            @NotNull LocalDateTime inicioPrevisto,
            @NotBlank String linhaDestino) {}

    // ---------- Saída ----------

    public record LoteResumo(String codigo, String materiaPrima, BigDecimal saldoKg, BigDecimal disponivelKg,
                             String linhaOrigem, String setorOrigem, String localArmazenamento,
                             String condicaoConservacao, LocalDateTime limiteUso, long horasRestantes,
                             Situacao situacao, String ordemReserva) {}

    public record LoteDetalhe(LoteResumo resumo, String categoria, String loteProducaoOrigem, String fornecedor,
                              String laudo, String responsavelQualidade, String processoLiberado,
                              List<EtapaRastreio> rastreabilidade, List<ItemAvaliacao> avaliacao,
                              List<ProcessoUpcycling> processosAprovados, String alerta) {}

    public record SimulacaoDestino(String processoCodigo, BigDecimal quantidadeKg,
                                   BigDecimal resultadoPrevistoKg, BigDecimal rendimentoPercentual,
                                   String observacao) {}

    public record ProducaoDia(int dia, int kg) {}

    public record Dashboard(BigDecimal excedentesEmEstoqueKg, int totalLotes,
                            BigDecimal disponivelKg, int lotesDisponiveis,
                            BigDecimal reservadoKg, int lotesReservados,
                            BigDecimal quarentenaKg, int lotesQuarentena,
                            BigDecimal rendimentoPercentual, BigDecimal valorRecuperado,
                            BigDecimal producaoConcluidaKg, BigDecimal entradaKg, BigDecimal perdasKg,
                            List<ProducaoDia> producaoSemana,
                            List<LoteResumo> prioridadesValidade,
                            List<OrdemUpcycling> emProducao) {}
}
