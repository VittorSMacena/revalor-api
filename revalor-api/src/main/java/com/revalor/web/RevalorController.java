package com.revalor.web;

import com.revalor.dto.Dtos.*;
import com.revalor.model.Dominio.*;
import com.revalor.service.EstoqueService;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class RevalorController {

    private final EstoqueService service;

    public RevalorController(EstoqueService service) {
        this.service = service;
    }

    /** Visão geral. */
    @GetMapping("/dashboard")
    public Dashboard dashboard() {
        return service.dashboard();
    }

    /** Estoque para reaproveitamento (filtros: situacao, busca). */
    @GetMapping("/lotes")
    public List<LoteResumo> listar(@RequestParam(required = false) Situacao situacao,
                                   @RequestParam(required = false) String busca) {
        return service.listar(situacao, busca);
    }

    /** Detalhe do lote: rastreabilidade, avaliação de qualidade e processos aprovados. */
    @GetMapping("/lotes/{codigo}")
    public LoteDetalhe detalhe(@PathVariable String codigo) {
        return service.detalhe(codigo);
    }

    /** Registrar excedente (entra em quarentena). */
    @PostMapping("/lotes")
    @ResponseStatus(HttpStatus.CREATED)
    public LoteDetalhe registrar(@Valid @RequestBody RegistroExcedenteRequest request) {
        return service.registrar(request);
    }

    /** Liberação pela qualidade. */
    @PostMapping("/lotes/{codigo}/liberacao")
    public LoteDetalhe liberar(@PathVariable String codigo, @Valid @RequestBody LiberacaoRequest request) {
        return service.liberar(codigo, request);
    }

    /** Resultado previsto antes de confirmar a destinação. */
    @GetMapping("/lotes/{codigo}/simulacao-destino")
    public SimulacaoDestino simular(@PathVariable String codigo, @RequestParam String processo,
                                    @RequestParam BigDecimal quantidadeKg) {
        return service.simular(codigo, processo, quantidadeKg);
    }

    /** Confirmar destinação: reserva o saldo e vincula o lote a uma nova ordem. */
    @PostMapping("/lotes/{codigo}/destino")
    @ResponseStatus(HttpStatus.CREATED)
    public OrdemUpcycling destinar(@PathVariable String codigo, @Valid @RequestBody DestinoRequest request) {
        return service.destinar(codigo, request);
    }

    @GetMapping("/processos")
    public List<ProcessoUpcycling> processos() {
        return service.listarProcessos();
    }

    @GetMapping("/ordens")
    public List<OrdemUpcycling> ordens() {
        return service.listarOrdens();
    }
}
