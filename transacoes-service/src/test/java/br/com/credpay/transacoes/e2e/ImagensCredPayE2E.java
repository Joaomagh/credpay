package br.com.credpay.transacoes.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

class ImagensCredPayE2E {

    private static final String POSTGRES_IMAGE = "postgres@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0";
    private static final String RABBIT_IMAGE = "rabbitmq:4.3.5-management-alpine@sha256:b3b8b7f95f5382a19f9ea33540e604f30aad081d37ad9aba72255135765373a1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final Path RAIZ = Path.of("..").toAbsolutePath().normalize();
    private static final List<String> FILAS = List.of("credpay.processamento.transacao-criada.v1",
            "credpay.processamento.transacao-criada.dlq.v1", "credpay.transacoes.transacao-processada.v1",
            "credpay.transacoes.transacao-processada.dlq.v1");

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void imagens_deveConcluirTransacoesEConservarReplay_quandoPreparadasPorFases() throws Exception {
        var tag = System.getenv("CREDPAY_IMAGE_TAG");
        assertThat(tag).as("tag das duas imagens locais: SHA completo do checkout").matches("[0-9a-f]{40}");

        try (var rede = Network.newNetwork();
             var bancoTransacoes = banco(rede, "db-transacoes", "credpay_transacoes_imagens");
             var bancoProcessamento = banco(rede, "db-processamento", "credpay_processamento_imagens");
             var broker = new RabbitMQContainer(DockerImageName.parse(RABBIT_IMAGE).asCompatibleSubstituteFor("rabbitmq"))
                     .withAdminUser("credpay_test").withAdminPassword("test")
                     .withNetwork(rede).withNetworkAliases("rabbit")
                     .withCopyFileToContainer(MountableFile.forHostPath(RAIZ.resolve("infra/rabbitmq/processamento-policies.json")),
                             "/tmp/processamento-policies.json")
                     .withCopyFileToContainer(MountableFile.forHostPath(RAIZ.resolve("infra/rabbitmq/transacoes-policies.json")),
                             "/tmp/transacoes-policies.json")) {
            bancoTransacoes.start();
            bancoProcessamento.start();
            broker.start();

            try (var transacoes = aplicativo("transacoes", tag, rede, "db-transacoes", bancoTransacoes, broker, false, false);
                 var processamento = aplicativo("processamento", tag, rede, "db-processamento", bancoProcessamento, broker, false, false)) {
                iniciarEConferirAplicativos(transacoes, processamento);
                var exchanges = consultar(broker, "list_exchanges", "name", "type", "durable");
                conferirExchange(exchanges, "credpay.transacoes.v1");
                conferirExchange(exchanges, "credpay.processamento.v1");
                assertThat(consultar(broker, "list_queues", "name")).as("topologias desligadas").isEmpty();
                assertThat(consultar(broker, "list_consumers")).as("consumo desligado").isEmpty();
                System.out.println("ImagensCredPayE2E: fase1 exchanges produtoras, filas=0, consumidores=0");
            }

            try (var transacoes = aplicativo("transacoes", tag, rede, "db-transacoes", bancoTransacoes, broker, true, false);
                 var processamento = aplicativo("processamento", tag, rede, "db-processamento", bancoProcessamento, broker, true, false)) {
                iniciarEConferirAplicativos(transacoes, processamento);
                importar(broker, "processamento");
                importar(broker, "transacoes");
                conferirPreparacao(broker);
                assertThat(consultar(broker, "list_consumers")).as("políticas prontas antes de consumo").isEmpty();
                System.out.println("ImagensCredPayE2E: fase2 filas quorum=4, políticas/bindings efetivos, consumidores=0");
            }

            try (var transacoes = aplicativo("transacoes", tag, rede, "db-transacoes", bancoTransacoes, broker, true, true);
                 var processamento = aplicativo("processamento", tag, rede, "db-processamento", bancoProcessamento, broker, true, true)) {
                iniciarEConferirAplicativos(transacoes, processamento);
                conferirConsumidores(broker);
                conferirBancosProprios(bancoTransacoes, bancoProcessamento);
                System.out.println("ImagensCredPayE2E: fase3 consumidores=2, ack obrigatório, prefetch=10/1, bancos próprios");
                var dadosTransacoes = jdbc(bancoTransacoes);
                var dadosProcessamento = jdbc(bancoProcessamento);
                conferirFluxo(transacoes, broker, dadosTransacoes, dadosProcessamento, "50.000", "APROVADA");
                conferirFluxo(transacoes, broker, dadosTransacoes, dadosProcessamento, "150.000", "REJEITADA");
            }
        }
    }

