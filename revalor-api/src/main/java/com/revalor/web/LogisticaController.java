package com.revalor.web;

import com.revalor.dto.DtosMercado.*;
import com.revalor.service.BarramentoEventos;
import com.revalor.service.LogisticaService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Logística em tempo real: entregas, telemetria e o canal de eventos (Server-Sent Events). */
@RestController
@RequestMapping("/api")
public class LogisticaController {

    private final LogisticaService service;
    private final BarramentoEventos barramento;
    /** Uma thread só: mantém a ordem dos eventos e evita que um cliente lento trave quem publica. */
    private final ExecutorService envio = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sse-envio");
        t.setDaemon(true);
        return t;
    });

    public LogisticaController(LogisticaService service, BarramentoEventos barramento) {
        this.service = service;
        this.barramento = barramento;
    }

    @GetMapping("/entregas")
    public List<EntregaDetalhe> entregas(@RequestParam(required = false) String compradorCodigo) {
        return service.listar(compradorCodigo);
    }

    @GetMapping("/entregas/{codigo}")
    public EntregaDetalhe entrega(@PathVariable String codigo) {
        return service.detalhe(codigo);
    }

    /** Motorista confirma a coleta na origem. */
    @PostMapping("/entregas/{codigo}/coleta")
    public EntregaDetalhe coleta(@PathVariable String codigo, @RequestBody(required = false) ColetaRequest request) {
        return service.coletar(codigo, request == null ? null : request.temperaturaC());
    }

    /** Telemetria do veículo: posição e temperatura da carga. */
    @PostMapping("/entregas/{codigo}/telemetria")
    public EntregaDetalhe telemetria(@PathVariable String codigo, @Valid @RequestBody TelemetriaRequest request) {
        return service.telemetria(codigo, request);
    }

    /** Recebimento confirmado no destino. */
    @PostMapping("/entregas/{codigo}/confirmacao")
    public EntregaDetalhe confirmacao(@PathVariable String codigo) {
        return service.confirmar(codigo);
    }

    /** Demonstração: avança um passo da viagem simulada. */
    @PostMapping("/entregas/{codigo}/avanco")
    public EntregaDetalhe avanco(@PathVariable String codigo) {
        return service.avancar(codigo);
    }

    /** Demonstração: liga ou desliga o avanço automático. */
    @PostMapping("/entregas/{codigo}/simulacao")
    public EntregaDetalhe simulacao(@PathVariable String codigo, @RequestBody SimulacaoRequest request) {
        return service.simulacao(codigo, request.ativa());
    }

    /**
     * Canal de eventos em tempo real (SSE). Cada evento traz tipo, referência e momento;
     * o cliente busca o dado atualizado na rota correspondente.
     */
    @GetMapping(path = "/eventos", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter eventos() {
        SseEmitter emitter = new SseEmitter(0L); // sem expiração; o navegador reconecta sozinho
        Runnable cancelar = barramento.inscrever(evento -> envio.execute(() -> {
            try {
                emitter.send(SseEmitter.event().name(evento.tipo()).data(evento));
            } catch (IOException | IllegalStateException e) {
                emitter.complete();
            }
        }));
        emitter.onCompletion(cancelar);
        emitter.onTimeout(cancelar);
        emitter.onError(e -> cancelar.run());
        envio.execute(() -> {
            try {
                emitter.send(SseEmitter.event().name("CONECTADO").data("ok"));
            } catch (IOException | IllegalStateException e) {
                emitter.complete();
            }
        });
        return emitter;
    }
}
