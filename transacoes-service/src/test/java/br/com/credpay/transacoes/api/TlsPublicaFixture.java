package br.com.credpay.transacoes.api;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

final class TlsPublicaFixture {

    private final Path arquivo;
    private final String senha;

    private TlsPublicaFixture(Path arquivo, String senha) {
        this.arquivo = arquivo;
        this.senha = senha;
    }

    static TlsPublicaFixture criar() {
        try {
            var projeto = Path.of("").toRealPath();
            var target = projeto.resolve("target");
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !target.toRealPath().startsWith(projeto)) {
                throw new IllegalStateException("Target TLS fora do workspace próprio.");
            }
            Files.createDirectories(target);
            var pasta = Files.createTempDirectory(target, "public-tls-");
            boolean posix = Files.getFileStore(pasta).supportsFileAttributeView("posix");
            if (posix) { Files.setPosixFilePermissions(pasta, PosixFilePermissions.fromString("rwx------")); }
            var arquivo = pasta.resolve("server.p12");
            var senha = UUID.randomUUID().toString() + UUID.randomUUID().toString();
            boolean windows = System.getProperty("os.name").startsWith("Windows");
            var keytool = Path.of(System.getProperty("java.home"), "bin", windows ? "keytool.exe" : "keytool");
            var builder = new ProcessBuilder(keytool.toString(), "-genkeypair", "-noprompt",
                    "-storetype", "PKCS12", "-keystore", arquivo.toString(), "-alias", "credpay-test",
                    "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=localhost",
                    "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "2",
                    "-storepass:env", "CREDPAY_TLS_TEST_PASS", "-keypass:env", "CREDPAY_TLS_TEST_PASS")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            var ambiente = builder.environment();
            ambiente.clear();
            ambiente.put("CREDPAY_TLS_TEST_PASS", senha);
            for (String nome : new String[] {"TMP", "TEMP", "TMPDIR"}) { ambiente.put(nome, pasta.toString()); }
            if (windows) { ambiente.put("SystemRoot", System.getenv("SystemRoot")); }
            var processo = builder.start();
            if (!processo.waitFor(30, TimeUnit.SECONDS)) {
                processo.destroyForcibly();
                throw new IllegalStateException("Fixture TLS: timeout de geração.");
            }
            if (processo.exitValue() != 0) { throw new IllegalStateException("Fixture TLS: geração recusada."); }
            if (posix) { Files.setPosixFilePermissions(arquivo, PosixFilePermissions.fromString("rw-------")); }
            return new TlsPublicaFixture(arquivo, senha);
        } catch (InterruptedException interrompida) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Fixture TLS interrompida.");
        } catch (Exception falha) {
            throw new IllegalStateException("Fixture TLS indisponível: " + falha.getClass().getSimpleName());
        }
    }

    String uri() { return arquivo.toUri().toString(); }

    String senha() { return senha; }

    SSLContext confianca() throws Exception {
        var servidor = KeyStore.getInstance("PKCS12");
        try (var entrada = Files.newInputStream(arquivo)) { servidor.load(entrada, senha.toCharArray()); }
        var confiaveis = KeyStore.getInstance("PKCS12");
        confiaveis.load(null, null);
        confiaveis.setCertificateEntry("credpay-test", servidor.getCertificate("credpay-test"));
        var fabrica = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        fabrica.init(confiaveis);
        var contexto = SSLContext.getInstance("TLS");
        contexto.init(null, fabrica.getTrustManagers(), null);
        return contexto;
    }
}
