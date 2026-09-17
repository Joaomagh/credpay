package br.com.credpay.transacoes.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class PublicarOutboxServiceTest {

    private static final Instant PUBLICADO_EM = Instant.parse("2026-09-17T13:00:00Z");

    @Test
    void publicarProximo_deveMarcarEvento_quandoBrokerConfirmarSemRetorno() {
        var repository = mock(OutboxRepository.class);
        var publicador = mock(PublicadorEvento.class);
        var evento = eventoPendente();
        var service = new PublicarOutboxService(
                repository,
                publicador,
                Clock.fixed(PUBLICADO_EM, ZoneOffset.UTC));
        when(repository.buscarPendentes(1)).thenReturn(List.of(evento));
        when(publicador.publicar(evento)).thenReturn(true);

        var publicado = service.publicarProximo();

        assertThat(publicado).isTrue();
        verify(repository).marcarPublicado(evento.eventId(), PUBLICADO_EM);
    }

    @Test
    void publicarProximo_deveManterPendente_quandoBrokerNaoConfirmarOuRetornar() {
        var repository = mock(OutboxRepository.class);
        var publicador = mock(PublicadorEvento.class);
        var evento = eventoPendente();
        var service = new PublicarOutboxService(
                repository,
                publicador,
                Clock.fixed(PUBLICADO_EM, ZoneOffset.UTC));
        when(repository.buscarPendentes(1)).thenReturn(List.of(evento));
        when(publicador.publicar(evento)).thenReturn(false);

        var publicado = service.publicarProximo();

        assertThat(publicado).isFalse();
        verify(repository, never()).marcarPublicado(evento.eventId(), PUBLICADO_EM);
    }

    @Test
    void publicarProximo_deveEncerrarSemPublicar_quandoNaoHouverPendente() {
        var repository = mock(OutboxRepository.class);
        var publicador = mock(PublicadorEvento.class);
        var service = new PublicarOutboxService(
                repository,
                publicador,
                Clock.fixed(PUBLICADO_EM, ZoneOffset.UTC));
        when(repository.buscarPendentes(1)).thenReturn(List.of());

        var publicado = service.publicarProximo();

        assertThat(publicado).isFalse();
        verifyNoInteractions(publicador);
        verify(repository, never()).marcarPublicado(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void publicarLote_deveProcessarNoMaximoVinteEventosConfirmados() {
        var repository = mock(OutboxRepository.class);
        var publicador = mock(PublicadorEvento.class);
        var service = new PublicarOutboxService(
                repository,
                publicador,
                Clock.fixed(PUBLICADO_EM, ZoneOffset.UTC));
        var eventos = IntStream.rangeClosed(1, 20)
                .mapToObj(numero -> eventoPendente(String.format(
                        "00000000-0000-0000-0000-%012d", numero)))
                .toList();
        when(repository.buscarPendentes(20)).thenReturn(eventos);
        when(publicador.publicar(any(EventoOutbox.class))).thenReturn(true);

        var quantidade = service.publicarLote();

        assertThat(quantidade).isEqualTo(20);
        verify(publicador, times(20)).publicar(any(EventoOutbox.class));
        eventos.forEach(evento ->
                verify(repository).marcarPublicado(evento.eventId(), PUBLICADO_EM));
    }

    @Test
    void publicarLote_deveInterromperNoPrimeiroEventoNaoConfirmado() {
        var repository = mock(OutboxRepository.class);
        var publicador = mock(PublicadorEvento.class);
        var service = new PublicarOutboxService(
                repository,
                publicador,
                Clock.fixed(PUBLICADO_EM, ZoneOffset.UTC));
        var primeiro = eventoPendente("00000000-0000-0000-0000-000000000001");
        var segundo = eventoPendente("00000000-0000-0000-0000-000000000002");
        var terceiro = eventoPendente("00000000-0000-0000-0000-000000000003");
        when(repository.buscarPendentes(20)).thenReturn(List.of(primeiro, segundo, terceiro));
        when(publicador.publicar(primeiro)).thenReturn(true);
        when(publicador.publicar(segundo)).thenReturn(false);

        var quantidade = service.publicarLote();

        assertThat(quantidade).isEqualTo(1);
        verify(repository).marcarPublicado(primeiro.eventId(), PUBLICADO_EM);
        verify(repository, never()).marcarPublicado(segundo.eventId(), PUBLICADO_EM);
        verify(repository, never()).marcarPublicado(terceiro.eventId(), PUBLICADO_EM);
        verify(publicador, never()).publicar(terceiro);
    }

    private EventoOutbox eventoPendente() {
        return eventoPendente("6dc8d48d-5b20-4ee9-ac7e-832e421121aa");
    }

    private EventoOutbox eventoPendente(String eventId) {
        return new EventoOutbox(
                UUID.fromString(eventId),
                UUID.fromString("7b8b61c2-9f63-4d74-9f8d-89cb52de0ed9"),
                "TransacaoCriada",
                1,
                "{\"eventType\":\"TransacaoCriada\"}",
                Instant.parse("2026-09-17T12:00:00Z"));
    }
}
