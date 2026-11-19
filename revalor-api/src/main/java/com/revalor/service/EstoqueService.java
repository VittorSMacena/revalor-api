package com.revalor.service;

import com.revalor.dto.Dtos.*;
import com.revalor.model.Dominio.*;
import com.revalor.model.Lote;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;

@Service
public class EstoqueService {

    private static final BigDecimal CEM = BigDecimal.valueOf(100);
    private static final BigDecimal VALOR_POR_KG = new BigDecimal("4.00");
    private static final BigDecimal ENTRADA_SEMANA = BigDecimal.valueOf(2400);
    private static final BigDecimal PERDAS_SEMANA = BigDecimal.valueOf(360);
    private static final int[] PRODUCAO_SEMANA = {240, 300, 260, 340, 220, 320, 360};
    private static final List<String> CRITERIOS = List.of(
            "Identidade e integridade", "Histórico de temperatura",
            "Plano de análises aplicável", "Alergênicos e segregação");

    private final Clock clock;
    private final BarramentoEventos eventos;
    private final Map<String, Lote> lotes = new LinkedHashMap<>();
    private final Map<String, ProcessoUpcycling> processos = new LinkedHashMap<>();
    private final Map<String, OrdemUpcycling> ordens = new LinkedHashMap<>();
    private final AtomicInteger seqOrdem = new AtomicInteger(85);
    private final AtomicInteger seqLote = new AtomicInteger(3);

    public EstoqueService(Clock clock, BarramentoEventos eventos) {
        this.clock = clock;
        this.eventos = eventos;
        carregarDadosDemonstrativos();
    }

    // ---------- Consultas ----------

    public synchronized Dashboard dashboard() {
        BigDecimal disponivel = somar(Situacao.DISPONIVEL, Lote::disponivelKg);
        BigDecimal reservado = lotes.values().stream().map(Lote::reservadoKg)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal quarentena = somar(Situacao.QUARENTENA, Lote::saldoKg);
        BigDecimal total = lotes.values().stream().map(Lote::saldoKg).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal produzido = ENTRADA_SEMANA.subtract(PERDAS_SEMANA);
        BigDecimal rendimento = produzido.multiply(CEM).divide(ENTRADA_SEMANA, 1, RoundingMode.HALF_UP);

        List<ProducaoDia> semana = new ArrayList<>();
        for (int i = 0; i < PRODUCAO_SEMANA.length; i++) semana.add(new ProducaoDia(i + 1, PRODUCAO_SEMANA[i]));

        List<LoteResumo> prioridades = lotes.values().stream()
                .filter(l -> l.situacao() == Situacao.DISPONIVEL)
                .sorted(Comparator.comparing(Lote::limiteUso)) // FEFO
                .limit(3).map(this::resumo).toList();

        return new Dashboard(total, lotes.size(),
                disponivel, contar(Situacao.DISPONIVEL),
                reservado, (int) lotes.values().stream().filter(l -> l.reservadoKg().signum() > 0).count(),
                quarentena, contar(Situacao.QUARENTENA),
                rendimento, produzido.multiply(VALOR_POR_KG).setScale(2, RoundingMode.HALF_UP),
                produzido, ENTRADA_SEMANA, PERDAS_SEMANA, semana, prioridades,
                ordens.values().stream().filter(o -> o.status() == StatusOrdem.EM_PRODUCAO).toList());
    }

    public synchronized List<LoteResumo> listar(Situacao situacao, String busca) {
        String termo = busca == null ? "" : busca.trim().toLowerCase();
        return lotes.values().stream()
                .filter(l -> situacao == null || l.situacao() == situacao)
                .filter(l -> termo.isEmpty()
                        || l.materiaPrima().toLowerCase().contains(termo)
                        || l.codigo().toLowerCase().contains(termo))
                .sorted(Comparator.comparing(Lote::limiteUso))
                .map(this::resumo).toList();
    }

    public synchronized LoteDetalhe detalhe(String codigo) {
        return detalhe(buscar(codigo));
    }

    public List<ProcessoUpcycling> listarProcessos() {
        return List.copyOf(processos.values());
    }

    public synchronized List<OrdemUpcycling> listarOrdens() {
        return List.copyOf(ordens.values());
    }

    // ---------- Registrar excedente (entra sempre em quarentena) ----------

