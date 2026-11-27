package br.com.credpay.transacoes.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.Locale;
import java.util.UUID;

import br.com.credpay.transacoes.application.VinculosIdentidadeExterna;
import br.com.credpay.transacoes.domain.IdentidadeExterna;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
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
@Import(VinculosIdentidadeExternaJdbc.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class VinculosIdentidadeExternaIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            "postgres@sha256:665dc7b37ac52a17e298511414c21d23ffa4e6a6ef6225b3e5d59c7afb35e994")
            .withDatabaseName("credpay_identidades").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void banco(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private VinculosIdentidadeExterna vinculos;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transacoes;

    @Test
    void buscarConta_devePreservarVinculoAposCommit_eUsarOParExato() {
        var conta = conta();
        var identidade = identidade();
        assertThatCode(() -> new TransactionTemplate(transacoes).executeWithoutResult(
                status -> inserir(identidade, conta))).doesNotThrowAnyException();

        var salvo = new TransactionTemplate(transacoes).execute(status -> vinculos.buscarConta(identidade));
        assertThat(salvo).contains(conta);
        assertThat(vinculos.buscarConta(new IdentidadeExterna("https://outro.example", identidade.sujeito())))
                .isEmpty();
        assertThat(vinculos.buscarConta(new IdentidadeExterna(
                identidade.emissor(), identidade.sujeito().toUpperCase(Locale.ROOT))))
                .isEmpty();
    }

    @Test
    void banco_deveRecusarTrocaDeContaParaAMesmaIdentidade() {
        var original = conta();
        var outra = conta();
        var identidade = identidade();
        assertThatCode(() -> inserir(identidade, original)).doesNotThrowAnyException();

        assertThatThrownBy(() -> inserir(identidade, outra)).satisfies(erro ->
                assertThat(((SQLException) causaRaiz(erro)).getSQLState()).isEqualTo("23505"));
        assertThat(vinculos.buscarConta(identidade)).contains(original);
    }

    @Test
    void banco_devePermitirMesmoSujeitoEmEmissoresDiferentes_semMisturarContas() {
        var primeira = conta();
        var segunda = conta();
        var identidade = identidade();
        var outra = new IdentidadeExterna("https://outro.example", identidade.sujeito());
        assertThatCode(() -> inserir(identidade, primeira)).doesNotThrowAnyException();
        assertThatCode(() -> inserir(outra, segunda)).doesNotThrowAnyException();

        assertThat(vinculos.buscarConta(identidade)).contains(primeira);
        assertThat(vinculos.buscarConta(outra)).contains(segunda);
    }

    @Test
    void banco_deveExigirContaExistente() {
        assertThatThrownBy(() -> inserir(identidade(), UUID.randomUUID())).satisfies(erro ->
                assertThat(((SQLException) causaRaiz(erro)).getSQLState()).isEqualTo("23503"));
    }

    @Test
    void banco_deveRecusarCamposAusentes_semCriarVinculosParciais() {
        var conta = conta();
        for (var ausente : new String[] {null, "", " "}) {
            var estado = ausente == null ? "23502" : "23514";
            assertThatThrownBy(() -> inserirDireto(ausente, "sujeito", conta)).satisfies(erro ->
                    assertThat(((SQLException) causaRaiz(erro)).getSQLState()).isEqualTo(estado));
            assertThatThrownBy(() -> inserirDireto("https://accounts.google.com", ausente, conta)).satisfies(erro ->
                    assertThat(((SQLException) causaRaiz(erro)).getSQLState()).isEqualTo(estado));
        }
        assertThatThrownBy(() -> inserirDireto("https://accounts.google.com", "sujeito", null)).satisfies(erro ->
                assertThat(((SQLException) causaRaiz(erro)).getSQLState()).isEqualTo("23502"));
    }

    private UUID conta() {
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO contas (id, usuario, senha_hash) VALUES (?, ?, ?)",
                id, "identidade-" + id, "0".repeat(96));
        return id;
    }

    private IdentidadeExterna identidade() {
        return new IdentidadeExterna("https://accounts.google.com", "sub-" + UUID.randomUUID());
    }

    private void inserir(IdentidadeExterna identidade, UUID conta) {
        inserirDireto(identidade.emissor(), identidade.sujeito(), conta);
    }

    private void inserirDireto(String emissor, String sujeito, UUID conta) {
        jdbc.update("INSERT INTO identidades_externas (emissor, sujeito, conta_id) VALUES (?, ?, ?)",
                emissor, sujeito, conta);
    }

    private static Throwable causaRaiz(Throwable erro) {
        var causa = erro;
        while (causa.getCause() != null) { causa = causa.getCause(); }
        return causa;
    }
}
