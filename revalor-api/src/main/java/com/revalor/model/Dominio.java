package com.revalor.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Tipos de domínio do Revalor. */
public final class Dominio {

    private Dominio() {}

    /** Decisão da qualidade. Só a qualidade libera um lote. */
    public enum Qualidade { QUARENTENA, LIBERADO }

    /** Situação exibida no estoque (abas Disponíveis / Reservados / Quarentena). */
    public enum Situacao { DISPONIVEL, RESERVADO, QUARENTENA }

    public enum StatusOrdem { PLANEJADA, EM_PRODUCAO, CONCLUIDA }

    public record EtapaRastreio(LocalDateTime momento, String titulo, String detalhe) {}

    public record ItemAvaliacao(String criterio, boolean conforme, String observacao) {}

    public record ProcessoUpcycling(String codigo, String nome, String materiaPrima,
                                    String produtoFinal, BigDecimal rendimento, String linhaDestino) {}

    public record OrdemUpcycling(String codigo, String loteCodigo, String processoCodigo,
                                 BigDecimal quantidadeKg, LocalDateTime inicioPrevisto,
                                 BigDecimal resultadoPrevistoKg, String linhaDestino,
                                 StatusOrdem status, int progresso) {}

    // ---------- Marketplace: oferta do lote e negociação ----------

    public enum StatusNegociacao { ABERTA, ACEITA, RECUSADA, CANCELADA }

    public enum Autor {
        COMPRADOR, VENDEDOR;
        public Autor outro() { return this == COMPRADOR ? VENDEDOR : COMPRADOR; }
    }

    public enum TipoRodada { PROPOSTA, CONTRAPROPOSTA, ACEITE, RECUSA, CANCELAMENTO }

    /** Um passo do histórico da negociação. Preço e quantidade só existem em proposta e contraproposta. */
    public record Rodada(int numero, TipoRodada tipo, Autor autor, BigDecimal precoKg, BigDecimal quantidadeKg,
                         String mensagem, LocalDateTime momento) {}

    public record Comprador(String codigo, String nome, String empresa, String cidade,
                            double latitude, double longitude) {}

    // ---------- Logística ----------

    public enum StatusEntrega { AGUARDANDO_COLETA, EM_TRANSITO, CHEGANDO, ENTREGUE }

    public record Ponto(String nome, double latitude, double longitude) {}

    public record EventoEntrega(LocalDateTime momento, StatusEntrega status, String titulo, String detalhe,
                                double latitude, double longitude, BigDecimal temperaturaC, boolean alerta) {}

    public static class RegraNegocioException extends RuntimeException {
        public RegraNegocioException(String msg) { super(msg); }
    }

    public static class NaoEncontradoException extends RuntimeException {
        public NaoEncontradoException(String msg) { super(msg); }
    }
}
