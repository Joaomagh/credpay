package br.com.credpay.transacoes.infrastructure.messaging;

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
import br.com.credpay.transacoes.TransacoesServiceApplication;
import br.com.credpay.transacoes.application.AplicarResultadoService;
import br.com.credpay.transacoes.application.SolicitarEncerramentoService;
import br.com.credpay.transacoes.application.AplicarEncerramentoService;
import tools.jackson.databind.ObjectMapper;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Entrada somente de teste; nenhuma anotação torna esta classe componente da aplicação. */
public final class EncerramentoTransacoesFixture {

    private EncerramentoTransacoesFixture() { }

    public static void main(String[] args) {
        try (var contexto = new SpringApplicationBuilder(TransacoesServiceApplication.class)
                .initializers(context -> {
                    var registro = (BeanDefinitionRegistry) context.getBeanFactory();
                    if (registro.containsBeanDefinition("fixtureSolicitarEncerramentoService")) throw new IllegalStateException();
                    registro.registerBeanDefinition("fixtureSolicitarEncerramentoService", new RootBeanDefinition(SolicitarEncerramentoService.class));
                    if (registro.containsBeanDefinition("fixtureAplicarEncerramentoService")) throw new IllegalStateException();
                    registro.registerBeanDefinition("fixtureAplicarEncerramentoService", new RootBeanDefinition(AplicarEncerramentoService.class));
                }).run(args)) {
            if (!AopUtils.isAopProxy(contexto.getBean(SolicitarEncerramentoService.class))) throw new IllegalStateException();
            if (!AopUtils.isAopProxy(contexto.getBean(AplicarEncerramentoService.class))) throw new IllegalStateException();
            var parser = new EncerramentoTransacaoRespondidoMessageParser(contexto.getBean(ObjectMapper.class));
            if (parser.getClass().getClassLoader() != TransacoesServiceApplication.class.getClassLoader()) {
                throw new IllegalStateException();
            }
            prepararTransporte(contexto);
            if (!AopUtils.isAopProxy(contexto.getBean(AplicarResultadoService.class))) throw new IllegalStateException();
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
        if (partes.length == 4 && "SOLICITAR".equals(partes[0])) {
            contexto.getBean(SolicitarEncerramentoService.class)
                    .executar(uuid(partes[1]), uuid(partes[2]), uuid(partes[3]));
            return;
        }
        if (partes.length == 2 && "PUBLICAR".equals(partes[0])) {
            publicar(contexto, uuid(partes[1]));
            return;
        }
        if (partes.length == 1 && "CONSUMIR".equals(partes[0])) {
            consumir(contexto, leitor, false);
            return;
        }
        if (partes.length == 1 && "CONSUMIR_PAUSADO".equals(partes[0])) {
            consumir(contexto, leitor, true);
            return;
        }
        if (partes.length == 2 && "PUBLICAR_CRIACAO".equals(partes[0])) {
            publicar(contexto, uuid(partes[1]), true);
            return;
        }
        if (partes.length == 1 && "CONSUMIR_FINANCEIRO".equals(partes[0])) {
            consumir(contexto, leitor, false, true);
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
            canal.queueDeclare("credpay.fixture.resposta.v1", true, false, false, Map.of("x-queue-type", "quorum"));
            canal.queueBind("credpay.fixture.resposta.v1", "credpay.fixture.encerramento.v1", "resposta");
            canal.queueDeclare("credpay.fixture.financeiro.v1", true, false, false, Map.of("x-queue-type", "quorum"));
            canal.queueBind("credpay.fixture.financeiro.v1", "credpay.fixture.encerramento.v1", "financeiro");
        }
    }

    private static void publicar(ConfigurableApplicationContext contexto, UUID transactionId) throws Exception {
        publicar(contexto, transactionId, false);
    }

    private static void publicar(ConfigurableApplicationContext contexto, UUID transactionId, boolean fluxoFinanceiro) throws Exception {
        var jdbc = contexto.getBean(JdbcTemplate.class);
        var tabela = fluxoFinanceiro ? "outbox_eventos" : "outbox_pedidos_encerramento";
        var evento = jdbc.queryForMap(
                "SELECT event_id, event_type, payload::text AS payload, published_at FROM " + tabela + " WHERE aggregate_id = ?",
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
        rabbit.send("credpay.fixture.encerramento.v1", fluxoFinanceiro ? "criacao" : "pedido",
                new Message(evento.get("payload").toString().getBytes(StandardCharsets.UTF_8), propriedades), correlacao);
        if (!correlacao.getFuture().get(5, TimeUnit.SECONDS).ack() || correlacao.getReturned() != null) {
            throw new IllegalStateException();
        }
        int alterados = jdbc.update(
                "UPDATE " + tabela + " SET published_at = CURRENT_TIMESTAMP WHERE event_id = ? AND published_at IS NULL",
                evento.get("event_id"));
        if (evento.get("published_at") == null && alterados != 1) throw new IllegalStateException();
    }

    private static void consumir(ConfigurableApplicationContext contexto, InputStreamReader leitor, boolean pausar) throws Exception {
        consumir(contexto, leitor, pausar, false);
    }

    private static void consumir(ConfigurableApplicationContext contexto, InputStreamReader leitor,
            boolean pausar, boolean fluxoFinanceiro) throws Exception {
        try (var conexao = novaConexao(contexto); var canal = conexao.createChannel()) {
            var entrega = canal.basicGet(fluxoFinanceiro ? "credpay.fixture.financeiro.v1" : "credpay.fixture.resposta.v1", false);
            if (entrega == null) throw new IllegalStateException();
            var propriedades = new DefaultMessagePropertiesConverter()
                    .toMessageProperties(entrega.getProps(), entrega.getEnvelope(), StandardCharsets.UTF_8.name());
            var mensagem = new Message(entrega.getBody(), propriedades);
            if (fluxoFinanceiro) {
                var parser = new TransacaoProcessadaMessageParser(contexto.getBean(ObjectMapper.class));
                contexto.getBean(AplicarResultadoService.class).executar(parser.parsear(mensagem));
            } else {
                var parser = new EncerramentoTransacaoRespondidoMessageParser(contexto.getBean(ObjectMapper.class));
                contexto.getBean(AplicarEncerramentoService.class).executar(parser.parsear(mensagem));
            }
            // O proxy retornou: commit concluído antes de confirmar a entrega.
            if (pausar) {
                System.out.println("CREDPAY_FIXTURE COMMITTED");
                if (!"ACK".equals(lerLimitado(leitor))) throw new IllegalArgumentException();
            }
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
