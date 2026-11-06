"""Exercise the production trust bootstrap in a non-root JRE, with synthetic loopback TLS only."""

from pathlib import Path
import subprocess
import tempfile

DEPLOY = Path(__file__).resolve().parents[1]
JDK = "eclipse-temurin:21-jdk-jammy"
JRE = "eclipse-temurin:21-jre-jammy"
PROBE = r'''
import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import javax.net.ssl.*;

public class Probe {
  public static void main(String[] args) throws Exception {
    char[] password = "changeit".toCharArray();
    var original = KeyStore.getInstance(Path.of(System.getenv("JAVA_HOME"), "lib/security/cacerts").toFile(), password);
    var combined = KeyStore.getInstance(Path.of(System.getProperty("javax.net.ssl.trustStore")).toFile(), password);
    for (var names = original.aliases(); names.hasMoreElements();) {
      var name = names.nextElement();
      if (!original.getCertificate(name).equals(combined.getCertificate(name))) throw new AssertionError("Public CA lost");
    }
    if (combined.size() != original.size() + 1) throw new AssertionError("AI CA not added");
    var keys = KeyStore.getInstance(Path.of("/checks/server.p12").toFile(), password);
    var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    km.init(keys, password);
    var context = SSLContext.getInstance("TLS");
    context.init(km.getKeyManagers(), null, null);
    var server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 8443), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(context));
    server.createContext("/health/ready/v2", exchange -> {
      var ok = "synthetic-internal-key".equals(exchange.getRequestHeaders().getFirst("X-Internal-Api-Key"));
      exchange.sendResponseHeaders(ok ? 200 : 401, -1);
      exchange.close();
    });
    server.start();
    try (var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(3)).build()) {
      var request = HttpRequest.newBuilder(URI.create("https://" + args[0] + ":8443/health/ready/v2"))
          .timeout(Duration.ofSeconds(5)).header("X-Internal-Api-Key", "synthetic-internal-key").build();
      try {
        if (client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() != 200)
          throw new AssertionError("Authentication failed");
        if (!args[1].equals("success")) throw new AssertionError("Invalid TLS accepted");
      } catch (SSLHandshakeException rejected) {
        if (!args[1].equals("reject")) throw rejected;
      }
    } finally { server.stop(0); }
    System.out.println("Public CAs preserved; TLS " + args[1]);
  }
}
'''
PREPARE = r'''
set -eu
cd /checks
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj /CN=Test-CA \
  -addext basicConstraints=critical,CA:TRUE -addext keyUsage=critical,keyCertSign,cRLSign \
  -keyout ca.key -out ca.crt 2>/dev/null
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj /CN=Wrong-CA \
  -addext basicConstraints=critical,CA:TRUE -keyout wrong.key -out wrong.crt 2>/dev/null
openssl req -new -newkey rsa:2048 -nodes -subj /CN=hanmaum-ai -keyout server.key -out server.csr 2>/dev/null
printf 'subjectAltName=DNS:hanmaum-ai\nbasicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n' > extensions.cnf
openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days 1 \
  -extfile extensions.cnf -out server.crt 2>/dev/null
openssl pkcs12 -export -in server.crt -inkey server.key -certfile ca.crt -out server.p12 -passout pass:changeit
javac Probe.java
chmod 755 /checks
chmod 644 /checks/*
'''


def docker(*args, succeeds=True):
    result = subprocess.run(["docker", *map(str, args)], capture_output=True, text=True)
    if succeeds and result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result


def main():
    # Pull before the network-none checks; no application image, real credentials or model calls.
    for image in (JDK, JRE):
        if docker("image", "inspect", image, succeeds=False).returncode:
            docker("pull", image)
    with tempfile.TemporaryDirectory(prefix="hanmaum-be-ai-tls-") as directory:
        work = Path(directory)
        (work / "Probe.java").write_text(PROBE, encoding="utf-8")
        docker("run", "--rm", "--network", "none", "--mount", f"type=bind,src={work},dst=/checks",
               JDK, "bash", "-c", PREPARE)
        base = ["run", "--rm", "--network", "none", "--user", "10001:10001", "--read-only",
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true",
                "--tmpfs", "/tmp:rw,mode=1777", "--add-host", "hanmaum-ai:127.0.0.1",
                "--mount", f"type=bind,src={work},dst=/checks,readonly",
                "--mount", f"type=bind,src={DEPLOY / 'backend-entrypoint.sh'},dst=/entrypoint.sh,readonly"]
        for certificate, hostname, expected in (("ca.crt", "hanmaum-ai", "success"),
                                                 ("wrong.crt", "hanmaum-ai", "reject"),
                                                 ("ca.crt", "127.0.0.1", "reject")):
            result = docker(*base, "--mount", f"type=bind,src={work / certificate},dst=/run/hanmaum-ai/ca.crt,readonly",
                            JRE, "bash", "/entrypoint.sh", "-cp", "/checks", "Probe", hostname, expected)
            print(result.stdout.strip())
        result = docker(*base, JRE, "bash", "/entrypoint.sh", "-version", succeeds=False)
        assert result.returncode != 0, "Missing AI CA must prevent startup"
    print("AI TLS: non-root/read-only JRE, public roots, authentication, untrusted CA, hostname and missing CA passed.")


if __name__ == "__main__":
    main()
