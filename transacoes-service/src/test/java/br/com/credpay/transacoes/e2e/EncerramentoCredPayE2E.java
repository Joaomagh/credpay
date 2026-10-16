package br.com.credpay.transacoes.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EncerramentoCredPayE2E {

    private static final Path RAIZ = Path.of("..").toAbsolutePath().normalize();
    private static final String POSTGRES_IMAGE = "postgres@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Container static final PostgreSQLContainer TRANSACOES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("credpay_transacoes_closure").withUsername("test").withPassword("test");
    @Container static final PostgreSQLContainer PROCESSAMENTO = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("credpay_processamento_closure").withUsername("test").withPassword("test");
    @Container static final RabbitMQContainer RABBIT = new RabbitMQContainer(DockerImageName.parse(
            "rabbitmq:4.3.5-management-alpine@sha256:b3b8b7f95f5382a19f9ea33540e604f30aad081d37ad9aba72255135765373a1")
            .asCompatibleSubstituteFor("rabbitmq"));

    private final Path arquivos = RAIZ.resolve(".local/e2e/closure-" + UUID.randomUUID());
    private final List<Process> processos = new ArrayList<>();
    private final LinkedBlockingQueue<String> respostasTransacoes = new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<String> respostasProcessamento = new LinkedBlockingQueue<>();
    private Process aplicativoTransacoes;
    private Process aplicativoProcessamento;
    private JdbcTemplate transacoes;
    private JdbcTemplate processamento;
    private int porta;

    @BeforeAll
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    void preparar() throws Exception {
        try {
            Files.createDirectories(arquivos);
            transacoes = jdbc(TRANSACOES);
            processamento = jdbc(PROCESSAMENTO);
            porta = portaLivre();
            int outraPorta;
            do { outraPorta = portaLivre(); } while (outraPorta == porta);
            aplicativoTransacoes = iniciar("transacoes", "EncerramentoTransacoesFixture",
                    TRANSACOES, porta, respostasTransacoes);
            aplicativoProcessamento = iniciar("processamento", "EncerramentoProcessamentoFixture",
                    PROCESSAMENTO, outraPorta, respostasProcessamento);
            aguardar(respostasTransacoes, "READY", 90);
            aguardar(respostasProcessamento, "READY", 90);
            assertThat(transacoes.queryForObject("SELECT to_regclass('public.processamentos')", String.class)).isNull();
            assertThat(processamento.queryForObject("SELECT to_regclass('public.transacoes')", String.class)).isNull();
        } catch (Exception | AssertionError falha) {
            encerrar();
            throw falha;
        }
    }

    @AfterAll
    void encerrar() throws InterruptedException {
        for (var processo : processos) {
            processo.destroy();
            if (!processo.waitFor(10, TimeUnit.SECONDS)) {
                processo.destroyForcibly();
                assertThat(processo.waitFor(10, TimeUnit.SECONDS)).as("processo encerrado").isTrue();
            }
        }
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void pedidoERespostaReais_devemConfirmarFalhaComCadeiaCausal() throws Exception {
        var chave = UUID.randomUUID();
        var pedido = UUID.randomUUID();
        var evidencia = UUID.randomUUID();
        var criada = enviar(HttpRequest.newBuilder(uri("/transacoes"))
                .header("Content-Type", "application/json").header("Idempotency-Key", chave.toString())
                .POST(HttpRequest.BodyPublishers.ofString("{\"valor\":50.000,\"moeda\":\"BRL\"}")));
        assertThat(criada.statusCode()).as("POST real").isEqualTo(201);
        var corpo = JSON.readTree(criada.body());
        assertThat(corpo.path("status").asText()).isEqualTo("PENDENTE");
        var id = UUID.fromString(corpo.path("id").asText());
        var criacao = transacoes.queryForObject("SELECT event_id FROM outbox_eventos WHERE aggregate_id = ?", UUID.class, id);
        comando(aplicativoTransacoes, respostasTransacoes, "SOLICITAR " + pedido + " " + id + " " + evidencia);
        comando(aplicativoTransacoes, respostasTransacoes, "PUBLICAR " + id);
        comando(aplicativoProcessamento, respostasProcessamento, "CONSUMIR");
        comando(aplicativoProcessamento, respostasProcessamento, "PUBLICAR " + id);
        comando(aplicativoTransacoes, respostasTransacoes, "CONSUMIR");

        var consulta = enviar(HttpRequest.newBuilder(uri("/transacoes/" + id)).GET());
        assertThat(consulta.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(consulta.body()).path("status").asText())
                .as("pedido/resposta transportados devem confirmar FALHOU").isEqualTo("FALHOU");

        var registro = processamento.queryForMap("SELECT * FROM encerramentos WHERE request_event_id = ?", pedido);
        var resposta = (UUID) registro.get("response_event_id");
        assertThat(registro.get("transaction_id")).isEqualTo(id);
        assertThat(registro.get("creation_event_id")).isEqualTo(criacao);
        assertThat(registro.get("correlation_id")).isEqualTo(id);
        assertThat(registro.get("evidence_id")).isEqualTo(evidencia);
        assertThat(registro.get("resultado")).isEqualTo("CONFIRMADO");
        assertThat(registro.get("output_event_id")).isNull();
        assertThat(transacoes.queryForObject("""
                SELECT COUNT(*) FROM respostas_encerramento_recebidas
                WHERE event_id = ? AND transaction_id = ? AND creation_event_id = ? AND causation_id = ?
                    AND correlation_id = ? AND resultado = 'CONFIRMADO' AND output_event_id IS NULL
                """, Integer.class, resposta, id, criacao, pedido, id)).isEqualTo(1);
        assertThat(transacoes.queryForObject("""
                SELECT COUNT(*) FROM historico_transacoes WHERE event_id = ? AND transaction_id = ?
                    AND causation_id = ? AND estado_anterior = 'PENDENTE' AND estado_final = 'FALHOU'
                    AND origem = 'processamento-service/EncerramentoTransacaoRespondido.v1'
                """, Integer.class, resposta, id, pedido)).isEqualTo(1);
        assertThat(processamento.queryForObject("SELECT COUNT(*) FROM processamentos WHERE transaction_id = ?",
                Integer.class, id)).isZero();
        assertThat(processamento.queryForObject("SELECT COUNT(*) FROM outbox_eventos WHERE aggregate_id = ?",
                Integer.class, id)).isZero();
        System.out.println("Encerramento controlado: POST/PEDIDO/RESPOSTA/GET FALHOU e causalidade comprovados.");
    }

    private Process iniciar(String servico, String classe, PostgreSQLContainer banco, int httpPort,
            LinkedBlockingQueue<String> respostas) throws Exception {
        var modulo = servico + "-service";
        var nome = "br.com.credpay." + servico + ".infrastructure.messaging." + classe;
        var relativa = nome.replace('.', '/') + ".class";
        var jar = RAIZ.resolve(modulo + "/target/" + modulo + "-0.0.1-SNAPSHOT.jar");
        var selecionadas = arquivos.resolve(servico);
        var destino = selecionadas.resolve(relativa);
        Files.createDirectories(destino.getParent());
        Files.copy(RAIZ.resolve(modulo + "/target/test-classes/" + relativa), destino);
        try (var entradas = Files.walk(selecionadas); var zip = new JarFile(jar.toFile())) {
            assertThat(entradas.filter(Files::isRegularFile).toList()).containsExactly(destino);
            assertThat(zip.stream().anyMatch(e -> e.getName().contains(classe))).as("fixture ausente do JAR").isFalse();
        }
        var builder = new ProcessBuilder("java", "-Xmx256m", "-Dloader.main=" + nome,
                "-Dloader.path=" + selecionadas, "-cp", jar.toString(),
                "org.springframework.boot.loader.launch.PropertiesLauncher", "--server.port=" + httpPort,
                "--management.health.rabbit.enabled=true", "--credpay.processamento.limites.BRL=100.00",
                "--credpay." + servico + ".consumer.topology.enabled=false",
                "--credpay." + servico + ".consumer.listener.enabled=false", "--credpay.outbox.publisher.enabled=false");
        builder.directory(RAIZ.toFile());
        var ambiente = builder.environment();
        ambiente.put("SPRING_DATASOURCE_URL", banco.getJdbcUrl());
        ambiente.put("SPRING_DATASOURCE_USERNAME", banco.getUsername());
        ambiente.put("SPRING_DATASOURCE_PASSWORD", banco.getPassword());
        ambiente.put("SPRING_RABBITMQ_HOST", RABBIT.getHost());
        ambiente.put("SPRING_RABBITMQ_PORT", RABBIT.getAmqpPort().toString());
        ambiente.put("SPRING_RABBITMQ_USERNAME", RABBIT.getAdminUsername());
        ambiente.put("SPRING_RABBITMQ_PASSWORD", RABBIT.getAdminPassword());
        ambiente.put("SPRING_RABBITMQ_VIRTUAL_HOST", "/");
        var processo = builder.redirectErrorStream(true).start();
        processos.add(processo);
        Thread.ofPlatform().daemon().start(() -> {
            try (var leitor = new BufferedReader(new InputStreamReader(processo.getInputStream(), StandardCharsets.UTF_8));
                    var log = Files.newBufferedWriter(arquivos.resolve(servico + ".log"), StandardCharsets.UTF_8)) {
                String linha;
                while ((linha = leitor.readLine()) != null) {
                    if (linha.startsWith("CREDPAY_FIXTURE ")) respostas.offer(linha.substring(16));
                    else { log.write(linha); log.newLine(); }
                }
            } catch (java.io.IOException falha) {
                respostas.offer("FAILURE");
            }
        });
        return processo;
    }

    private void comando(Process processo, LinkedBlockingQueue<String> respostas, String comando) throws Exception {
        assertThat(processo.isAlive()).as("fixture ativa").isTrue();
        var escritor = new OutputStreamWriter(processo.getOutputStream(), StandardCharsets.UTF_8);
        escritor.write(comando + "\n");
        escritor.flush();
        aguardar(respostas, "DONE", 20);
    }

    private void aguardar(LinkedBlockingQueue<String> respostas, String esperado, int segundos) throws InterruptedException {
        assertThat(respostas.poll(segundos, TimeUnit.SECONDS))
                .withFailMessage("fixture não confirmou etapa %s; diagnóstico local em %s", esperado, arquivos)
                .isEqualTo(esperado);
    }

    private HttpResponse<String> enviar(HttpRequest.Builder builder) throws Exception {
        return HTTP.send(builder.timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String caminho) { return URI.create("http://localhost:" + porta + caminho); }

    private static JdbcTemplate jdbc(PostgreSQLContainer container) {
        var fonte = new DriverManagerDataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword());
        return new JdbcTemplate(fonte);
    }

    private int portaLivre() throws java.io.IOException {
        try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }
}
