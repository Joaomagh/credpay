package br.com.credpay.transacoes.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import br.com.credpay.transacoes.application.ClienteRepository;
import br.com.credpay.transacoes.domain.Cliente;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ClienteJdbcRepository.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class ClienteRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            "postgres@sha256:665dc7b37ac52a17e298511414c21d23ffa4e6a6ef6225b3e5d59c7afb35e994")
            .withDatabaseName("credpay_clientes").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void banco(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private ClienteRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transacoes;

    @Test
    void migrar_deveCriarEstruturaDeClientes_naBaselineAtual() {
        assertThat(jdbc.queryForObject("SELECT to_regclass('public.clientes') IS NOT NULL", Boolean.class)).isTrue();
    }

    @Test
    void inserir_devePreservarClienteAposCommit_eRestringirLeituraAoDono() {
        var conta = conta();
        var outra = conta();
        var cliente = new Cliente(UUID.randomUUID(), conta, "João d’Ávila-Silva");
        var tx = new TransactionTemplate(transacoes);

        tx.executeWithoutResult(status -> repository.inserir(cliente));
        var salvo = tx.execute(status -> repository.buscar(conta, cliente.id()));

        assertThat(salvo).contains(cliente);
        assertThat(repository.buscar(outra, cliente.id())).isEmpty();
        assertThat(repository.buscar(conta, UUID.randomUUID())).isEmpty();
    }

    @Test
    void listar_deveRespeitarCursorDoPostgres_semMisturarContas() {
        var conta = conta();
        var outra = conta();
        var primeiro = new Cliente(UUID.fromString("00000000-0000-4000-8000-000000000001"), conta, "Primeiro");
        var segundo = new Cliente(UUID.fromString("7fffffff-ffff-4fff-8fff-ffffffffffff"), conta, "Segundo");
        var terceiro = new Cliente(UUID.fromString("80000000-0000-4000-8000-000000000000"), conta, "Terceiro");
        var alheio = new Cliente(UUID.fromString("00000000-0000-4000-8000-000000000002"), outra, "Alheio");
        var tx = new TransactionTemplate(transacoes);
        tx.executeWithoutResult(status -> {
            repository.inserir(terceiro);
            repository.inserir(alheio);
            repository.inserir(segundo);
            repository.inserir(primeiro);
        });

        assertThat(repository.listar(conta, 2, null)).containsExactly(primeiro, segundo);
        assertThat(repository.listar(conta, 2, segundo.id())).containsExactly(terceiro);
        assertThat(repository.listar(outra, 2, null)).containsExactly(alheio);
    }

    @Test
    void inserir_deveRecusarIdDuplicado_semSobrescreverClienteOriginal() {
        var conta = conta();
        var cliente = new Cliente(UUID.randomUUID(), conta, "Original");
        var tx = new TransactionTemplate(transacoes);
        tx.executeWithoutResult(status -> repository.inserir(cliente));

        assertThatThrownBy(() -> tx.executeWithoutResult(status ->
                repository.inserir(new Cliente(cliente.id(), conta, "Alterado"))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(repository.buscar(conta, cliente.id())).contains(cliente);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\u00a0", "\u2003", "Cliente\nExemplo", "Cliente\u007f", "Cliente\u0085",
            " Cliente", "Cliente\u202f"})
    void banco_deveRecusarNomeInvalido_quandoDominioForContornado(String nome) {
        var conta = conta();

        assertThatThrownBy(() -> inserirDireto(UUID.randomUUID(), conta, nome))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void banco_deveContarPontosDeCodigo_semTruncarNome() {
        var conta = conta();
        var nome = "\ud801\udc00".repeat(120);
        var cliente = new Cliente(UUID.randomUUID(), conta, nome);
        var tx = new TransactionTemplate(transacoes);
        tx.executeWithoutResult(status -> repository.inserir(cliente));

        assertThat(repository.buscar(conta, cliente.id())).contains(cliente);
        assertThatThrownBy(() -> inserirDireto(UUID.randomUUID(), conta, nome + "a"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void banco_deveExigirContaExistente_quandoDominioForContornado() {
        assertThatThrownBy(() -> inserirDireto(UUID.randomUUID(), UUID.randomUUID(), "Cliente"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> inserirDireto(UUID.randomUUID(), null, "Cliente"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID conta() {
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO contas (id, usuario, senha_hash) VALUES (?, ?, ?)",
                id, "cliente-" + id, "0".repeat(96));
        return id;
    }

    private void inserirDireto(UUID id, UUID conta, String nome) {
        jdbc.update("INSERT INTO clientes (id, conta_id, nome) VALUES (?, ?, ?)", id, conta, nome);
    }
}
