package br.com.credpay.transacoes.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

import br.com.credpay.transacoes.application.TransacaoRepository;
import br.com.credpay.transacoes.domain.StatusTransacao;
import br.com.credpay.transacoes.domain.Transacao;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false",
        "spring.jpa.properties.hibernate.cache.use_query_cache=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TransacaoJpaRepository.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class TransacaoRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            "postgres@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0")
            .withDatabaseName("credpay_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configurarBanco(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private TransacaoRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @ParameterizedTest
    @CsvSource({
            "7b8b61c2-9f63-4d74-9f8d-89cb52de0ed9, 10.00, BRL",
            "fb72faab-a904-4aab-9bc1-2a93da8de941, 123.456, USD"
    })
    void inserir_devePermitirLeituraEmOutraTransacao_quandoCommitForConcluido(
            String identidade, String quantia, String codigoMoeda) {
        var id = UUID.fromString(identidade);
        var valor = new BigDecimal(quantia);
        var moeda = Currency.getInstance(codigoMoeda);
        var transacao = Transacao.criar(id, valor, moeda);
        var transacoes = new TransactionTemplate(transactionManager);

        transacoes.executeWithoutResult(status -> repository.inserir(transacao));
        Optional<Transacao> encontrada = transacoes.execute(status -> repository.buscarPorId(id));

        assertThat(encontrada).isPresent();
        var persistida = encontrada.orElseThrow();
        assertThat(persistida).isNotSameAs(transacao);
        assertThat(persistida.id()).isEqualTo(id);
        assertThat(persistida.valor()).isEqualByComparingTo(valor);
        assertThat(persistida.valor().scale()).isEqualTo(valor.scale());
        assertThat(persistida.moeda()).isEqualTo(moeda);
        assertThat(persistida.status()).isEqualTo(StatusTransacao.PENDENTE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-0.01", "NaN", "Infinity", "-Infinity"})
    void banco_deveRejeitarValorNaoPositivoOuNaoFinito_quandoDominioForContornado(String valor) {
        var id = UUID.randomUUID();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO transacoes (id, valor, moeda, status)
                VALUES (?, CAST(? AS numeric), 'BRL', 'PENDENTE')
                """, id, valor))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("ck_transacoes_valor_positivo_finito");
    }

    @Test
    void inserir_deveFalharSemSobrescreverOriginal_quandoIdJaExistir() {
        var id = UUID.fromString("385572ba-10e7-4fbc-b447-26463d284a17");
        var original = Transacao.criar(id, new BigDecimal("10.00"), Currency.getInstance("BRL"));
        var duplicada = Transacao.criar(id, new BigDecimal("20.00"), Currency.getInstance("USD"));
        var transacoes = new TransactionTemplate(transactionManager);

        transacoes.executeWithoutResult(status -> repository.inserir(original));

        assertThatThrownBy(() -> transacoes.executeWithoutResult(status -> repository.inserir(duplicada)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("transacoes_pkey");

        Optional<Transacao> encontrada = transacoes.execute(status -> repository.buscarPorId(id));
        assertThat(encontrada).isPresent();
        var persistida = encontrada.orElseThrow();
        assertThat(persistida.valor()).isEqualByComparingTo("10.00");
        assertThat(persistida.valor().scale()).isEqualTo(2);
        assertThat(persistida.moeda()).isEqualTo(Currency.getInstance("BRL"));
        assertThat(persistida.status()).isEqualTo(StatusTransacao.PENDENTE);
    }

    @Test
    void buscarPorId_deveRetornarVazio_quandoTransacaoNaoExistir() {
        var idInexistente = UUID.fromString("7ac65065-6d2e-45fa-a838-a54155668c9c");
        var transacoes = new TransactionTemplate(transactionManager);

        Optional<Transacao> encontrada = transacoes.execute(
                status -> repository.buscarPorId(idInexistente));

        assertThat(encontrada).isEmpty();
    }

    @Test
    void inserir_deveDescartarRegistro_quandoTransacaoForRevertida() {
        var id = UUID.fromString("bc414eee-93e9-44bd-b228-e1985e127d2d");
        var transacao = Transacao.criar(id, new BigDecimal("10.00"), Currency.getInstance("BRL"));
        var transacoes = new TransactionTemplate(transactionManager);

        transacoes.executeWithoutResult(status -> {
            repository.inserir(transacao);
            entityManager.flush();
            status.setRollbackOnly();
        });

        Optional<Transacao> encontrada = transacoes.execute(status -> repository.buscarPorId(id));
        assertThat(encontrada).isEmpty();
    }

    @Test
    void inserirComChave_devePermitirBuscaPorChaveEmOutraTransacao() {
        var chaveIdempotencia = UUID.fromString("03714dde-d152-47f0-97f0-82171ffbe150");
        var id = UUID.fromString("44fe8ae7-47a6-45f2-bf6c-46a83ee782ee");
        var transacao = Transacao.criar(
                id, new BigDecimal("10.00"), Currency.getInstance("BRL"));
        var transacoes = new TransactionTemplate(transactionManager);

        transacoes.executeWithoutResult(
                status -> repository.inserir(chaveIdempotencia, transacao));
        Optional<Transacao> encontrada = transacoes.execute(
                status -> repository.buscarPorChaveIdempotencia(chaveIdempotencia));

        assertThat(encontrada).isPresent();
        var persistida = encontrada.orElseThrow();
        assertThat(persistida.id()).isEqualTo(id);
        assertThat(persistida.valor()).isEqualByComparingTo("10.00");
        assertThat(persistida.valor().scale()).isEqualTo(2);
        assertThat(persistida.moeda()).isEqualTo(Currency.getInstance("BRL"));
        assertThat(persistida.status()).isEqualTo(StatusTransacao.PENDENTE);
    }

    @Test
    void inserirComChave_deveFalharSemSobrescreverOriginal_quandoChaveJaExistir() {
        var chaveIdempotencia = UUID.fromString("82e27f0c-3d82-45b9-970b-2ac252040e8a");
        var original = Transacao.criar(
                UUID.fromString("90c8ed8a-6b22-4f1d-b836-d103ddf94adc"),
                new BigDecimal("10.00"),
                Currency.getInstance("BRL"));
        var duplicada = Transacao.criar(
                UUID.fromString("1d7c582f-e11c-4f24-8582-6f16145fa748"),
                new BigDecimal("20.00"),
                Currency.getInstance("USD"));
        var transacoes = new TransactionTemplate(transactionManager);

        transacoes.executeWithoutResult(
                status -> repository.inserir(chaveIdempotencia, original));

        assertThatThrownBy(() -> transacoes.executeWithoutResult(
                status -> repository.inserir(chaveIdempotencia, duplicada)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("idempotencias_transacao_pkey");

        Optional<Transacao> encontrada = transacoes.execute(
                status -> repository.buscarPorChaveIdempotencia(chaveIdempotencia));
        assertThat(encontrada).isPresent();
        assertThat(encontrada.orElseThrow().id()).isEqualTo(original.id());
        Optional<Transacao> transacaoDuplicada = transacoes.execute(
                status -> repository.buscarPorId(duplicada.id()));
        assertThat(transacaoDuplicada).isEmpty();
    }

    @Test
    void banco_deveRejeitarChaveIdempotenteNula() {
        var transacao = Transacao.criar(
                UUID.fromString("0b60a7ab-4d4d-43cf-b422-c752c7526495"),
                new BigDecimal("10.00"),
                Currency.getInstance("BRL"));
        var transacoes = new TransactionTemplate(transactionManager);
        transacoes.executeWithoutResult(status -> repository.inserir(transacao));

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO idempotencias_transacao (chave, transacao_id)
                VALUES (NULL, ?)
                """, transacao.id()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("chave");
    }
}
