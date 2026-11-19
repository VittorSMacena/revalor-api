package com.revalor.service;

import com.revalor.dto.DtosMercado.*;
import com.revalor.model.Anuncio;
import com.revalor.model.Dominio.*;
import com.revalor.model.Entrega;
import com.revalor.model.Lote;
import com.revalor.model.Negociacao;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;

/**
 * Marketplace B2B: o vendedor (Rafael) oferta lotes liberados; o comprador (Beatriz) encontra a oferta,
 * confere a rastreabilidade, propõe preço e quantidade e negocia até o aceite. O aceite reserva o saldo
 * do lote e cria a entrega acompanhada em tempo real.
 */
@Service
public class MercadoService {

    /** Comissão da plataforma sobre a venda (faixa de 10% a 20% no modelo de monetização; usamos o ponto médio). */
    public static final BigDecimal TAKE_RATE = new BigDecimal("0.15");
    /** Limite de propostas e contrapropostas por negociação. */
    public static final int MAX_PROPOSTAS = 8;
    private static final String VENDEDOR = "Rafael Medeiros • Unidade Campinas";

    private final EstoqueService estoque;
    private final LogisticaService logistica;
    private final BarramentoEventos eventos;
    private final Map<String, Anuncio> anuncios = new LinkedHashMap<>();
    private final Map<String, Negociacao> negociacoes = new LinkedHashMap<>();
    private final Map<String, Comprador> compradores = new LinkedHashMap<>();
    private final AtomicInteger seqNegociacao = new AtomicInteger(1);

    public MercadoService(EstoqueService estoque, LogisticaService logistica, BarramentoEventos eventos) {
        this.estoque = estoque;
        this.logistica = logistica;
        this.eventos = eventos;
        comprador(new Comprador("COMP-01", "Beatriz Albuquerque", "Foodtech de ingredientes (P&D)",
                "São Paulo/SP", -23.5505, -46.6333));
        comprador(new Comprador("COMP-02", "Comprador convidado", "Formulador regional",
                "Jundiaí/SP", -23.1857, -46.8978));
    }

    private void comprador(Comprador c) {
        compradores.put(c.codigo(), c);
    }

    // ---------- Compradores ----------

    public List<Comprador> listarCompradores() {
        return List.copyOf(compradores.values());
    }

    // ---------- Oferta do lote (vendedor) ----------

    public OfertaMercado publicar(String loteCodigo, PublicarOfertaRequest r) {
        synchronized (estoque) {
            Lote lote = estoque.obterLote(loteCodigo);
            if (lote.qualidade() != Qualidade.LIBERADO) {
                throw new RegraNegocioException("Lote em quarentena: só lotes liberados pela qualidade podem ser ofertados.");
            }
            exigirDentroDoLimite(lote);
            Anuncio atual = anuncios.get(loteCodigo);
            if (atual != null && atual.ativo()) {
                throw new RegraNegocioException("Este lote já está ofertado no marketplace.");
            }
            if (r.quantidadeKg().compareTo(lote.disponivelKg()) > 0) {
                throw new RegraNegocioException("Quantidade acima do saldo disponível (" + lote.disponivelKg() + " kg).");
            }
            BigDecimal minimo = r.quantidadeMinimaKg() == null ? BigDecimal.ZERO : r.quantidadeMinimaKg();
            if (minimo.signum() < 0 || minimo.compareTo(r.quantidadeKg()) > 0) {
                throw new RegraNegocioException("A quantidade mínima deve estar entre 0 e a quantidade ofertada.");
            }
            Anuncio a = new Anuncio(loteCodigo, r.precoKg(), r.quantidadeKg(), minimo, r.responsavel(), estoque.agora());
            anuncios.put(loteCodigo, a);
            lote.registrarEtapa(estoque.agora(), "Ofertado no marketplace",
                    r.quantidadeKg().stripTrailingZeros().toPlainString() + " kg • R$ "
                            + r.precoKg().setScale(2, RoundingMode.HALF_UP) + "/kg • " + r.responsavel());
            eventos.publicar("OFERTA_ATUALIZADA", loteCodigo, estoque.agora());
            eventos.publicar("ESTOQUE_ATUALIZADO", loteCodigo, estoque.agora());
            return oferta(lote, a);
        }
    }