    public synchronized LoteDetalhe registrar(RegistroExcedenteRequest r) {
        if (!r.limiteUsoInformado().isAfter(r.coletadoEm())) {
            throw new RegraNegocioException("O limite de uso informado deve ser posterior à coleta.");
        }
        String codigo = gerarCodigo(r.materiaPrima(), r.coletadoEm());
        Lote lote = new Lote(codigo, r.materiaPrima(), r.categoria(), r.quantidadeLiquidaKg(),
                r.linhaOrigem(), r.setorOrigem(), r.loteProducaoOrigem(), null,
                r.localArmazenamento(), r.condicaoObservada() + " • " + r.temperaturaColeta() + " °C",
                r.limiteUsoInformado());
        lote.registrarEtapa(r.coletadoEm(), "Registro na origem",
                r.linhaOrigem() + " • " + r.motivo() + " • " + r.responsavel());
        lote.registrarEtapa(agora(), "Entrada em quarentena",
                r.quantidadeLiquidaKg().stripTrailingZeros().toPlainString() + " kg • " + r.localArmazenamento());
        lotes.put(codigo, lote);
        eventos.publicar("ESTOQUE_ATUALIZADO", codigo, agora());
        return detalhe(lote);
    }

    // ---------- Liberação (exclusiva da qualidade) ----------

    public synchronized LoteDetalhe liberar(String codigo, LiberacaoRequest r) {
        Lote lote = buscar(codigo);
        if (lote.qualidade() != Qualidade.QUARENTENA) {
            throw new RegraNegocioException("O lote já foi avaliado. Qualquer desvio exige nova avaliação.");
        }
        List<String> reprovados = r.itens().stream().filter(i -> !i.conforme())
                .map(ItemAvaliacao::criterio).toList();
        if (!reprovados.isEmpty()) {
            throw new RegraNegocioException("Lote permanece em quarentena. Não conforme: " + String.join(", ", reprovados));
        }
        if (!r.limiteUsoValidado().isAfter(agora())) {
            throw new RegraNegocioException("O limite de uso validado já expirou.");
        }
        validarProcesso(lote, processo(r.processoCodigo()));
        lote.liberar(r.responsavel(), r.laudo(), r.processoCodigo(), r.limiteUsoValidado(), r.itens(), agora());
        eventos.publicar("ESTOQUE_ATUALIZADO", codigo, agora());
        return detalhe(lote);
    }

    // ---------- Destinar para upcycling ----------

    public synchronized SimulacaoDestino simular(String codigo, String processoCodigo, BigDecimal quantidadeKg) {
        Lote lote = buscar(codigo);
        ProcessoUpcycling p = processo(processoCodigo);
        validarProcesso(lote, p);
        return new SimulacaoDestino(p.codigo(), quantidadeKg, previsto(quantidadeKg, p),
                p.rendimento().multiply(CEM).setScale(0, RoundingMode.HALF_UP),
                "Estimativa da ficha do processo, não produção realizada.");
    }

    public synchronized OrdemUpcycling destinar(String codigo, DestinoRequest r) {
        Lote lote = buscar(codigo);
        if (lote.qualidade() != Qualidade.LIBERADO) {
            throw new RegraNegocioException("Lote em quarentena: uso bloqueado até a liberação pela qualidade.");
        }
        ProcessoUpcycling p = processo(r.processoCodigo());
        validarProcesso(lote, p);
        if (r.quantidadeKg().compareTo(lote.disponivelKg()) > 0) {
            throw new RegraNegocioException("Quantidade acima do saldo disponível (" + lote.disponivelKg() + " kg).");
        }
        if (r.inicioPrevisto().isBefore(agora())) {
            throw new RegraNegocioException("O início previsto não pode estar no passado.");
        }
        if (!r.inicioPrevisto().isBefore(lote.limiteUso())) {
            throw new RegraNegocioException("O início deve ocorrer antes do limite de uso validado.");
        }
        String codigoOrdem = "OR-%03d".formatted(seqOrdem.getAndIncrement());
        OrdemUpcycling ordem = new OrdemUpcycling(codigoOrdem, lote.codigo(), p.codigo(), r.quantidadeKg(),
                r.inicioPrevisto(), previsto(r.quantidadeKg(), p), r.linhaDestino(), StatusOrdem.PLANEJADA, 0);
        ordens.put(codigoOrdem, ordem);
        lote.reservar(r.quantidadeKg(), codigoOrdem, agora());
        eventos.publicar("ESTOQUE_ATUALIZADO", codigo, agora());
        return ordem;
    }

