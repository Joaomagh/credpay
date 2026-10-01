package br.com.credpay.processamento.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.credpay.processamento.domain.StatusProcessamento;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RegistrarProcessamentoServiceTest {

    private final RepositorioEmMemoria repository = new RepositorioEmMemoria();
    private final UUID transactionId = UUID.fromString("a76a5537-a838-4ae5-a52d-26cd8463b7f0");
    private final UUID eventId = UUID.fromString("61e71d1c-2844-459d-8490-c77765b23690");
    private final UUID outputId = UUID.fromString("ed826c39-d7cc-4fe2-a225-b106e8b73367");
    private final Instant occurredAt = Instant.parse("2026-09-30T12:00:00.123456789Z");
    private final Instant processedAt = Instant.parse("2026-09-30T12:00:01.987654Z");

    @Test
    void executar_devePersistirPrimeiraDecisaoComSnapshotCompleto() {
        var service = service(moeda -> new BigDecimal("100.00"),
                Clock.fixed(processedAt, ZoneOffset.UTC), () -> outputId);

        var resultado = service.executar(entrada(new BigDecimal("75.00")));

        assertThat(resultado.eventId()).isEqualTo(eventId);
        assertThat(resultado.transactionId()).isEqualTo(transactionId);
        assertThat(resultado.occurredAt()).isEqualTo(occurredAt);
        assertThat(resultado.valor()).isEqualByComparingTo("75.00");
        assertThat(resultado.limiteAplicado()).isEqualByComparingTo("100.00");
        assertThat(resultado.status()).isEqualTo(StatusProcessamento.APROVADA);
        assertThat(resultado.processedAt()).isEqualTo(processedAt);
        assertThat(resultado.outputEventId()).isEqualTo(outputId);
        assertThat(repository.buscarPorEventId(eventId)).contains(resultado);
    }

    @Test
    void entrada_deveRejeitarCorrelacaoDivergenteAntesDoProcessamento() {
        assertThatThrownBy(() -> new TransacaoCriadaRecebida(eventId, transactionId,
                occurredAt, UUID.randomUUID(), new BigDecimal("75.00"), Currency.getInstance("BRL")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("correlationId deve corresponder a transactionId");
    }

    @Test
    void entrada_deveRejeitarValorNaoPositivoAntesDoProcessamento() {
        assertThatThrownBy(() -> entrada(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("valor deve ser maior que zero");
    }

    @Test
    void executar_deveReusarSnapshot_quandoMesmoEventoForEquivalenteAposMudancaDePolitica() {
        var consultas = new AtomicInteger();
        var service = service(moeda -> {
            consultas.incrementAndGet();
            return new BigDecimal("100.00");
        }, Clock.fixed(processedAt, ZoneOffset.UTC), () -> outputId);
        var original = service.executar(entrada(new BigDecimal("75.0")));
        var replay = service(moeda -> {
            throw new AssertionError("politica nao deve ser consultada no replay");
        }, new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { throw new AssertionError("relogio nao deve ser consultado"); }
        }, () -> { throw new AssertionError("novo ID nao deve ser gerado"); });

        var recebido = replay.executar(entrada(new BigDecimal("75.00")));

        assertThat(recebido).isSameAs(original);
        assertThat(repository.registros).hasSize(1);
        assertThat(consultas).hasValue(1);
    }

    @ParameterizedTest
    @EnumSource(CampoDivergente.class)
    void executar_deveRejeitarMesmoEventoDivergenteSemSobrescrever(CampoDivergente campo) {
        var original = service(moeda -> new BigDecimal("100.00"),
                Clock.fixed(processedAt, ZoneOffset.UTC), () -> outputId)
                .executar(entrada(new BigDecimal("75.00")));
        var replay = service(moeda -> { throw new AssertionError("politica consultada"); },
                Clock.fixed(processedAt, ZoneOffset.UTC), () -> UUID.randomUUID());

        var divergente = switch (campo) {
            case VALOR -> entrada(new BigDecimal("76.00"));
            case MOEDA -> new TransacaoCriadaRecebida(eventId, transactionId,
                    occurredAt, transactionId, new BigDecimal("75.00"), Currency.getInstance("USD"));
            case OCORREU_EM -> new TransacaoCriadaRecebida(eventId, transactionId,
                    occurredAt.plusNanos(1), transactionId,
                    new BigDecimal("75.00"), Currency.getInstance("BRL"));
            case TRANSACAO -> {
                var outraTransacao = UUID.randomUUID();
                yield new TransacaoCriadaRecebida(eventId, outraTransacao,
                        occurredAt, outraTransacao, new BigDecimal("75.00"), Currency.getInstance("BRL"));
            }
        };

        assertThatThrownBy(() -> replay.executar(divergente))
                .isInstanceOf(ConflitoProcessamentoException.class)
                .hasMessage("evento divergente");
        assertThat(repository.buscarPorEventId(eventId)).contains(original);
        assertThat(repository.registros).hasSize(1);
    }

    @Test
    void executar_deveRejeitarNovoEventoParaTransacaoJaProcessada() {
        var original = service(moeda -> new BigDecimal("100.00"),
                Clock.fixed(processedAt, ZoneOffset.UTC), () -> outputId)
                .executar(entrada(new BigDecimal("75.00")));
        var replay = service(moeda -> { throw new AssertionError("politica consultada"); },
                Clock.fixed(processedAt, ZoneOffset.UTC), () -> UUID.randomUUID());
        var outroEvento = new TransacaoCriadaRecebida(UUID.randomUUID(), transactionId,
                occurredAt, transactionId, new BigDecimal("75.00"), Currency.getInstance("BRL"));

        assertThatThrownBy(() -> replay.executar(outroEvento))
                .isInstanceOf(ConflitoProcessamentoException.class)
                .hasMessage("transacao ja processada");
        assertThat(repository.buscarPorEventId(eventId)).contains(original);
        assertThat(repository.registros).hasSize(1);
    }

    private RegistrarProcessamentoService service(
            LimitesProcessamento limites, Clock clock, GeradorEventIdSaida gerador) {
        return new RegistrarProcessamentoService(repository,
                new ProcessarTransacaoService(limites), clock, gerador);
    }

    private TransacaoCriadaRecebida entrada(BigDecimal valor) {
        return new TransacaoCriadaRecebida(eventId, transactionId, occurredAt,
                transactionId, valor, Currency.getInstance("BRL"));
    }

    private static final class RepositorioEmMemoria implements ProcessamentoRepository {
        private final Map<UUID, ProcessamentoRegistrado> registros = new HashMap<>();

        @Override public void bloquearIdentidades(UUID eventId, UUID transactionId) { }

        @Override public void inserir(ProcessamentoRegistrado processamento) {
            registros.put(processamento.eventId(), processamento);
        }

        @Override public Optional<ProcessamentoRegistrado> buscarPorEventId(UUID id) {
            return Optional.ofNullable(registros.get(id));
        }

        @Override public boolean existePorTransactionId(UUID id) {
            return registros.values().stream().anyMatch(registro -> registro.transactionId().equals(id));
        }
    }

    private enum CampoDivergente {
        VALOR, MOEDA, OCORREU_EM, TRANSACAO
    }
}
