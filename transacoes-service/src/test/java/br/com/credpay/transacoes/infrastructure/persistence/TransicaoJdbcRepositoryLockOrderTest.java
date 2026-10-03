package br.com.credpay.transacoes.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class TransicaoJdbcRepositoryLockOrderTest {

    @Test
    void bloquearIdentidades_deveOrdenarChavesEfetivasInclusiveComIdentidadesInvertidas() {
        var jdbc = mock(JdbcTemplate.class);
        var evento = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var transacao = UUID.fromString("00000000-0000-0000-0000-000000000002");
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(evento.toString()))).thenReturn(20L);
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(transacao.toString()))).thenReturn(-10L);
        var adquiridas = new ArrayList<Long>();
        doAnswer(invocation -> {
            adquiridas.add(invocation.getArgument(2));
            return 1L;
        }).when(jdbc).queryForObject(anyString(), eq(Long.class), anyLong());
        var repository = new TransicaoJdbcRepository(jdbc);

        repository.bloquearIdentidades(evento, transacao);
        repository.bloquearIdentidades(transacao, evento);

        assertThat(adquiridas).containsExactly(-10L, 20L, -10L, 20L);
    }

    @Test
    void bloquearIdentidades_deveAdquirirUmaVez_quandoHashesCoincidirem() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), anyString())).thenReturn(20L);
        var adquiridas = new ArrayList<Long>();
        doAnswer(invocation -> {
            adquiridas.add(invocation.getArgument(2));
            return 1L;
        }).when(jdbc).queryForObject(anyString(), eq(Long.class), anyLong());
        var repository = new TransicaoJdbcRepository(jdbc);

        repository.bloquearIdentidades(UUID.randomUUID(), UUID.randomUUID());

        assertThat(adquiridas).containsExactly(20L);
    }
}
