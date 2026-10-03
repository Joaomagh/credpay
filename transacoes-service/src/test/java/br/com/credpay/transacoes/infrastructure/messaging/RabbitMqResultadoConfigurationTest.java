package br.com.credpay.transacoes.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RabbitMqResultadoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RabbitMqResultadoConfiguration.class);

    @Test
    void topologia_devePermanecerDesligadaPorPadraoEOpcaoExplicita() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(Queue.class).doesNotHaveBean(Binding.class)
                    .doesNotHaveBean(DirectExchange.class);
        });
        runner.withPropertyValues("credpay.transacoes.consumer.topology.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(Queue.class).doesNotHaveBean(Binding.class)
                    .doesNotHaveBean(DirectExchange.class);
        });
    }

    @Test
    void topologia_deveDeclararSomenteRecursosPropriosQuandoHabilitada() {
        runner.withPropertyValues("credpay.transacoes.consumer.topology.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            var queues = context.getBeansOfType(Queue.class);
            assertThat(queues).hasSize(2);
            assertThat(queues.values()).allSatisfy(queue -> {
                assertThat(queue.isDurable()).isTrue();
                assertThat(queue.isExclusive()).isFalse();
                assertThat(queue.isAutoDelete()).isFalse();
                assertThat(queue.getArguments()).containsOnlyKeys("x-queue-type").containsEntry("x-queue-type", "quorum");
            });
            assertThat(queues.values()).extracting(Queue::getName).containsExactlyInAnyOrder(
                    "credpay.transacoes.transacao-processada.v1", "credpay.transacoes.transacao-processada.dlq.v1");
            var exchanges = context.getBeansOfType(DirectExchange.class);
            assertThat(exchanges).hasSize(1);
            assertThat(exchanges.values()).allSatisfy(exchange -> {
                assertThat(exchange.getName()).isEqualTo("credpay.transacoes.dlx.v1");
                assertThat(exchange.isDurable()).isTrue();
                assertThat(exchange.isAutoDelete()).isFalse();
            });
            var bindings = context.getBeansOfType(Binding.class);
            assertThat(bindings).hasSize(2);
            var entrada = bindings.get("transacaoProcessadaBinding");
            assertThat(entrada.getExchange()).isEqualTo("credpay.processamento.v1");
            assertThat(entrada.getDestination()).isEqualTo("credpay.transacoes.transacao-processada.v1");
            assertThat(entrada.getDestinationType()).isEqualTo(Binding.DestinationType.QUEUE);
            assertThat(entrada.getRoutingKey()).isEqualTo("transacao.processada.v1");
            var dlq = bindings.get("transacaoProcessadaDlqBinding");
            assertThat(dlq.getExchange()).isEqualTo("credpay.transacoes.dlx.v1");
            assertThat(dlq.getDestination()).isEqualTo("credpay.transacoes.transacao-processada.dlq.v1");
            assertThat(dlq.getDestinationType()).isEqualTo(Binding.DestinationType.QUEUE);
            assertThat(dlq.getRoutingKey()).isEqualTo("transacao.processada.dlq.v1");
        });
    }
}
