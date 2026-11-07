package br.com.credpay.transacoes.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.Duration;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class PostgresRuntimeTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            "postgres@sha256:665dc7b37ac52a17e298511414c21d23ffa4e6a6ef6225b3e5d59c7afb35e994")
            .withDatabaseName("credpay_test")
            .withUsername("test")
            .withPassword("test")
            .withStartupTimeout(Duration.ofSeconds(60));

    @ParameterizedTest
    @ValueSource(strings = {"10.00", "123.456"})
    void consultar_devePreservarDecimal_quandoPostgresDaBaselineEstiverDisponivel(String quantia)
            throws Exception {
        var valor = new BigDecimal(quantia);

        try (var conexao = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var consulta = conexao.prepareStatement(
                        "SELECT current_setting('server_version_num')::integer AS versao, CAST(? AS numeric) AS valor")) {
            consulta.setQueryTimeout(10);
            consulta.setBigDecimal(1, valor);

            try (var resultado = consulta.executeQuery()) {
                assertThat(resultado.next()).isTrue();
                assertThat(resultado.getInt("versao")).isEqualTo(170011);
                assertThat(resultado.getBigDecimal("valor")).isEqualTo(valor);
                assertThat(resultado.getBigDecimal("valor").scale()).isEqualTo(valor.scale());
                assertThat(resultado.next()).isFalse();
            }
        }
    }
}
