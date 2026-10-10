package br.com.credpay.processamento.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = DefaultManagementHttpTest.Fixture.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.address=127.0.0.1")
@ActiveProfiles("diagnostics")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DiagnosticsManagementHttpTest {

    @Autowired private TestRestTemplate http;
    @Autowired private MeterRegistry registry;
    @Autowired private ObjectMapper mapper;

    @Test
    void diagnostics_deveConsultarContadorComFiltroDeOutcome() throws Exception {
        registry.counter("credpay.messaging.publish.attempts", "outcome", "confirmed").increment(2);
        registry.counter("credpay.messaging.publish.attempts", "outcome", "returned").increment();
        registry.counter("credpay.messaging.publish.attempts", "outcome", "nacked").increment();
        registry.counter("credpay.messaging.publish.attempts", "outcome", "error").increment();
        var response = http.getForEntity("/actuator/metrics/credpay.messaging.publish.attempts", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var metric = mapper.readTree(response.getBody());
        assertThat(metric.path("name").asText()).isEqualTo("credpay.messaging.publish.attempts");
        assertThat(metric.path("measurements").get(0).path("statistic").asText()).isEqualTo("COUNT");
        assertThat(metric.path("measurements").get(0).path("value").asDouble()).isEqualTo(5);
        assertThat(metric.path("availableTags").size()).isEqualTo(1);
        var tag = metric.path("availableTags").get(0);
        assertThat(tag.path("tag").asText()).isEqualTo("outcome");
        var values = new java.util.ArrayList<String>();
        tag.path("values").forEach(value -> values.add(value.asText()));
        assertThat(values).containsExactlyInAnyOrder("confirmed", "returned", "nacked", "error");
        var filtered = http.getForEntity(
                "/actuator/metrics/credpay.messaging.publish.attempts?tag=outcome:returned", String.class);
        assertThat(filtered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(filtered.getBody()).path("measurements").get(0).path("value").asDouble())
                .isEqualTo(1);
    }

    @Test
    void diagnostics_deveManterHealthEDemaisEndpointsFechados() {
        assertThat(http.getForEntity("/actuator/health", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        for (var endpoint : java.util.List.of("env", "beans", "configprops")) {
            assertThat(http.getForEntity("/actuator/" + endpoint, String.class).getStatusCode())
                    .as(endpoint).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}