    private PostgreSQLContainer<?> banco(Network rede, String alias, String nome) {
        return new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName(nome).withUsername("test").withPassword("test")
                .withNetwork(rede).withNetworkAliases(alias);
    }

    private GenericContainer<?> aplicativo(String servico, String tag, Network rede, String aliasBanco,
                                           PostgreSQLContainer<?> banco, RabbitMQContainer broker, boolean topologia, boolean consumir) {
        return new GenericContainer<>(DockerImageName.parse("credpay-" + servico + ":" + tag))
                .withImagePullPolicy(imagem -> false)
                .withNetwork(rede).withExposedPorts(8080)
                .withEnv("SPRING_DATASOURCE_URL", "jdbc:postgresql://" + aliasBanco + ":5432/" + banco.getDatabaseName())
                .withEnv("SPRING_DATASOURCE_USERNAME", banco.getUsername())
                .withEnv("SPRING_DATASOURCE_PASSWORD", banco.getPassword())
                .withEnv("SPRING_RABBITMQ_HOST", "rabbit")
                .withEnv("SPRING_RABBITMQ_PORT", "5672")
                .withEnv("SPRING_RABBITMQ_USERNAME", broker.getAdminUsername())
                .withEnv("SPRING_RABBITMQ_PASSWORD", broker.getAdminPassword())
                .withEnv("SPRING_RABBITMQ_VIRTUAL_HOST", "/")
                .withCommand("--server.port=8080", "--management.health.rabbit.enabled=true",
                        "--credpay." + servico + ".consumer.topology.enabled=" + topologia,
                        "--credpay." + servico + ".consumer.listener.enabled=" + consumir,
                        "--credpay.outbox.publisher.enabled=" + consumir, "--credpay.processamento.limites.BRL=100.00")
                .waitingFor(Wait.forHttp("/actuator/health").forStatusCode(200)
                        .forResponsePredicate(this::healthUp).withStartupTimeout(Duration.ofSeconds(90)));
    }

    private void iniciarEConferirAplicativos(GenericContainer<?> transacoes, GenericContainer<?> processamento) throws Exception {
        transacoes.start();
        processamento.start();
        conferirUsuarioEMounts(transacoes, "transacoes");
        conferirUsuarioEMounts(processamento, "processamento");
    }

    private boolean healthUp(String corpo) {
        try {
            return "UP".equals(JSON.readTree(corpo).path("status").asText());
        } catch (Exception respostaInvalida) {
            return false;
        }
    }

    private void conferirUsuarioEMounts(GenericContainer<?> aplicativo, String servico) throws Exception {
        var usuario = aplicativo.execInContainer("id", "-u");
        assertThat(usuario.getExitCode()).as("id -u: " + servico).isZero();
        assertThat(usuario.getStdout().strip()).as("UID efetivo: " + servico).isEqualTo("10001");
        var info = aplicativo.getContainerInfo();
        assertThat(info.getConfig().getUser()).as("usuário configurado: " + servico).isEqualTo("10001:10001");
        assertThat(info.getMounts()).as("nenhum mount no aplicativo: " + servico).isEmpty();
        assertThat(info.getHostConfig().getPrivileged()).as("sem modo privilegiado: " + servico).isFalse();
        System.out.println("ImagensCredPayE2E: " + servico + " uid=10001, mounts=0, privileged=false, health=UP");
    }

