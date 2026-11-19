package com.revalor;

import static org.junit.jupiter.api.Assertions.*;

import com.revalor.dto.Dtos.*;
import com.revalor.dto.DtosMercado.*;
import com.revalor.model.Dominio.*;
import com.revalor.service.BarramentoEventos;
import com.revalor.service.EstoqueService;
import com.revalor.service.LogisticaService;
import com.revalor.service.MercadoService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Testes de regra de negócio do marketplace e da logística, sem subir o Spring. */
class MercadoLogisticaTest {

    static final String LOTE = "BAN-0710-01"; // 320 kg, liberado, limite 07/10 18:00
    static final String BEATRIZ = "COMP-01";

    EstoqueService estoque;
    MercadoService mercado;
    LogisticaService logistica;
    BarramentoEventos barramento;
    List<BarramentoEventos.Evento> eventos;

    @BeforeEach
    void preparar() {
        ZoneId zona = ZoneId.of("America/Sao_Paulo");
        Clock clock = Clock.fixed(LocalDateTime.of(2026, 10, 7, 10, 0).atZone(zona).toInstant(), zona);
        barramento = new BarramentoEventos();
        eventos = new ArrayList<>();
        barramento.inscrever(eventos::add);
        estoque = new EstoqueService(clock, barramento);
        logistica = new LogisticaService(estoque, barramento);
        mercado = new MercadoService(estoque, logistica, barramento);
    }

    static BigDecimal bd(String v) { return new BigDecimal(v); }

    OfertaMercado ofertar(String lote, String preco, String qtd, String minimo) {
        return mercado.publicar(lote, new PublicarOfertaRequest(bd(preco), bd(qtd),
                minimo == null ? null : bd(minimo), "Rafael Medeiros"));
    }

    NegociacaoDetalhe propor(String comprador, String lote, String qtd, String preco) {
        return mercado.abrirNegociacao(lote, new PropostaRequest(comprador, bd(qtd), bd(preco), "Quero fechar"));
    }

    // ---------- Oferta do lote ----------

    @Test
    void lote_em_quarentena_nao_pode_ser_ofertado() {
        var e = assertThrows(RegraNegocioException.class, () -> ofertar("CEN-0710-01", "2.00", "100", null));
        assertTrue(e.getMessage().contains("quarentena"));
    }

    @Test
    void lote_totalmente_reservado_nao_pode_ser_ofertado() {
        assertThrows(RegraNegocioException.class, () -> ofertar("AVE-0610-01", "2.00", "100", null));
    }

    @Test
    void oferta_aparece_na_vitrine_com_rastreabilidade_e_valida_quantidade() {
        assertTrue(mercado.ofertas(null).isEmpty());
        assertThrows(RegraNegocioException.class, () -> ofertar(LOTE, "3.50", "400", null));
        OfertaMercado o = ofertar(LOTE, "3.50", "300", "50");
        assertEquals(bd("300"), o.quantidadeOfertadaKg());
        assertFalse(o.rastreabilidade().isEmpty());
        assertFalse(o.avaliacao().isEmpty());
        assertEquals(1, mercado.ofertas("banana").size());
        assertEquals(0, mercado.ofertas("tomate").size());
        assertThrows(RegraNegocioException.class, () -> ofertar(LOTE, "3.50", "300", null), "oferta duplicada");
    }

    // ---------- Negociação ----------

    @Test
    void proposta_abaixo_do_preco_aguarda_o_vendedor_e_respeita_a_vez() {
        ofertar(LOTE, "4.00", "300", "50");
        NegociacaoDetalhe n = propor(BEATRIZ, LOTE, "200", "3.00");
        assertEquals(StatusNegociacao.ABERTA, n.status());
        assertEquals(Autor.VENDEDOR, n.vezDe());
        assertEquals(bd("600.00"), n.valores().totalBruto());
        assertEquals(bd("90.00"), n.valores().comissaoRevalor()); // 15%
        assertEquals(bd("510.00"), n.valores().liquidoVendedor());

        // comprador não pode agir fora da vez
        assertThrows(RegraNegocioException.class, () ->
                mercado.aceitar(n.codigo(), new DecisaoRequest(Autor.COMPRADOR, null)));
        assertThrows(RegraNegocioException.class, () ->
                mercado.contrapropor(n.codigo(), new ContrapropostaRequest(Autor.COMPRADOR, bd("200"), bd("3.20"), null)));
        // comprador não abre segunda negociação para o mesmo lote
        assertThrows(RegraNegocioException.class, () -> propor(BEATRIZ, LOTE, "100", "3.10"));
    }

