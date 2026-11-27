package br.com.credpay.transacoes.api;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.startup.Tomcat;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

final class ProvedorOidcFixture implements AutoCloseable {
    enum Modo { VALIDO, EMISSOR_INVALIDO, AUDIENCIA_INVALIDA, NONCE_INVALIDO, EXPIRADO, ASSINATURA_INVALIDA }

    private final Tomcat servidor;
    private final RSAKey chave;
    private final RSAKey outraChave;
    private final Map<String, Autorizacao> codigos = new ConcurrentHashMap<>();
    private final AtomicInteger trocas = new AtomicInteger();
    private volatile Modo modo = Modo.VALIDO;
    private volatile String sujeito = "sub-a";

    ProvedorOidcFixture() {
        try {
            chave = chave();
            outraChave = chave();
            servidor = new Tomcat();
            var base = Files.createDirectories(Path.of("target", "oidc-fixture-" + UUID.randomUUID()).toAbsolutePath());
            servidor.setBaseDir(base.toString());
            servidor.setPort(0);
            servidor.getConnector().setProperty("address", "127.0.0.1");
            var contexto = servidor.addContext("", base.toString());
            Tomcat.addServlet(contexto, "oidc", new HttpServlet() {
                @Override
                protected void service(HttpServletRequest pedido, HttpServletResponse resposta) throws IOException {
                    switch (pedido.getRequestURI()) {
                        case "/authorize" -> autorizar(pedido, resposta);
                        case "/token" -> token(pedido, resposta);
                        case "/jwks" -> json(resposta, 200, "{\"keys\":[" + chave.toPublicJWK().toJSONString() + "]}");
                        default -> resposta.setStatus(404);
                    }
                }
            });
            contexto.addServletMappingDecoded("/*", "oidc");
            servidor.start();
        } catch (Exception erro) {
            throw new IllegalStateException("Não foi possível iniciar o provedor de teste", erro);
        }
    }

    String origem() { return "http://127.0.0.1:" + servidor.getConnector().getLocalPort(); }
    int trocas() { return trocas.get(); }
    void sujeito(String valor) { sujeito = valor; }
    void modo(Modo valor) { modo = valor; }
    void reiniciar() { modo = Modo.VALIDO; sujeito = "sub-a"; trocas.set(0); codigos.clear(); }