    private JsonNode consultar(RabbitMQContainer broker, String comando, String... campos) throws Exception {
        var argumentos = new ArrayList<>(List.of("rabbitmqctl", "--timeout", "5", "--quiet", "--formatter=json", comando));
        argumentos.addAll(List.of(campos));
        var resultado = broker.execInContainer(argumentos.toArray(String[]::new));
        assertThat(resultado.getExitCode()).as("consulta do broker: " + comando).isZero();
        var linhas = JSON.readTree(resultado.getStdout());
        assertThat(linhas).as("JSON do broker: " + comando).isNotNull();
        assertThat(linhas.isArray()).as("array do broker: " + comando).isTrue();
        return linhas;
    }

    private void conferirExchange(JsonNode exchanges, String nome) {
        var exchange = porNome(exchanges, nome);
        assertThat(exchange.path("type").asText()).isEqualTo("direct");
        assertThat(exchange.path("durable").asBoolean()).isTrue();
    }

    private JsonNode porNome(JsonNode linhas, String nome) {
        var encontradas = StreamSupport.stream(linhas.spliterator(), false)
                .filter(linha -> nome.equals(linha.path("name").asText())).toList();
        assertThat(encontradas).as("recurso exato: " + nome).hasSize(1);
        return encontradas.getFirst();
    }

    private void importar(RabbitMQContainer broker, String servico) throws Exception {
        var resultado = broker.execInContainer("rabbitmqctl", "--timeout", "5", "import_definitions", "/tmp/" + servico + "-policies.json");
        assertThat(resultado.getExitCode()).as("importação das políticas: " + servico).isZero();
    }

    private void conferirPreparacao(RabbitMQContainer broker) throws Exception {
        assertThat(porNome(consultar(broker, "list_feature_flags", "name", "state"), "stream_queue").path("state").asText()).isEqualTo("enabled");
        var filas = consultar(broker, "list_queues", "name", "type", "durable", "arguments", "policy", "operator_policy", "effective_policy_definition");
        assertThat(filas).as("duas entradas e duas DLQs").hasSize(4);
        var servicos = List.of("processamento", "transacoes");
        for (int modulo = 0; modulo < servicos.size(); modulo++) {
            var politicas = JSON.readTree(Files.readString(RAIZ.resolve("infra/rabbitmq/" + servicos.get(modulo) + "-policies.json"))).path("policies");
            assertThat(politicas.isArray()).isTrue();
            assertThat(politicas).hasSize(2);
            for (int indice = 0; indice < 2; indice++) {
                var fila = porNome(filas, FILAS.get(modulo * 2 + indice));
                assertThat(fila.path("type").asText()).isEqualTo("quorum");
                assertThat(fila.path("durable").asBoolean()).isTrue();
                assertThat(fila.path("arguments")).isEqualTo(JSON.readTree("[[\"x-queue-type\",\"longstr\",\"quorum\"]]"));
                assertThat(fila.path("policy").asText()).isEqualTo(politicas.get(indice).path("name").asText());
                assertThat(fila.path("operator_policy").asText()).isEmpty();
                assertThat(fila.path("effective_policy_definition")).isEqualTo(politicas.get(indice).path("definition"));
            }
        }
        var exchanges = consultar(broker, "list_exchanges", "name", "type", "durable");
        for (var nome : List.of("credpay.transacoes.v1", "credpay.processamento.v1", "credpay.transacoes.dlx.v1", "credpay.processamento.dlx.v1")) {
            conferirExchange(exchanges, nome);
        }
        var bindings = consultar(broker, "list_bindings", "source_name", "destination_name", "destination_kind", "routing_key");
        conferirBinding(bindings, "credpay.transacoes.v1", FILAS.get(0), "transacao.criada.v1");
        conferirBinding(bindings, "credpay.processamento.dlx.v1", FILAS.get(1), "transacao.criada.dlq.v1");
        conferirBinding(bindings, "credpay.processamento.v1", FILAS.get(2), "transacao.processada.v1");
        conferirBinding(bindings, "credpay.transacoes.dlx.v1", FILAS.get(3), "transacao.processada.dlq.v1");
    }