    // ---------- Regras auxiliares ----------

    private void validarProcesso(Lote lote, ProcessoUpcycling p) {
        if (!p.materiaPrima().equalsIgnoreCase(lote.materiaPrima())) {
            throw new RegraNegocioException("O processo " + p.codigo() + " não é aprovado para " + lote.materiaPrima() + ".");
        }
        if (lote.processoLiberado() != null && !lote.processoLiberado().equals(p.codigo())) {
            throw new RegraNegocioException("Liberação restrita ao processo " + lote.processoLiberado() + ".");
        }
    }

    private BigDecimal previsto(BigDecimal kg, ProcessoUpcycling p) {
        return kg.multiply(p.rendimento()).setScale(1, RoundingMode.HALF_UP);
    }

    private ProcessoUpcycling processo(String codigo) {
        ProcessoUpcycling p = processos.get(codigo);
        if (p == null) throw new NaoEncontradoException("Processo não aprovado: " + codigo);
        return p;
    }

    /**
     * Acesso ao lote para o marketplace e a logística. Quem altera o lote deve fazê-lo dentro de
     * {@code synchronized (estoqueService)}, o mesmo monitor dos métodos deste serviço.
     */
    public Lote obterLote(String codigo) {
        return buscar(codigo);
    }

    private Lote buscar(String codigo) {
        Lote l = lotes.get(codigo);
        if (l == null) throw new NaoEncontradoException("Lote não encontrado: " + codigo);
        return l;
    }

    public LocalDateTime agora() {
        return LocalDateTime.now(clock);
    }

    private int contar(Situacao s) {
        return (int) lotes.values().stream().filter(l -> l.situacao() == s).count();
    }

