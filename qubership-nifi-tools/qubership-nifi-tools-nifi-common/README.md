# qubership-nifi-tools-nifi-common

A reusable library for talking to the Apache NiFi 2.x REST API from a command-line tool: TLS setup,
authentication, bounded HTTP transport, URI resolution, version detection, and component catalog
retrieval. It is built on Apache HttpClient 5 and Jackson, and it makes no assumption about how the
calling tool is configured or what it does with the results.

## Maven coordinates

```xml
<dependency>
    <groupId>org.qubership.nifi</groupId>
    <artifactId>qubership-nifi-tools-nifi-common</artifactId>
    <version>${qubership-nifi.version}</version>
</dependency>
```

Requires Java 21.

## Packages

| Package | Contents |
| --- | --- |
| `...nifi.common.tls` | `TlsContextFactory` builds an `SSLContext` from optional client key material (`Pkcs12KeyMaterial`) and optional trust material (`PemTrustMaterial`, `Pkcs12TrustMaterial`). Unusable material raises `TlsMaterialException`. |
| `...nifi.common.auth` | `NiFiRequestAuthenticator` and its three implementations: `BearerTokenAuthenticator`, `AuthorizationBearerCookieAuthenticator`, and `NoAuthentication` for mutual TLS or unauthenticated endpoints. |
| `...nifi.common.http` | `NiFiUriResolver` normalizes the deployment URL and resolves paths against it. `NiFiHttpClient` is the bounded transport, `NiFiRestClient` the JSON layer over it, `NiFiHttpResponse` the result, and `NiFiApiException` the failure. |
| `...nifi.common.api` | `NiFiAboutClient` and `NiFiVersion` for version detection, `NiFiComponentCatalogClient` and `NiFiComponentKind` for type lists, component definitions, and additional details. |

## Library usage

```java
NiFiUriResolver resolver = NiFiUriResolver.fromBaseUrl("https://nifi.example.com/nifi");
SSLContext sslContext = TlsContextFactory.create(
        Optional.empty(), Optional.of(PemTrustMaterial.fromFile(Path.of("ca.pem"))));
NiFiRequestAuthenticator auth = new BearerTokenAuthenticator(token);

NiFiHttpClient http = new NiFiHttpClient(
        NiFiHttpClient.newHttpClient(sslContext, Duration.ofSeconds(30)), resolver, auth);
try (NiFiRestClient rest = new NiFiRestClient(http, new ObjectMapper())) {
    String version = new NiFiAboutClient(rest, resolver).readVersionString();

    NiFiComponentCatalogClient catalog = new NiFiComponentCatalogClient(rest, resolver);
    for (JsonNode type : catalog.listTypes(NiFiComponentKind.PROCESSOR)) {
        JsonNode definition = catalog.getDefinition(NiFiComponentKind.PROCESSOR,
                type.path("bundle").path("group").asText(),
                type.path("bundle").path("artifact").asText(),
                type.path("bundle").path("version").asText(),
                type.path("type").asText());
    }
}
```

`NiFiRestClient` and `NiFiHttpClient` own the underlying Apache client and its connection pool.
Close one of them when the work is done.

`Pkcs12KeyMaterial` and `Pkcs12TrustMaterial` copy their password arrays. Call `clearPassword()` in
a `finally` block after `TlsContextFactory.create` returns or fails. The caller remains responsible
for clearing the password array supplied to the material object.

`NiFiUriResolver.fromBaseUrl` requires HTTPS by default; the two-argument overload relaxes that for
a test server. It accepts either the deployment URL or the browser UI URL, stripping a trailing
`/nifi` or `/nifi-api` so both normalize to the same base, and it preserves a reverse-proxy path
prefix.

## What the transport guarantees

- **Same origin.** Every request URI is checked against the resolver's scheme, host, and port, and
  so is every redirect hop. A redirect off the origin is refused rather than followed.
- **No automatic redirects.** Redirects are followed only by this library's own loop, up to the
  configured budget.
- **Bounded responses.** A body over `maxBodyBytes` fails the exchange instead of being buffered.
- **Retries only where they are safe.** GET is retried on 429, 502, 503, and 504 and on transport
  failures, with exponential backoff capped by `maxBackoff` and honoring `Retry-After`. POST and
  DELETE are never retried.
- **Diagnostics without secrets.** `NiFiApiException` carries the method, a redacted URI, the status
  code, and a bounded single-line body excerpt. Authenticators redact their credentials in
  `toString()`.

All of the bounds live in `NiFiHttpClient.Config`; `Config.defaults()` is a 60-second request
timeout, a 32 MB body limit, three retries, and three redirects.

## Limitations

- **Synchronous only.** Every call blocks the calling thread. There is no async or reactive surface.
- **One origin per client.** A `NiFiHttpClient` is bound to the origin of the resolver it was built
  with. Talking to two NiFi instances means two clients.
- **NiFi 2.x endpoint paths.** `NiFiComponentKind` and `NiFiAboutClient` hardcode the NiFi 2.x
  `/nifi-api/flow/...` paths.
- **The request timeout is per socket read,** not a deadline for the whole response. A response that
  keeps trickling bytes can outlast it.