    private void conferirBinding(JsonNode bindings, String origem, String destino, String rota) {
        var quantidade = StreamSupport.stream(bindings.spliterator(), false)
                .filter(linha -> origem.equals(linha.path("source_name").asText())
                        && destino.equals(linha.path("destination_name").asText())
                        && "queue".equals(linha.path("destination_kind").asText())
                        && rota.equals(linha.path("routing_key").asText())).count();
        assertThat(quantidade).as("binding: " + destino).isEqualTo(1);
    }

    private void conferirConsumidores(RabbitMQContainer broker) throws Exception {
        var prazo = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        JsonNode consumidores;
        do {
            consumidores = consultar(broker, "list_consumers");
            var observados = consumidores;
            boolean exatos = observados.size() == 2 && List.of(0, 2).stream().allMatch(indice ->
                    StreamSupport.stream(observados.spliterator(), false).filter(linha ->
                            FILAS.get(indice).equals(linha.path("queue_name").asText())
                            && linha.path("ack_required").asBoolean()
                            && linha.path("prefetch_count").asInt() == (indice == 0 ? 10 : 1)).count() == 1);
            if (exatos) break;
            TimeUnit.MILLISECONDS.sleep(200);
        } while (System.nanoTime() < prazo);
        assertThat(consumidores).as("exatamente duas entradas, nenhum consumidor de DLQ").hasSize(2);
        for (int indice : List.of(0, 2)) {
            var encontrados = StreamSupport.stream(consumidores.spliterator(), false)
                    .filter(linha -> FILAS.get(indice).equals(linha.path("queue_name").asText())).toList();
            assertThat(encontrados).hasSize(1);
            assertThat(encontrados.getFirst().path("ack_required").asBoolean()).isTrue();
            assertThat(encontrados.getFirst().path("prefetch_count").asInt()).isEqualTo(indice == 0 ? 10 : 1);
        }
    }

    private void conferirBancosProprios(PostgreSQLContainer<?> bancoTransacoes, PostgreSQLContainer<?> bancoProcessamento) {
        var transacoes = jdbc(bancoTransacoes);
        var processamento = jdbc(bancoProcessamento);
        assertThat(transacoes.queryForObject("SELECT to_regclass('public.transacoes')", String.class)).isEqualTo("transacoes");
        assertThat(transacoes.queryForObject("SELECT to_regclass('public.processamentos')", String.class)).isNull();
        assertThat(processamento.queryForObject("SELECT to_regclass('public.processamentos')", String.class)).isEqualTo("processamentos");
        assertThat(processamento.queryForObject("SELECT to_regclass('public.transacoes')", String.class)).isNull();
    }

    private JdbcTemplate jdbc(PostgreSQLContainer<?> banco) {
        return new JdbcTemplate(new DriverManagerDataSource(banco.getJdbcUrl(), banco.getUsername(), banco.getPassword()));
    }