    private BigDecimal somar(Situacao s, java.util.function.Function<Lote, BigDecimal> campo) {
        return lotes.values().stream().filter(l -> l.situacao() == s).map(campo)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String gerarCodigo(String materiaPrima, LocalDateTime data) {
        String[] p = materiaPrima.trim().toLowerCase().split("\\s+");
        String chave = (p.length > 2 && p[1].equals("de")) ? p[2] : p[0];
        String base = Normalizer.normalize(chave, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase();
        base = base.substring(0, Math.min(3, base.length()));
        return "%s-%s-%02d".formatted(base, DateTimeFormatter.ofPattern("ddMM").format(data), seqLote.getAndIncrement());
    }

    private LoteResumo resumo(Lote l) {
        return new LoteResumo(l.codigo(), l.materiaPrima(), l.saldoKg(), l.disponivelKg(), l.linhaOrigem(),
                l.setorOrigem(), l.localArmazenamento(), l.condicaoConservacao(), l.limiteUso(),
                Duration.between(agora(), l.limiteUso()).toHours(), l.situacao(), l.ordemReserva());
    }

    private LoteDetalhe detalhe(Lote l) {
        String alerta = switch (l.situacao()) {
            case QUARENTENA -> "Em quarentena: uso bloqueado até a liberação pela qualidade.";
            case DISPONIVEL -> "Concluir antes de " + DateTimeFormatter.ofPattern("HH:mm").format(l.limiteUso())
                    + ". Respeitar o limite validado e os parâmetros do processo. Desvios de conservação bloqueiam o uso.";
            case RESERVADO -> "Saldo totalmente reservado na ordem " + l.ordemReserva() + ".";
        };
        List<ProcessoUpcycling> aprovados = processos.values().stream()
                .filter(p -> p.materiaPrima().equalsIgnoreCase(l.materiaPrima())).toList();
        return new LoteDetalhe(resumo(l), l.categoria(), l.loteProducaoOrigem(), l.fornecedor(), l.laudo(),
                l.responsavelQualidade(), l.processoLiberado(), l.etapas(), l.avaliacao(), aprovados, alerta);
    }

    // ---------- Dados demonstrativos (espelham as telas) ----------

    private void carregarDadosDemonstrativos() {
        processo("UP-02", "Polpa de banana", "Banana madura", "Polpa", "0.90", "Despolpamento • Linha UP-01");
        processo("UP-03", "Base de molho", "Tomate fora de calibre", "Base de molho", "0.90", "Cozimento • Linha UP-03");
        processo("UP-04", "Purê de maçã", "Aparas de maçã", "Purê", "0.90", "Cozimento • Linha UP-04");
        processo("UP-05", "Farinha de aveia", "Farelo de aveia", "Farinha", "0.95", "Ingredientes secos • Linha UP-05");
        processo("UP-06", "Base vegetal", "Aparas de cenoura", "Base vegetal", "0.85", "Cozimento • Linha UP-03");
        processo("UP-07", "Polpa de manga", "Polpa de manga", "Polpa", "0.92", "Despolpamento • Linha UP-01");
        processo("UP-08", "Farinha de bagaço de malte", "Bagaço de malte", "Farinha", "0.85", "Ingredientes secos • Linha UP-05");

        lote("BAN-0710-01", "Banana madura", "Frutas e derivados", "320", "Linha 02", "Seleção de frutas",
                "PROD-0710-032", "Fazenda Boa Terra", "CF-01 • A-03", "0 a 4 °C", dia(7, 18, 0), "UP-02", null);
        lote("TOM-0610-02", "Tomate fora de calibre", "Frutas e derivados", "160", "Linha 04", "Classificação",
                "PROD-0610-021", null, "CF-01 • B-01", "0 a 4 °C", dia(8, 10, 0), "UP-03", null);
        lote("MAC-0610-01", "Aparas de maçã", "Frutas e derivados", "240", "Linha 03", "Corte de frutas",
                "PROD-0610-014", null, "CF-01 • A-05", "0 a 4 °C", dia(9, 10, 0), "UP-04", null);
        lote("AVE-0610-01", "Farelo de aveia", "Grãos e cereais", "260", "Linha 05", "Peneiramento",
                "PROD-0610-009", null, "SE-01 • C-02", "Seco • até 25 °C", dia(12, 12, 0), "UP-05", "OR-084");
        lote("CEN-0710-01", "Aparas de cenoura", "Vegetais", "180", "Linha 01", "Corte de vegetais",
                "PROD-0710-011", null, "CF-02 • Q-01", "0 a 4 °C", dia(8, 14, 0), null, null);
        lote("MAN-0710-02", "Polpa de manga", "Frutas e derivados", "120", "Linha 02", "Despolpamento",
                "PROD-0710-020", null, "CF-02 • Q-02", "0 a 4 °C", dia(8, 16, 0), null, null);

        ordens.put("OR-084", new OrdemUpcycling("OR-084", "AVE-0610-01", "UP-05", new BigDecimal("260"),
                dia(7, 8, 0), new BigDecimal("247.0"), "Ingredientes secos • Linha UP-05", StatusOrdem.EM_PRODUCAO, 65));
    }

    private void processo(String cod, String nome, String mp, String produto, String rend, String linha) {
        processos.put(cod, new ProcessoUpcycling(cod, nome, mp, produto, new BigDecimal(rend), linha));
    }

    private LocalDateTime dia(int d, int h, int m) {
        return LocalDateTime.of(2026, 10, d, h, m);
    }

    private void lote(String cod, String mp, String cat, String kg, String linha, String setor, String prod,
                      String fornecedor, String local, String cond, LocalDateTime limite, String proc, String ordem) {
        Lote l = new Lote(cod, mp, cat, new BigDecimal(kg), linha, setor, prod, fornecedor, local, cond, limite);
        LocalDateTime base = dia(7, 7, 45);
        l.registrarEtapa(base, "Segregação na origem", linha + " • " + setor);
        l.registrarEtapa(base.plusMinutes(25), "Pesagem e entrada em quarentena", kg + " kg • " + local);
        if (proc != null) {
            List<ItemAvaliacao> itens = CRITERIOS.stream().map(c -> new ItemAvaliacao(c, true, "Conforme")).toList();
            l.liberar("Marina Costa", "Q-" + cod.substring(4), proc, limite, itens, dia(7, 9, 20));
            l.registrarEtapa(dia(7, 9, 35), "Transferência para estoque liberado", local + " • conservação de " + cond);
        }
        if (ordem != null) l.definirReserva(new BigDecimal(kg), ordem);
        lotes.put(cod, l);
    }
}