    ClientRegistration registro() {
        return ClientRegistration.withRegistrationId("google").clientId("cliente-ficticio").clientSecret("ficticio")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).scope("openid")
                .redirectUri("{baseUrl}/auth/retorno/google").authorizationUri(origem() + "/authorize")
                .tokenUri(origem() + "/token").jwkSetUri(origem() + "/jwks").issuerUri(origem())
                .userNameAttributeName("sub").clientName("Provedor de teste")
                .clientSettings(ClientRegistration.ClientSettings.builder().requireProofKey(true).build()).build();
    }

    String autorizar(String destino) throws Exception {
        if (!URI.create(destino).getAuthority().equals(URI.create(origem()).getAuthority())) {
            throw new IllegalArgumentException("O teste só aceita o provedor loopback");
        }
        try (var cliente = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
            var resposta = cliente.send(HttpRequest.newBuilder(URI.create(destino)).timeout(Duration.ofSeconds(5))
                    .build(), HttpResponse.BodyHandlers.discarding());
            if (resposta.statusCode() != 302) { throw new IllegalStateException("Autorização de teste recusada"); }
            return resposta.headers().firstValue("Location").orElseThrow();
        }
    }

    private void autorizar(HttpServletRequest pedido, HttpServletResponse resposta) throws IOException {
        var parametros = parametros(pedido.getQueryString());
        if (!"GET".equals(pedido.getMethod()) || !"code".equals(parametros.get("response_type"))
                || !"openid".equals(parametros.get("scope"))
                || !"S256".equals(parametros.get("code_challenge_method"))) {
            json(resposta, 400, "{\"error\":\"invalid_request\"}");
            return;
        }
        var codigo = UUID.randomUUID().toString();
        codigos.put(codigo, new Autorizacao(parametros.get("redirect_uri"), parametros.get("nonce"),
                parametros.get("code_challenge"), sujeito));
        resposta.sendRedirect(parametros.get("redirect_uri") + "?code=" + codigo
                + "&state=" + URLEncoder.encode(parametros.get("state"), StandardCharsets.UTF_8));
    }

    private void token(HttpServletRequest pedido, HttpServletResponse resposta) throws IOException {
        trocas.incrementAndGet();
        var corpo = pedido.getInputStream().readNBytes(8193);
        var parametros = parametros(new String(corpo, StandardCharsets.UTF_8));
        var autorizacao = codigos.remove(parametros.get("code"));
        var basic = "Basic " + Base64.getEncoder().encodeToString(
                "cliente-ficticio:ficticio".getBytes(StandardCharsets.UTF_8));
        try {
            var verificador = parametros.getOrDefault("code_verifier", "");
            var desafio = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verificador.getBytes(StandardCharsets.UTF_8)));
            if (corpo.length > 8192 || autorizacao == null || !basic.equals(pedido.getHeader("Authorization"))
                    || !"authorization_code".equals(parametros.get("grant_type"))
                    || !autorizacao.retorno().equals(parametros.get("redirect_uri"))
                    || !autorizacao.desafio().equals(desafio)) {
                json(resposta, 400, "{\"error\":\"invalid_grant\"}");
                return;
            }
            var agora = Instant.now();
            var claims = new JWTClaimsSet.Builder().issuer(modo == Modo.EMISSOR_INVALIDO ? "https://outro.example" : origem())
                    .subject(autorizacao.sujeito()).audience(modo == Modo.AUDIENCIA_INVALIDA ? "outro-cliente" : "cliente-ficticio")
                    .issueTime(Date.from(agora.minusSeconds(modo == Modo.EXPIRADO ? 600 : 1)))
                    .expirationTime(Date.from(agora.plusSeconds(modo == Modo.EXPIRADO ? -300 : 120)))
                    .claim("nonce", modo == Modo.NONCE_INVALIDO ? "nonce-divergente" : autorizacao.nonce()).build();
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(chave.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(modo == Modo.ASSINATURA_INVALIDA ? outraChave : chave));
            json(resposta, 200, "{\"access_token\":\"token-ficticio\",\"token_type\":\"Bearer\",\"expires_in\":120,"
                    + "\"scope\":\"openid\",\"id_token\":\"" + jwt.serialize() + "\"}");
        } catch (JOSEException | java.security.GeneralSecurityException erro) {
            throw new IOException("Falha criptográfica no provedor de teste", erro);
        }
    }

    private static Map<String, String> parametros(String query) {
        var resultado = new HashMap<String, String>();
        if (query != null) {
            for (var parte : query.split("&")) {
                var par = parte.split("=", 2);
                resultado.put(URLDecoder.decode(par[0], StandardCharsets.UTF_8),
                        par.length == 2 ? URLDecoder.decode(par[1], StandardCharsets.UTF_8) : "");
            }
        }
        return resultado;
    }

    private static RSAKey chave() throws java.security.GeneralSecurityException {
        var gerador = KeyPairGenerator.getInstance("RSA");
        gerador.initialize(2048);
        var par = gerador.generateKeyPair();
        return new RSAKey.Builder((RSAPublicKey) par.getPublic()).privateKey((RSAPrivateKey) par.getPrivate())
                .keyID(UUID.randomUUID().toString()).build();
    }

    private static void json(HttpServletResponse resposta, int status, String texto) throws IOException {
        var bytes = texto.getBytes(StandardCharsets.UTF_8);
        resposta.setContentType("application/json");
        resposta.setStatus(status);
        resposta.setContentLength(bytes.length);
        resposta.getOutputStream().write(bytes);
    }

    @Override
    public void close() {
        try { servidor.stop(); servidor.destroy(); }
        catch (org.apache.catalina.LifecycleException erro) {
            throw new IllegalStateException("Falha ao encerrar provedor de teste", erro);
        }
    }

    private record Autorizacao(String retorno, String nonce, String desafio, String sujeito) { }
}