    private void conferirFluxo(GenericContainer<?> aplicativo, RabbitMQContainer broker, JdbcTemplate transacoes,
                                JdbcTemplate processamento, String valor, String status) throws Exception {
        aguardarFilasVazias(broker);
        var chave = UUID.randomUUID();
        var resposta = post(aplicativo, chave, valor);
        assertThat(resposta.statusCode()).withFailMessage("POST não retornou201").isEqualTo(201);
        var original = lerJson(resposta.body());
        assertThat(original.path("status").asText()).withFailMessage("POST original não é PENDENTE").isEqualTo("PENDENTE");
        var id = UUID.fromString(original.path("id").asText());
        var location = resposta.headers().firstValue("Location").orElseThrow();
        assertThat(location).withFailMessage("Location não referencia o UUID original").isEqualTo("/transacoes/" + id);
        var finalizada = aguardarFinal(aplicativo, location, status);
        assertThat(finalizada.path("id").asText()).withFailMessage("GET alterou identidade").isEqualTo(id.toString());
        assertThat(finalizada.path("valor").decimalValue()).withFailMessage("GET alterou valor").isEqualByComparingTo(new BigDecimal(valor));
        assertThat(finalizada.path("moeda").asText()).withFailMessage("GET alterou moeda").isEqualTo("BRL");
        aguardarPublicacoes(transacoes, processamento, id);

        var entrada = transacoes.queryForMap("SELECT * FROM outbox_eventos WHERE aggregate_id = ?", id);
        var registro = processamento.queryForMap("SELECT * FROM processamentos WHERE transaction_id = ?", id);
        var saida = processamento.queryForMap("SELECT * FROM outbox_eventos WHERE aggregate_id = ?", id);
        var historico = transacoes.queryForMap("SELECT * FROM historico_transacoes WHERE transaction_id = ?", id);
        assertThat(registro.get("event_id")).withFailMessage("decisão perdeu identidade da entrada").isEqualTo(entrada.get("event_id"));
        assertThat(registro.get("output_event_id")).withFailMessage("outbox perdeu identidade da saída").isEqualTo(saida.get("event_id"));
        assertThat(registro.get("correlation_id")).withFailMessage("decisão perdeu correlação").isEqualTo(id);
        assertThat(registro.get("resultado")).withFailMessage("decisão divergente").isEqualTo(status);
        assertThat((BigDecimal) registro.get("valor")).withFailMessage("decisão alterou valor").isEqualByComparingTo(new BigDecimal(valor));
        assertThat(registro.get("moeda")).withFailMessage("decisão alterou moeda").isEqualTo("BRL");
        assertThat((BigDecimal) registro.get("limite_aplicado")).withFailMessage("limite da decisão divergente").isEqualByComparingTo("100.00");
        assertThat(historico.get("event_id")).withFailMessage("histórico perdeu identidade da saída").isEqualTo(saida.get("event_id"));
        assertThat(historico.get("causation_id")).withFailMessage("histórico perdeu causa da entrada").isEqualTo(entrada.get("event_id"));
        assertThat(historico.get("correlation_id")).withFailMessage("histórico perdeu correlação").isEqualTo(id);
        assertThat(historico.get("estado_anterior")).withFailMessage("histórico não iniciou em PENDENTE").isEqualTo("PENDENTE");
        assertThat(historico.get("estado_final")).withFailMessage("histórico perdeu decisão").isEqualTo(status);
        assertThat(historico.get("origem")).withFailMessage("histórico perdeu origem").isEqualTo("processamento-service/TransacaoProcessada.v1");
        var payloadEntrada = lerJson(entrada.get("payload").toString());
        var payloadSaida = lerJson(saida.get("payload").toString());
        assertThat(payloadEntrada.path("eventId").asText()).withFailMessage("payload de entrada perdeu eventId").isEqualTo(entrada.get("event_id").toString());
        assertThat(payloadEntrada.path("correlationId").asText()).withFailMessage("payload de entrada perdeu correlação").isEqualTo(id.toString());
        assertThat(payloadEntrada.path("data").path("transactionId").asText()).withFailMessage("payload de entrada perdeu transação").isEqualTo(id.toString());
        assertThat(payloadEntrada.path("data").path("amount").asText()).withFailMessage("entrada alterou decimal textual original").isEqualTo(valor);
        assertThat(payloadSaida.path("eventId").asText()).withFailMessage("payload de saída perdeu eventId").isEqualTo(saida.get("event_id").toString());
        assertThat(payloadSaida.path("causationId").asText()).withFailMessage("payload de saída perdeu causa").isEqualTo(entrada.get("event_id").toString());
        assertThat(payloadSaida.path("correlationId").asText()).withFailMessage("payload de saída perdeu correlação").isEqualTo(id.toString());
        assertThat(payloadSaida.path("data").path("transactionId").asText()).withFailMessage("payload de saída perdeu transação").isEqualTo(id.toString());
        assertThat(payloadSaida.path("data").path("status").asText()).withFailMessage("payload de saída perdeu decisão").isEqualTo(status);
        var ocorridoEntrada = Instant.parse(payloadEntrada.path("occurredAt").asText());
        var ocorridoSaida = Instant.parse(payloadSaida.path("occurredAt").asText());
        assertThat(registro.get("event_occurred_epoch_second")).withFailMessage("decisão alterou instante da entrada").isEqualTo(ocorridoEntrada.getEpochSecond());
        assertThat(registro.get("event_occurred_nano")).withFailMessage("decisão alterou precisão da entrada").isEqualTo(ocorridoEntrada.getNano());
        assertThat(historico.get("occurred_at_epoch_second")).withFailMessage("histórico alterou instante da saída").isEqualTo(ocorridoSaida.getEpochSecond());
        assertThat(historico.get("occurred_at_nano")).withFailMessage("histórico alterou precisão da saída").isEqualTo(ocorridoSaida.getNano());
        assertThat(((java.sql.Timestamp) registro.get("processed_at")).toInstant())
                .withFailMessage("saída não conserva instante confirmado da decisão").isEqualTo(ocorridoSaida);
        assertThat(transacoes.queryForObject("SELECT scale(valor) FROM transacoes WHERE id = ?", Integer.class, id))
                .withFailMessage("transação perdeu escala decimal original").isEqualTo(3);
        assertThat(processamento.queryForObject("SELECT scale(valor) FROM processamentos WHERE transaction_id = ?", Integer.class, id))
                .withFailMessage("decisão perdeu escala decimal original").isEqualTo(3);
        conferirUnicidade(transacoes, "transacoes", "id", id);
        conferirUnicidade(transacoes, "historico_transacoes", "transaction_id", id);
        conferirUnicidade(transacoes, "outbox_eventos", "aggregate_id", id);
        conferirUnicidade(processamento, "processamentos", "transaction_id", id);
        conferirUnicidade(processamento, "outbox_eventos", "aggregate_id", id);
        aguardarFilasVazias(broker);
        var antes = snapshot(transacoes, processamento);
        var replay = post(aplicativo, chave, new BigDecimal(valor).stripTrailingZeros().toPlainString());
        assertThat(replay.statusCode()).withFailMessage("replay POST não retornou201").isEqualTo(201);
        assertThat(replay.headers().firstValue("Location")).withFailMessage("replay POST alterou Location").contains(location);
        assertThat(replay.body()).withFailMessage("replay POST alterou resposta original PENDENTE").isEqualTo(resposta.body());
        assertThat(aguardarFinal(aplicativo, location, status)).withFailMessage("replay POST alterou GET final").isEqualTo(finalizada);
        assertThat(snapshot(transacoes, processamento)).withFailMessage("replay POST alterou registros completos dos bancos").isEqualTo(antes);
        aguardarFilasVazias(broker);
        System.out.println("ImagensCredPayE2E: fluxo=" + status + ", outboxes publicadas=2, histórico único, replayPOST estável");
    }

