package br.com.credpay.processamento.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.support.ListenerExecutionFailedException;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;

class TransacaoCriadaRetryConfigurationTest {

    @TestConfiguration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = TransacaoCriadaListener.class, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                    pattern = "br\\.com\\.credpay\\.processamento\\.infrastructure\\.messaging\\.TransacaoCriadaRetryConfiguration"))
    static class RetryScan {
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class))
            .withBean(ConnectionFactory.class, () -> mock(ConnectionFactory.class))
            .withUserConfiguration(RetryScan.class);

    @Test
    void consumir_deveRecuperarNaTerceiraTentativa_comEsperasDeUmEDoisSegundos() {
        habilitado().run(contexto -> {
            var tentativas = new AtomicInteger();
            var consumidor = comRetry(contexto, mensagem -> {
                if (tentativas.incrementAndGet() < 3) {
                    throw new DataAccessResourceFailureException("falha controlada de teste");
                }
            });
            var inicio = System.nanoTime();

            assertThatCode(() -> consumidor.accept(mensagem())).doesNotThrowAnyException();

            assertThat(tentativas).hasValue(3);
            assertThat(System.nanoTime() - inicio).isGreaterThanOrEqualTo(TimeUnit.SECONDS.toNanos(3));
        });
    }

    @Test
    void consumir_deveRejeitarSemDetalhesInternos_quandoTresTentativasFalharem() {
        habilitado().run(contexto -> {
            var tentativas = new AtomicInteger();
            var consumidor = comRetry(contexto, mensagem -> {
                tentativas.incrementAndGet();
                throw new DataAccessResourceFailureException("diagnostico-interno-nao-publicavel");
            });

            assertThatThrownBy(() -> consumidor.accept(mensagem()))
                    .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                    .hasMessage("TransacaoCriada com falha operacional apos 3 tentativas")
                    .hasNoCause();
            assertThat(tentativas).hasValue(3);
        });
    }

    @Test
    void consumir_devePreservarRejeicaoSemRetry_quandoFalhaPermanenteEstiverEnvelopada() {
        habilitado().run(contexto -> {
            var tentativas = new AtomicInteger();
            var permanente = new AmqpRejectAndDontRequeueException("diagnostico permanente seguro");
            var consumidor = comRetry(contexto, mensagem -> {
                tentativas.incrementAndGet();
                throw new ListenerExecutionFailedException("falha de teste", permanente, mensagem);
            });

            assertThatThrownBy(() -> consumidor.accept(mensagem())).isSameAs(permanente);
            assertThat(tentativas).hasValue(1);
        });
    }

    @Test
    void contexto_deveManterRetryDesabilitado_porPadraoOuSomenteComTopologia() {
        contextRunner.run(contexto -> assertThat(contexto)
                .doesNotHaveBean("transacaoCriadaRetryInterceptor")
                .doesNotHaveBean("transacaoCriadaListenerContainerFactory"));
        contextRunner.withPropertyValues("credpay.processamento.consumer.topology.enabled=true")
                .run(contexto -> assertThat(contexto)
                        .doesNotHaveBean("transacaoCriadaRetryInterceptor")
                        .doesNotHaveBean("transacaoCriadaListenerContainerFactory"));
        contextRunner.withPropertyValues("credpay.processamento.consumer.listener.enabled=true")
                .run(contexto -> assertThat(contexto)
                        .doesNotHaveBean("transacaoCriadaRetryInterceptor")
                        .doesNotHaveBean("transacaoCriadaListenerContainerFactory"));
    }

    private ApplicationContextRunner habilitado() {
        return contextRunner.withPropertyValues(
                "credpay.processamento.consumer.topology.enabled=true",
                "credpay.processamento.consumer.listener.enabled=true");
    }

    @SuppressWarnings("unchecked")
    private Consumer<Message> comRetry(ApplicationContext contexto, Consumer<Message> trabalho) {
        var proxy = new ProxyFactory();
        proxy.setInterfaces(BiConsumer.class);
        BiConsumer<Object, Message> invocacaoContainer = (canal, mensagem) -> trabalho.accept(mensagem);
        proxy.setTarget(invocacaoContainer);
        contexto.getBeansOfType(RetryOperationsInterceptor.class).values().forEach(proxy::addAdvice);
        var consumidor = (BiConsumer<Object, Message>) proxy.getProxy();
        return mensagem -> consumidor.accept(null, mensagem);
    }

    private Message mensagem() {
        return new Message(new byte[0], new MessageProperties());
    }
}
