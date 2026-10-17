package dev.trustpay;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import javax.net.ssl.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/** Java has its own truststore. Add the runtime's system CA bundle for private Vercel TLS. */
final class PlatformHttp {
  static SimpleClientHttpRequestFactory factory(String url, int connectMillis, int readMillis) {
    var host = URI.create(url).getHost();
    SSLSocketFactory tls =
        host != null && host.endsWith(".services.vercel-infra.com") ? systemTls() : null;
    var factory =
        new SimpleClientHttpRequestFactory() {
          @Override
          protected void prepareConnection(HttpURLConnection connection, String method)
              throws IOException {
            super.prepareConnection(connection, method);
            if (tls != null && connection instanceof HttpsURLConnection secure)
              secure.setSSLSocketFactory(tls);
          }
        };
    factory.setConnectTimeout(connectMillis);
    factory.setReadTimeout(readMillis);
    return factory;
  }

  private static SSLSocketFactory systemTls() {
    try {
      var store = KeyStore.getInstance(KeyStore.getDefaultType());
      store.load(null, null);
      var defaults = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      defaults.init((KeyStore) null);
      int count = 0;
      for (var manager : defaults.getTrustManagers())
        if (manager instanceof X509TrustManager x509)
          for (var cert : x509.getAcceptedIssuers())
            store.setCertificateEntry("jvm-" + count++, cert);
      var paths = new LinkedHashSet<String>();
      for (var key :
          List.of(
              "SSL_CERT_FILE",
              "NODE_EXTRA_CA_CERTS",
              "REQUESTS_CA_BUNDLE",
              "CURL_CA_BUNDLE",
              "VERCEL_CA_CERT_PATH")) {
        var value = System.getenv(key);
        if (value != null && !value.isBlank()) paths.add(value);
      }
      paths.add("/etc/ssl/certs/ca-certificates.crt");
      paths.add("/etc/ssl/cert.pem");
      for (var path : paths)
        if (Files.isRegularFile(Path.of(path))) {
          try (var input = Files.newInputStream(Path.of(path))) {
            for (var cert : CertificateFactory.getInstance("X.509").generateCertificates(input))
              store.setCertificateEntry("system-" + count++, cert);
          }
        }
      var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trust.init(store);
      var context = SSLContext.getInstance("TLS");
      context.init(null, trust.getTrustManagers(), null);
      org.slf4j.LoggerFactory.getLogger(PlatformHttp.class)
          .info("Private service TLS uses JVM and runtime CA bundles ({} certificates)", count);
      return context.getSocketFactory();
    } catch (Exception e) {
      throw new IllegalStateException("Cannot initialize runtime certificate trust", e);
    }
  }
}