    @Test
    void contraproposta_e_aceite_reservam_saldo_e_criam_entrega() {
        ofertar(LOTE, "4.00", "300", "50");
        NegociacaoDetalhe n = propor(BEATRIZ, LOTE, "200", "3.00");

        n = mercado.contrapropor(n.codigo(), new ContrapropostaRequest(Autor.VENDEDOR, bd("200"), bd("3.80"), "Melhor preço"));
        assertEquals(Autor.COMPRADOR, n.vezDe());
        n = mercado.contrapropor(n.codigo(), new ContrapropostaRequest(Autor.COMPRADOR, bd("250"), bd("3.50"), null));
        assertEquals(Autor.VENDEDOR, n.vezDe());
        n = mercado.aceitar(n.codigo(), new DecisaoRequest(Autor.VENDEDOR, "Fechado"));

        assertEquals(StatusNegociacao.ACEITA, n.status());
        assertNull(n.vezDe());
        assertEquals(bd("875.00"), n.valores().totalBruto());
        assertNotNull(n.entregaCodigo());

        LoteDetalhe lote = estoque.detalhe(LOTE);
        assertEquals(bd("70"), lote.resumo().disponivelKg());
        assertEquals(n.codigo(), lote.resumo().ordemReserva());
        assertTrue(lote.rastreabilidade().stream().anyMatch(e -> e.titulo().equals("Venda fechada no marketplace")));

        // a oferta restante cai para o saldo; abaixo do mínimo de 50 kg, o mínimo acompanha o saldo
        assertEquals(bd("50"), mercado.oferta(LOTE).quantidadeOfertadaKg());
        EntregaDetalhe ent = logistica.detalhe(n.entregaCodigo());
        assertEquals(StatusEntrega.AGUARDANDO_COLETA, ent.status());
        assertEquals(bd("250"), ent.quantidadeKg());
    }

    @Test
    void proposta_no_preco_anunciado_fecha_na_hora() {
        ofertar(LOTE, "4.00", "300", null);
        NegociacaoDetalhe n = propor(BEATRIZ, LOTE, "100", "4.00");
        assertEquals(StatusNegociacao.ACEITA, n.status());
        assertEquals(TipoRodada.ACEITE, n.rodadas().get(n.rodadas().size() - 1).tipo());
        assertEquals(bd("220"), estoque.detalhe(LOTE).resumo().disponivelKg());
    }

    @Test
    void contraproposta_do_vendedor_nao_pode_superar_o_preco_anunciado() {
        ofertar(LOTE, "4.00", "300", null);
        NegociacaoDetalhe n = propor(BEATRIZ, LOTE, "100", "3.00");
        assertThrows(RegraNegocioException.class, () ->
                mercado.contrapropor(n.codigo(), new ContrapropostaRequest(Autor.VENDEDOR, bd("100"), bd("4.50"), null)));
    }

    @Test
    void quantidade_fora_da_faixa_da_oferta_e_rejeitada() {
        ofertar(LOTE, "4.00", "300", "100");
        assertThrows(RegraNegocioException.class, () -> propor(BEATRIZ, LOTE, "301", "3.00"));
        assertThrows(RegraNegocioException.class, () -> propor(BEATRIZ, LOTE, "99", "3.00"));
        assertThrows(NaoEncontradoException.class, () -> propor("COMP-99", LOTE, "100", "3.00"));
    }

    @Test
    void recusa_e_cancelamento_encerram_sem_reservar() {
        ofertar(LOTE, "4.00", "300", null);
        NegociacaoDetalhe n = propor(BEATRIZ, LOTE, "100", "3.00");
        n = mercado.recusar(n.codigo(), new DecisaoRequest(Autor.VENDEDOR, "Abaixo do custo"));
        assertEquals(StatusNegociacao.RECUSADA, n.status());
        assertEquals(bd("320"), estoque.detalhe(LOTE).resumo().disponivelKg());

        final String cod = propor(BEATRIZ, LOTE, "100", "3.20").codigo();
        assertThrows(RegraNegocioException.class, () ->
                mercado.cancelar(cod, new DecisaoRequest(Autor.VENDEDOR, null)));
        NegociacaoDetalhe cancelada = mercado.cancelar(cod, new DecisaoRequest(Autor.COMPRADOR, "Desisti"));
        assertEquals(StatusNegociacao.CANCELADA, cancelada.status());
        assertThrows(RegraNegocioException.class, () -> mercado.aceitar(cod, new DecisaoRequest(Autor.VENDEDOR, null)));
    }