    /** Retira a oferta; negociações abertas desse lote são canceladas. */
    public void retirar(String loteCodigo) {
        synchronized (estoque) {
            Anuncio a = anuncios.get(loteCodigo);
            if (a == null || !a.ativo()) throw new NaoEncontradoException("Lote não está ofertado: " + loteCodigo);
            a.encerrar();
            Lote lote = estoque.obterLote(loteCodigo);
            lote.registrarEtapa(estoque.agora(), "Oferta retirada do marketplace", "Retirada pelo vendedor");
            for (Negociacao n : negociacoes.values()) {
                if (n.aberta() && n.loteCodigo().equals(loteCodigo)) {
                    n.cancelar(Autor.VENDEDOR, "Oferta retirada pelo vendedor.", estoque.agora());
                    eventos.publicar("NEGOCIACAO_ATUALIZADA", n.codigo(), estoque.agora());
                }
            }
            eventos.publicar("OFERTA_ATUALIZADA", loteCodigo, estoque.agora());
            eventos.publicar("ESTOQUE_ATUALIZADO", loteCodigo, estoque.agora());
        }
    }

    // ---------- Vitrine (comprador) ----------

    public List<OfertaMercado> ofertas(String busca) {
        synchronized (estoque) {
            String termo = busca == null ? "" : busca.trim().toLowerCase();
            return anuncios.values().stream()
                    .filter(Anuncio::ativo)
                    .map(a -> Map.entry(a, estoque.obterLote(a.loteCodigo())))
                    .filter(en -> visivel(en.getValue()))
                    .filter(en -> termo.isEmpty()
                            || en.getValue().materiaPrima().toLowerCase().contains(termo)
                            || en.getValue().codigo().toLowerCase().contains(termo))
                    .sorted(Comparator.comparing(en -> en.getValue().limiteUso())) // FEFO
                    .map(en -> oferta(en.getValue(), en.getKey())).toList();
        }
    }

    public OfertaMercado oferta(String loteCodigo) {
        synchronized (estoque) {
            Anuncio a = anuncios.get(loteCodigo);
            if (a == null || !a.ativo()) throw new NaoEncontradoException("Lote não está ofertado: " + loteCodigo);
            return oferta(estoque.obterLote(loteCodigo), a);
        }
    }

    // ---------- Negociação ----------

    /** Comprador abre a negociação. Proposta no preço anunciado (ou acima) fecha a compra na hora. */
    public NegociacaoDetalhe abrirNegociacao(String loteCodigo, PropostaRequest r) {
        synchronized (estoque) {
            Comprador c = compradores.get(r.compradorCodigo());
            if (c == null) throw new NaoEncontradoException("Comprador não encontrado: " + r.compradorCodigo());
            Anuncio a = anuncioAtivo(loteCodigo);
            Lote lote = estoque.obterLote(loteCodigo);
            exigirDentroDoLimite(lote);
            validarProposta(lote, a, r.quantidadeKg(), r.precoKg());
            boolean duplicada = negociacoes.values().stream().anyMatch(n -> n.aberta()
                    && n.loteCodigo().equals(loteCodigo) && n.comprador().codigo().equals(c.codigo()));
            if (duplicada) {
                throw new RegraNegocioException("Já existe uma negociação aberta deste comprador para o lote " + loteCodigo + ".");
            }
            String codigo = "NEG-%03d".formatted(seqNegociacao.getAndIncrement());
            Negociacao n = new Negociacao(codigo, loteCodigo, lote.materiaPrima(), c, a.precoKg(), estoque.agora());
            n.proporOuContrapropor(Autor.COMPRADOR, r.precoKg(), r.quantidadeKg(), r.mensagem(), estoque.agora());
            if (r.precoKg().compareTo(a.precoKg()) >= 0) {
                fechar(n, lote, a, Autor.VENDEDOR, "Aceite automático: proposta no preço anunciado.");
            }
            negociacoes.put(codigo, n);
            eventos.publicar("NEGOCIACAO_ATUALIZADA", codigo, estoque.agora());
            return detalhe(n);
        }
    }

