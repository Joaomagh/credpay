package br.com.credpay.transacoes.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;

import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.ConnectionFactoryConfigurator;
import org.junit.jupiter.api.Test;

class ConfiguracaoClienteRabbitTest {

    @Test
    void naoDeveExporCredenciaisNaFalhaDeConfiguracaoIncluindoCausas() {
        var uriFicticia = "amqp://fixture-user:fixture password@broker.invalid/demo";
        var erro = catchThrowable(() -> ConnectionFactoryConfigurator.load(
                new ConnectionFactory(), Map.of("uri", uriFicticia), ""));

        assertThat(erro).isInstanceOf(IllegalArgumentException.class);
        var texto = new StringWriter();
        erro.printStackTrace(new PrintWriter(texto));
        assertThat(texto.toString()).doesNotContain("fixture-user", "fixture password", uriFicticia);
    }
}