    @Test
    void saldo_disputado_por_dois_compradores_nao_e_vendido_duas_vezes() {
        ofertar(LOTE, "4.00", "300", null);
        NegociacaoDetalhe a = propor(BEATRIZ, LOTE, "200", "3.00");
        NegociacaoDetalhe b = propor("COMP-02", LOTE, "200", "3.10");
        mercado.aceitar(a.codigo(), new DecisaoRequest(Autor.VENDEDOR, null));
        var e = assertThrows(RegraNegocioException.class, () ->
                mercado.aceitar(b.codigo(), new DecisaoRequest(Autor.VENDEDOR, null)));
        assertTrue(e.getMessage().contains("Saldo insuficiente"));
        assertEquals(bd("120"), estoque.detalhe(LOTE).resumo().disponivelKg());
    }

    @Test
    void retirar_a_oferta_cancela_negociacoes_abertas() {
        ofertar(LOTE, "4.00", "300", null);
        NegociacaoDetalhe n = propor(BEATRIZ, LOTE, "100", "3.00");
        mercado.retirar(LOTE);
        assertEquals(StatusNegociacao.CANCELADA, mercado.detalheNegociacao(n.codigo()).status());
        assertTrue(mercado.ofertas(null).isEmpty());
        assertThrows(NaoEncontradoException.class, () -> mercado.oferta(LOTE));
    }

    @Test
    void oferta_esgotada_sai_da_vitrine() {
        ofertar(LOTE, "4.00", "320", null);
        propor(BEATRIZ, LOTE, "320", "4.00");
        assertTrue(mercado.ofertas(null).isEmpty());
        assertEquals(Situacao.RESERVADO, estoque.detalhe(LOTE).resumo().situacao());
    }

    @Test
    void fluxo_de_registro_a_venda_do_bagaco_de_malte() {
        // Rafael registra, a qualidade libera, Rafael oferta, Beatriz compra.
        LoteDetalhe novo = estoque.registrar(new RegistroExcedenteRequest("Bagaço de malte", "Grãos e cereais",
                "Brassagem", "Filtração", "BRASS-0710-01", bd("600"), "Subproduto da brassagem",
                "Úmido, resfriado", bd("4"), "Big bag forrado", "CF-01 • B-02",
                LocalDateTime.of(2026, 10, 7, 8, 30), LocalDateTime.of(2026, 10, 7, 14, 30), "Rafael Medeiros"));
        String cod = novo.resumo().codigo();
        assertEquals(Situacao.QUARENTENA, novo.resumo().situacao());
        assertThrows(RegraNegocioException.class, () -> ofertar(cod, "2.00", "600", null));

        List<ItemAvaliacao> itens = List.of(new ItemAvaliacao("Identidade e integridade", true, "Conforme"),
                new ItemAvaliacao("Histórico de temperatura", true, "Conforme"));
        estoque.liberar(cod, new LiberacaoRequest("Marina Costa", "Q-MALTE-01", "UP-08",
                LocalDateTime.of(2026, 10, 7, 14, 30), itens));
        ofertar(cod, "2.50", "600", "100");
        NegociacaoDetalhe n = propor(BEATRIZ, cod, "400", "2.50");
        assertEquals(StatusNegociacao.ACEITA, n.status());
        assertEquals(bd("1000.00"), n.valores().totalBruto());
    }

    // ---------- Logística em tempo real ----------

    @Test
    void entrega_percorre_o_trajeto_ate_a_confirmacao() {
        ofertar(LOTE, "4.00", "300", null);
        String ent = propor(BEATRIZ, LOTE, "100", "4.00").entregaCodigo();

        EntregaDetalhe e = logistica.detalhe(ent);
        assertTrue(e.distanciaTotalKm().doubleValue() > 80 && e.distanciaTotalKm().doubleValue() < 110,
                "Campinas → São Paulo em linha reta: " + e.distanciaTotalKm());
        assertEquals(bd("4"), e.temperaturaMaxC());

        assertThrows(RegraNegocioException.class, () ->
                logistica.telemetria(ent, new TelemetriaRequest(-23.0, -47.0, bd("3"), null)), "sem coleta");
        assertThrows(RegraNegocioException.class, () -> logistica.confirmar(ent), "sem chegar");

        int passos = 0;
        int ultimoProgresso = -1;
        while (logistica.detalhe(ent).status() != StatusEntrega.ENTREGUE && passos < 40) {
            e = logistica.avancar(ent);
            assertTrue(e.progresso() >= ultimoProgresso, "progresso não pode regredir");
            ultimoProgresso = e.progresso();
            passos++;
        }
        e = logistica.detalhe(ent);
        assertEquals(StatusEntrega.ENTREGUE, e.status());
        assertEquals(100, e.progresso());
        assertEquals(0, e.distanciaRestanteKm().compareTo(BigDecimal.ZERO));
        assertTrue(passos >= 5 && passos <= 12, "passos: " + passos);
        assertFalse(e.riscoValidade());
        assertTrue(e.eventos().stream().noneMatch(ev -> ev.alerta()), "temperatura estável na simulação");
        assertThrows(RegraNegocioException.class, () -> logistica.avancar(ent));

        List<String> etapas = estoque.detalhe(LOTE).rastreabilidade().stream().map(x -> x.titulo()).toList();
        assertTrue(etapas.contains("Saída para entrega"));
        assertTrue(etapas.contains("Entregue ao comprador"));
    }