    public NegociacaoDetalhe contrapropor(String codigo, ContrapropostaRequest r) {
        synchronized (estoque) {
            Negociacao n = negociacao(codigo);
            exigirAbertaComVez(n, r.autor());
            Anuncio a = anuncioAtivo(n.loteCodigo());
            Lote lote = estoque.obterLote(n.loteCodigo());
            exigirDentroDoLimite(lote);
            if (n.totalPropostas() >= MAX_PROPOSTAS) {
                throw new RegraNegocioException("Limite de " + MAX_PROPOSTAS + " rodadas atingido. Aceite ou recuse a última proposta.");
            }
            if (r.autor() == Autor.VENDEDOR && r.precoKg().compareTo(a.precoKg()) > 0) {
                throw new RegraNegocioException("A contraproposta do vendedor não pode superar o preço anunciado (R$ "
                        + a.precoKg().setScale(2, RoundingMode.HALF_UP) + "/kg).");
            }
            validarProposta(lote, a, r.quantidadeKg(), r.precoKg());
            n.proporOuContrapropor(r.autor(), r.precoKg(), r.quantidadeKg(), r.mensagem(), estoque.agora());
            if (r.autor() == Autor.COMPRADOR && r.precoKg().compareTo(a.precoKg()) >= 0) {
                fechar(n, lote, a, Autor.VENDEDOR, "Aceite automático: proposta no preço anunciado.");
            }
            eventos.publicar("NEGOCIACAO_ATUALIZADA", codigo, estoque.agora());
            return detalhe(n);
        }
    }

    /** Aceita a última proposta do outro lado: reserva o saldo e cria a entrega. */
    public NegociacaoDetalhe aceitar(String codigo, DecisaoRequest r) {
        synchronized (estoque) {
            Negociacao n = negociacao(codigo);
            exigirAbertaComVez(n, r.autor());
            Anuncio a = anuncioAtivo(n.loteCodigo());
            Lote lote = estoque.obterLote(n.loteCodigo());
            fechar(n, lote, a, r.autor(), r.mensagem());
            eventos.publicar("NEGOCIACAO_ATUALIZADA", codigo, estoque.agora());
            return detalhe(n);
        }
    }

    public NegociacaoDetalhe recusar(String codigo, DecisaoRequest r) {
        synchronized (estoque) {
            Negociacao n = negociacao(codigo);
            exigirAbertaComVez(n, r.autor());
            n.recusar(r.autor(), r.mensagem(), estoque.agora());
            eventos.publicar("NEGOCIACAO_ATUALIZADA", codigo, estoque.agora());
            return detalhe(n);
        }
    }

    /** O comprador pode desistir a qualquer momento enquanto a negociação estiver aberta. */
    public NegociacaoDetalhe cancelar(String codigo, DecisaoRequest r) {
        synchronized (estoque) {
            Negociacao n = negociacao(codigo);
            if (r.autor() != Autor.COMPRADOR) {
                throw new RegraNegocioException("Só o comprador pode cancelar. O vendedor deve recusar a proposta.");
            }
            if (!n.aberta()) throw new RegraNegocioException("Negociação já encerrada (" + n.status() + ").");
            n.cancelar(Autor.COMPRADOR, r.mensagem(), estoque.agora());
            eventos.publicar("NEGOCIACAO_ATUALIZADA", codigo, estoque.agora());
            return detalhe(n);
        }
    }

    public List<NegociacaoDetalhe> listarNegociacoes(String compradorCodigo, String loteCodigo,
                                                     StatusNegociacao status) {
        synchronized (estoque) {
            return negociacoes.values().stream()
                    .filter(n -> compradorCodigo == null || n.comprador().codigo().equals(compradorCodigo))
                    .filter(n -> loteCodigo == null || n.loteCodigo().equals(loteCodigo))
                    .filter(n -> status == null || n.status() == status)
                    .sorted(Comparator.comparing(Negociacao::atualizadaEm).reversed())
                    .map(this::detalhe).toList();
        }
    }

    public NegociacaoDetalhe detalheNegociacao(String codigo) {
        synchronized (estoque) {
            return detalhe(negociacao(codigo));
        }
    }

    // ---------- Regras ----------

    /** Revalida estoque e prazo, reserva o saldo, abate a oferta e cria a entrega. */
    private void fechar(Negociacao n, Lote lote, Anuncio a, Autor quemAceita, String mensagem) {
        Rodada ultima = n.ultimaProposta();
        BigDecimal qtd = ultima.quantidadeKg();
        if (lote.qualidade() != Qualidade.LIBERADO) {
            throw new RegraNegocioException("O lote não está mais liberado pela qualidade.");
        }
        exigirDentroDoLimite(lote);
        if (qtd.compareTo(lote.disponivelKg()) > 0 || qtd.compareTo(a.quantidadeKg()) > 0) {
            throw new RegraNegocioException("Saldo insuficiente: restam "
                    + lote.disponivelKg().min(a.quantidadeKg()).stripTrailingZeros().toPlainString()
                    + " kg disponíveis. Contraproponha uma quantidade menor.");
        }
        Entrega entrega = logistica.criar(n.codigo(), lote, qtd, n.comprador());
        n.aceitar(quemAceita, mensagem, estoque.agora(), entrega.codigo());
        lote.reservarParaVenda(qtd, n.codigo(), n.comprador().empresa(), estoque.agora());
        a.consumir(qtd);
        eventos.publicar("OFERTA_ATUALIZADA", lote.codigo(), estoque.agora());
        eventos.publicar("ESTOQUE_ATUALIZADO", lote.codigo(), estoque.agora());
    }

