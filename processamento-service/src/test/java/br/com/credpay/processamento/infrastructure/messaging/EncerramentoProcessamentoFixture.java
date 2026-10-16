package br.com.credpay.processamento.infrastructure.messaging;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import br.com.credpay.processamento.ProcessamentoServiceApplication;
import br.com.credpay.processamento.application.RegistrarEncerramentoService;
import tools.jackson.databind.ObjectMapper;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Entrada somente de teste; nenhuma anotação torna esta classe componente da aplicação. */
public final class EncerramentoProcessamentoFixture {

    private EncerramentoProcessamentoFixture() { }

    public static void main(String[] args) {
        try (var contexto = new SpringApplicationBuilder(ProcessamentoServiceApplication.class)
                .initializers(context -> {
                    var registro = (BeanDefinitionRegistry) context.getBeanFactory();
                    if (registro.containsBeanDefinition("fixtureRegistrarEncerramentoService")) throw new IllegalStateException();
                    registro.registerBeanDefinition("fixtureRegistrarEncerramentoService", new RootBeanDefinition(RegistrarEncerramentoService.class));
                }).run(args)) {
            if (!AopUtils.isAopProxy(contexto.getBean(RegistrarEncerramentoService.class))) throw new IllegalStateException();
            var parser = new EncerramentoTransacaoSolicitadoMessageParser(contexto.getBean(ObjectMapper.class));
            if (parser.getClass().getClassLoader() != ProcessamentoServiceApplication.class.getClassLoader()) {
                throw new IllegalStateException();
            }
            prepararTransporte(contexto);
            System.out.println("CREDPAY_FIXTURE READY");
            var leitor = new InputStreamReader(System.in, StandardCharsets.UTF_8);
            String linha;
            while ((linha = lerLimitado(leitor)) != null) {
                executar(contexto, linha, leitor);
                System.out.println("CREDPAY_FIXTURE DONE");
            }
        } catch (Exception | AssertionError falha) {
            System.err.println("CREDPAY_FIXTURE FAILURE");
            System.exit(2);
        }
    }

    private static void executar(ConfigurableApplicationContext contexto, String linha, InputStreamReader leitor) throws Exception {
        var partes = linha.split(" ", -1);

        if (partes.length == 2 && "PUBLICAR".equals(partes[0])) {
            publicar(contexto, uuid(partes[1]));
            return;
        }
        if (partes.length == 1 && "CONSUMIR".equals(partes[0])) {
            consumir(contexto);
            return;
        }
        if (partes.length == 1 && "CONSUMIR_PAUSADO".equals(partes[0])) {
            // Scaffolding do red: o consumo atual ainda confirma antes da janela observável.
            consumir(contexto);
            System.out.println("CREDPAY_FIXTURE COMMITTED");
            if (!"ACK".equals(lerLimitado(leitor))) throw new IllegalArgumentException();
            return;
        }
        throw new IllegalArgumentException();
    }

    private static com.rabbitmq.client.Connection novaConexao(ConfigurableApplicationContext contexto) throws Exception {
        var factory = contexto.getBean(CachingConnectionFactory.class).getRabbitConnectionFactory();
        return factory.newConnection("encerramento-fixture");
    }

    private static void prepararTransporte(ConfigurableApplicationContext contexto) throws Exception {
        try (var conexao = novaConexao(contexto); var canal = conexao.createChannel()) {
            canal.exchangeDeclare("credpay.fixture.encerramento.v1", "direct", true);
            canal.queueDeclare("credpay.fixture.pedido.v1", true, false, false, Map.of("x-queue-type", "quorum"));
            canal.queueBind("credpay.fixture.pedido.v1", "credpay.fixture.encerramento.v1", "pedido");
        }
    }

    private static void publicar(ConfigurableApplicationContext contexto, UUID transactionId) throws Exception {
        var jdbc = contexto.getBean(JdbcTemplate.class);
        var evento = jdbc.queryForMap(
                "SELECT event_id, event_type, payload::text AS payload, published_at FROM outbox_encerramentos WHERE aggregate_id = ?",
                transactionId);
        var propriedades = new MessageProperties();
        propriedades.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        propriedades.setContentEncoding(StandardCharsets.UTF_8.name());
        propriedades.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        propriedades.setMessageId(evento.get("event_id").toString());
        propriedades.setType(evento.get("event_type").toString());
        propriedades.setCorrelationId(transactionId.toString());
        var correlacao = new CorrelationData(evento.get("event_id").toString());
        var rabbit = contexto.getBean(RabbitTemplate.class);
        rabbit.setMandatory(true);
        rabbit.send("credpay.fixture.encerramento.v1", "resposta",
                new Message(evento.get("payload").toString().getBytes(StandardCharsets.UTF_8), propriedades), correlacao);
        if (!correlacao.getFuture().get(5, TimeUnit.SECONDS).ack() || correlacao.getReturned() != null) {
            throw new IllegalStateException();
        }
        int alterados = jdbc.update(
                "UPDATE outbox_encerramentos SET published_at = CURRENT_TIMESTAMP WHERE event_id = ? AND published_at IS NULL",
                evento.get("event_id"));
        if (evento.get("published_at") == null && alterados != 1) throw new IllegalStateException();
    }

    private static void consumir(ConfigurableApplicationContext contexto) throws Exception {
        try (var conexao = novaConexao(contexto); var canal = conexao.createChannel()) {
            var entrega = canal.basicGet("credpay.fixture.pedido.v1", false);
            if (entrega == null) throw new IllegalStateException();
            var propriedades = new DefaultMessagePropertiesConverter()
                    .toMessageProperties(entrega.getProps(), entrega.getEnvelope(), StandardCharsets.UTF_8.name());
            var mensagem = new Message(entrega.getBody(), propriedades);
            var parser = new EncerramentoTransacaoSolicitadoMessageParser(contexto.getBean(ObjectMapper.class));
            contexto.getBean(RegistrarEncerramentoService.class).executar(parser.parsear(mensagem));
            // O proxy retornou: commit concluído antes de confirmar a entrega.
            canal.basicAck(entrega.getEnvelope().getDeliveryTag(), false);
        }
    }

    private static UUID uuid(String texto) {
        var id = UUID.fromString(texto);
        if (!id.toString().equals(texto)) throw new IllegalArgumentException();
        return id;
    }

    private static String lerLimitado(InputStreamReader leitor) throws java.io.IOException {
        var linha = new StringBuilder();
        int caractere;
        while ((caractere = leitor.read()) != -1) {
            if (caractere == '\n') return linha.toString();
            if (caractere == '\r' || linha.length() >= 160) throw new IllegalArgumentException();
            linha.append((char) caractere);
        }
        if (!linha.isEmpty()) throw new IllegalArgumentException();
        return null;
    }
}