    @Test
    void telemetria_acima_do_limite_gera_alerta_e_normaliza_depois() {
        ofertar(LOTE, "4.00", "300", null);
        String ent = propor(BEATRIZ, LOTE, "100", "4.00").entregaCodigo();
        logistica.avancar(ent); // coleta
        logistica.avancar(ent); // 1º deslocamento

        EntregaDetalhe e = logistica.detalhe(ent);
        e = logistica.telemetria(ent, new TelemetriaRequest(e.posicao().latitude(), e.posicao().longitude(), bd("9.0"), null));
        assertTrue(e.alertaTemperatura());
        assertTrue(e.eventos().get(e.eventos().size() - 1).titulo().startsWith("ALERTA"));

        e = logistica.avancar(ent);
        assertFalse(e.alertaTemperatura());
        assertEquals("Temperatura normalizada", e.eventos().get(e.eventos().size() - 1).titulo());
    }

    @Test
    void eta_depois_do_limite_de_uso_marca_risco_de_validade() {
        // Tomate (limite 08/10 10:00) é seguro; para forçar risco, atrasamos o relógio da entrega via telemetria.
        ofertar(LOTE, "4.00", "300", null);
        String ent = propor(BEATRIZ, LOTE, "100", "4.00").entregaCodigo();
        logistica.coletar(ent, bd("3.0"));
        EntregaDetalhe e = logistica.telemetria(ent, new TelemetriaRequest(-22.95, -47.05, bd("3.0"),
                LocalDateTime.of(2026, 10, 7, 17, 45)));
        assertTrue(e.riscoValidade(), "chegada prevista depois de 18:00");
        assertTrue(e.eta().isAfter(e.limiteUso()));
    }

    @Test
    void confirmacao_manual_exige_estar_chegando() {
        ofertar(LOTE, "4.00", "300", null);
        String ent = propor(BEATRIZ, LOTE, "100", "4.00").entregaCodigo();
        logistica.coletar(ent, null);
        EntregaDetalhe e = logistica.telemetria(ent, new TelemetriaRequest(-23.50, -46.70, bd("3.0"), null));
        assertEquals(StatusEntrega.CHEGANDO, e.status());
        e = logistica.confirmar(ent);
        assertEquals(StatusEntrega.ENTREGUE, e.status());
    }

    @Test
    void simulacao_automatica_avanca_ate_concluir() {
        ofertar(LOTE, "4.00", "300", null);
        String ent = propor(BEATRIZ, LOTE, "100", "4.00").entregaCodigo();
        logistica.simulacao(ent, true);
        for (int i = 0; i < 40 && logistica.detalhe(ent).simulacaoAtiva(); i++) logistica.avancarSimulacoesAtivas();
        EntregaDetalhe e = logistica.detalhe(ent);
        assertEquals(StatusEntrega.ENTREGUE, e.status());
        assertFalse(e.simulacaoAtiva());
    }

    // ---------- Tempo real ----------

    @Test
    void mudancas_sao_publicadas_no_barramento_de_eventos() {
        ofertar(LOTE, "4.00", "300", null);
        NegociacaoDetalhe n = propor(BEATRIZ, LOTE, "100", "3.00");
        String entrega = mercado.aceitar(n.codigo(), new DecisaoRequest(Autor.VENDEDOR, null)).entregaCodigo();
        logistica.avancar(entrega);
        List<String> tipos = eventos.stream().map(BarramentoEventos.Evento::tipo).distinct().toList();
        assertTrue(tipos.containsAll(List.of("OFERTA_ATUALIZADA", "NEGOCIACAO_ATUALIZADA",
                "ENTREGA_ATUALIZADA", "ESTOQUE_ATUALIZADO")), tipos.toString());
    }
}