    private void validarProposta(Lote lote, Anuncio a, BigDecimal qtd, BigDecimal preco) {
        BigDecimal maximo = lote.disponivelKg().min(a.quantidadeKg());
        if (qtd.compareTo(maximo) > 0) {
            throw new RegraNegocioException("Quantidade acima do disponível na oferta ("
                    + maximo.stripTrailingZeros().toPlainString() + " kg).");
        }
        BigDecimal minimo = a.quantidadeMinimaKg().min(maximo);
        if (qtd.compareTo(minimo) < 0) {
            throw new RegraNegocioException("Quantidade abaixo do mínimo da oferta ("
                    + minimo.stripTrailingZeros().toPlainString() + " kg).");
        }
        if (preco.signum() <= 0) throw new RegraNegocioException("O preço deve ser maior que zero.");
    }

    private void exigirAbertaComVez(Negociacao n, Autor autor) {
        if (!n.aberta()) throw new RegraNegocioException("Negociação já encerrada (" + n.status() + ").");
        if (n.vezDe() != autor) {
            throw new RegraNegocioException("Aguardando resposta do " + n.vezDe().name().toLowerCase()
                    + ". Você pode agir depois da resposta dele.");
        }
    }

    private void exigirDentroDoLimite(Lote lote) {
        if (!lote.limiteUso().isAfter(estoque.agora())) {
            throw new RegraNegocioException("O limite de uso do lote " + lote.codigo() + " já expirou.");
        }
    }

    private Anuncio anuncioAtivo(String loteCodigo) {
        Anuncio a = anuncios.get(loteCodigo);
        if (a == null || !a.ativo()) throw new RegraNegocioException("O lote " + loteCodigo + " não está mais ofertado.");
        return a;
    }

    private Negociacao negociacao(String codigo) {
        Negociacao n = negociacoes.get(codigo);
        if (n == null) throw new NaoEncontradoException("Negociação não encontrada: " + codigo);
        return n;
    }

    private boolean visivel(Lote lote) {
        return lote.limiteUso().isAfter(estoque.agora()) && lote.disponivelKg().signum() > 0;
    }

    // ---------- Montagem de respostas ----------

    private OfertaMercado oferta(Lote l, Anuncio a) {
        BigDecimal ofertada = l.disponivelKg().min(a.quantidadeKg());
        return new OfertaMercado(l.codigo(), l.materiaPrima(), l.categoria(), VENDEDOR,
                l.linhaOrigem() + " • " + l.setorOrigem() + " • lote de produção " + l.loteProducaoOrigem(),
                a.precoKg(), ofertada, a.quantidadeMinimaKg().min(ofertada), l.limiteUso(),
                Duration.between(estoque.agora(), l.limiteUso()).toHours(), l.condicaoConservacao(),
                l.laudo(), l.processoLiberado(), l.responsavelQualidade(), a.publicadoEm(),
                l.etapas(), l.avaliacao());
    }

    private NegociacaoDetalhe detalhe(Negociacao n) {
        Rodada ultima = n.ultimaProposta();
        ValorNegociado valores = null;
        if (ultima != null) {
            BigDecimal bruto = ultima.precoKg().multiply(ultima.quantidadeKg()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal comissao = bruto.multiply(TAKE_RATE).setScale(2, RoundingMode.HALF_UP);
            valores = new ValorNegociado(bruto, TAKE_RATE.multiply(BigDecimal.valueOf(100)).setScale(0),
                    comissao, bruto.subtract(comissao));
        }
        return new NegociacaoDetalhe(n.codigo(), n.loteCodigo(), n.materiaPrima(), n.comprador(), n.status(),
                n.aberta() ? n.vezDe() : null, n.precoAnunciadoKg(), ultima, valores, n.rodadas(),
                n.entregaCodigo(), n.criadaEm(), n.atualizadaEm());
    }
}
