package br.com.credpay.transacoes.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SessaoDemoController.class)
@Import(SessaoDemoConfiguration.class)
@ActiveProfiles("sessao-demo")
class LoginMethodsTest {
    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private JdbcTemplate jdbc;
    @MockitoBean(name = "clock")
    private Clock relogio;

    @Test
    void metodos_deveIndicarGoogleDesativadoSemExigirLogin() throws Exception {
        mvc.perform(get("/auth/metodos")).andExpect(status().isOk())
                .andExpect(jsonPath("$.google").value(false));
    }
}
