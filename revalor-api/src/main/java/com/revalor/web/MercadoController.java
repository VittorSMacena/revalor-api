package com.revalor.web;

import com.revalor.dto.DtosMercado.*;
import com.revalor.model.Dominio.*;
import com.revalor.service.MercadoService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Marketplace: oferta do lote (vendedor), vitrine e negociação (comprador). */
@RestController
@RequestMapping("/api")
public class MercadoController {

    private final MercadoService service;

    public MercadoController(MercadoService service) {
        this.service = service;
    }

    @GetMapping("/compradores")
    public List<Comprador> compradores() {
        return service.listarCompradores();
    }

    // ----- Vendedor (Rafael) -----

    /** Oferta um lote liberado no marketplace. */
    @PostMapping("/lotes/{codigo}/oferta")
    @ResponseStatus(HttpStatus.CREATED)
    public OfertaMercado publicar(@PathVariable String codigo, @Valid @RequestBody PublicarOfertaRequest request) {
        return service.publicar(codigo, request);
    }

    @DeleteMapping("/lotes/{codigo}/oferta")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void retirar(@PathVariable String codigo) {
        service.retirar(codigo);
    }

    // ----- Comprador (Beatriz) -----

    /** Vitrine: lotes ofertados, ordenados por validade (FEFO). */
    @GetMapping("/mercado/ofertas")
    public List<OfertaMercado> ofertas(@RequestParam(required = false) String busca) {
        return service.ofertas(busca);
    }

    /** Detalhe da oferta com rastreabilidade e avaliação de qualidade do lote. */
    @GetMapping("/mercado/ofertas/{loteCodigo}")
    public OfertaMercado oferta(@PathVariable String loteCodigo) {
        return service.oferta(loteCodigo);
    }

    /** Abre a negociação com uma proposta de preço e quantidade. */
    @PostMapping("/mercado/ofertas/{loteCodigo}/negociacoes")
    @ResponseStatus(HttpStatus.CREATED)
    public NegociacaoDetalhe propor(@PathVariable String loteCodigo, @Valid @RequestBody PropostaRequest request) {
        return service.abrirNegociacao(loteCodigo, request);
    }

    // ----- Negociação (as duas partes) -----

    @GetMapping("/negociacoes")
    public List<NegociacaoDetalhe> negociacoes(@RequestParam(required = false) String compradorCodigo,
                                               @RequestParam(required = false) String loteCodigo,
                                               @RequestParam(required = false) StatusNegociacao status) {
        return service.listarNegociacoes(compradorCodigo, loteCodigo, status);
    }

    @GetMapping("/negociacoes/{codigo}")
    public NegociacaoDetalhe negociacao(@PathVariable String codigo) {
        return service.detalheNegociacao(codigo);
    }

    @PostMapping("/negociacoes/{codigo}/contraproposta")
    public NegociacaoDetalhe contrapropor(@PathVariable String codigo,
                                          @Valid @RequestBody ContrapropostaRequest request) {
        return service.contrapropor(codigo, request);
    }

    /** Aceita a última proposta do outro lado: reserva o saldo e cria a entrega. */
    @PostMapping("/negociacoes/{codigo}/aceite")
    public NegociacaoDetalhe aceitar(@PathVariable String codigo, @Valid @RequestBody DecisaoRequest request) {
        return service.aceitar(codigo, request);
    }

    @PostMapping("/negociacoes/{codigo}/recusa")
    public NegociacaoDetalhe recusar(@PathVariable String codigo, @Valid @RequestBody DecisaoRequest request) {
        return service.recusar(codigo, request);
    }

    @PostMapping("/negociacoes/{codigo}/cancelamento")
    public NegociacaoDetalhe cancelar(@PathVariable String codigo, @Valid @RequestBody DecisaoRequest request) {
        return service.cancelar(codigo, request);
    }
}
