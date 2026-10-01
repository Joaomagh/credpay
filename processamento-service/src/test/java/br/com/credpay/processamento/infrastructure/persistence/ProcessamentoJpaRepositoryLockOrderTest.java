package br.com.credpay.processamento.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ProcessamentoJpaRepositoryLockOrderTest {

    private static final UUID PRIMEIRO = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SEGUNDO = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void bloquearIdentidades_deveOrdenarAsChavesReais_doPostgreSQL() {
        var chavesAdquiridas = executarComHashes(20L, 10L);

        assertThat(chavesAdquiridas).containsExactly(10L, 20L);
    }

    @Test
    void bloquearIdentidades_deveAdquirirUmaVez_quandoHashesColidirem() {
        var chavesAdquiridas = executarComHashes(20L, 20L);

        assertThat(chavesAdquiridas).containsExactly(20L);
    }

    private List<Object> executarComHashes(long hashPrimeiro, long hashSegundo) {
        var entityManager = mock(EntityManager.class);
        var chavesAdquiridas = new ArrayList<Object>();
        when(entityManager.createNativeQuery(anyString(), eq(Long.class))).thenAnswer(invocacao -> {
            String sql = invocacao.getArgument(0);
            var parametro = new AtomicReference<Object>();
            var query = mock(Query.class);
            when(query.setParameter(anyString(), org.mockito.ArgumentMatchers.any()))
                    .thenAnswer(chamada -> {
                        parametro.set(chamada.getArgument(1));
                        return query;
                    });
            when(query.getSingleResult()).thenAnswer(chamada -> {
                if (sql.contains("pg_advisory_xact_lock")) {
                    chavesAdquiridas.add(parametro.get());
                    return 1L;
                }
                return PRIMEIRO.toString().equals(parametro.get()) ? hashPrimeiro : hashSegundo;
            });
            return query;
        });
        var repository = new ProcessamentoJpaRepository();
        ReflectionTestUtils.setField(repository, "entityManager", entityManager);

        repository.bloquearIdentidades(PRIMEIRO, SEGUNDO);
        return chavesAdquiridas;
    }
}