    private HttpResponse<String> post(GenericContainer<?> aplicativo, UUID chave, String valor) throws Exception {
        return enviar(HttpRequest.newBuilder(uri(aplicativo, "/transacoes"))
                .header("Content-Type", "application/json").header("Idempotency-Key", chave.toString())
                .POST(HttpRequest.BodyPublishers.ofString("{\"valor\":" + valor + ",\"moeda\":\"BRL\"}")));
    }

    private URI uri(GenericContainer<?> aplicativo, String caminho) {
        return URI.create("http://" + aplicativo.getHost() + ":" + aplicativo.getMappedPort(8080) + caminho);
    }

    private HttpResponse<String> enviar(HttpRequest.Builder pedido) throws Exception {
        return HTTP.send(pedido.timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode lerJson(String corpo) {
        try {
            var json = JSON.readTree(corpo);
            assertThat(json).withFailMessage("JSON financeiro ausente").isNotNull();
            assertThat(json.isObject()).withFailMessage("JSON financeiro não é objeto").isTrue();
            return json;
        } catch (java.io.IOException respostaInvalida) {
            throw new AssertionError("JSON financeiro inválido; conteúdo omitido");
        }
    }

    private JsonNode aguardarFinal(GenericContainer<?> aplicativo, String location, String status) throws Exception {
        var prazo = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        do {
            var resposta = enviar(HttpRequest.newBuilder(uri(aplicativo, location)).GET());
            assertThat(resposta.statusCode()).withFailMessage("GET não retornou200").isEqualTo(200);
            var json = lerJson(resposta.body());
            if (status.equals(json.path("status").asText())) return json;
            TimeUnit.MILLISECONDS.sleep(100);
        } while (System.nanoTime() < prazo);
        throw new AssertionError("GET não alcançou estado final esperado: " + status);
    }

    private void aguardarPublicacoes(JdbcTemplate transacoes, JdbcTemplate processamento, UUID id) throws InterruptedException {
        var prazo = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            var primeira = transacoes.queryForObject("SELECT COUNT(*) FROM outbox_eventos WHERE aggregate_id = ? AND published_at IS NOT NULL", Integer.class, id) == 1;
            var segunda = processamento.queryForObject("SELECT COUNT(*) FROM outbox_eventos WHERE aggregate_id = ? AND published_at IS NOT NULL", Integer.class, id) == 1;
            if (primeira && segunda) return;
            TimeUnit.MILLISECONDS.sleep(100);
        } while (System.nanoTime() < prazo);
        throw new AssertionError("ambas outboxes não ficaram publicadas no prazo");
    }

    private void conferirUnicidade(JdbcTemplate banco, String tabela, String coluna, UUID id) {
        assertThat(banco.queryForObject("SELECT COUNT(*) FROM " + tabela + " WHERE " + coluna + " = ?", Integer.class, id))
                .withFailMessage("registro persistido não é único: " + tabela).isEqualTo(1);
    }

    private List<List<Map<String, Object>>> snapshot(JdbcTemplate transacoes, JdbcTemplate processamento) {
        return List.of(transacoes.queryForList("SELECT * FROM transacoes ORDER BY id"),
                transacoes.queryForList("SELECT * FROM historico_transacoes ORDER BY event_id"),
                transacoes.queryForList("SELECT * FROM outbox_eventos ORDER BY event_id"),
                processamento.queryForList("SELECT * FROM processamentos ORDER BY event_id"),
                processamento.queryForList("SELECT * FROM outbox_eventos ORDER BY event_id"));
    }

    private void aguardarFilasVazias(RabbitMQContainer broker) throws Exception {
        var prazo = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            var filas = consultar(broker, "list_queues", "name", "messages_ready", "messages_unacknowledged");
            boolean vazias = FILAS.stream().allMatch(nome -> {
                var fila = porNome(filas, nome);
                assertThat(fila.path("messages_ready").isIntegralNumber()).withFailMessage("contagem ready ausente/inválida").isTrue();
                assertThat(fila.path("messages_unacknowledged").isIntegralNumber()).withFailMessage("contagem unacked ausente/inválida").isTrue();
                return fila.path("messages_ready").asInt() == 0 && fila.path("messages_unacknowledged").asInt() == 0;
            });
            if (vazias) return;
            TimeUnit.MILLISECONDS.sleep(200);
        } while (System.nanoTime() < prazo);
        throw new AssertionError("entradas/DLQs não ficaram vazias após fluxo/replay");
    }
}
