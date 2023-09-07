/*
 * Copyright (c) 2022, 2023 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.helidon.webserver.tests.mtls;

import java.security.Principal;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.LinkedList;
import java.util.List;

import io.helidon.common.configurable.Resource;
import io.helidon.http.Http;
import io.helidon.common.pki.Keys;
import io.helidon.common.tls.Tls;
import io.helidon.common.tls.TlsClientAuth;
import io.helidon.webserver.testing.junit5.ServerTest;
import io.helidon.webserver.testing.junit5.SetUpRoute;
import io.helidon.webserver.testing.junit5.SetUpServer;
import io.helidon.webclient.api.ClientResponseTyped;
import io.helidon.webclient.api.WebClient;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.WebServerConfig;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.http.ServerResponse;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

@ServerTest
class MtlsTest {
    private static WebServer server;
    private final WebClient client;

    MtlsTest(WebServer server) {
        MtlsTest.server = server;
        int port = server.port();

        Keys privateKeyConfig = Keys.builder()
                .keystore(keystore -> keystore
                        .keystore(Resource.create("client.p12"))
                        .passphrase("password"))
                .build();

        Tls tls = Tls.builder()
                .clientAuth(TlsClientAuth.REQUIRED)
                .privateKey(privateKeyConfig.privateKey().get())
                .privateKeyCertChain(privateKeyConfig.certChain())
                .trustAll(true) // todo need to have this from a keystore as well
                // insecure setup, as we have self-signed certificate
                .endpointIdentificationAlgorithm(Tls.ENDPOINT_IDENTIFICATION_NONE)
                .build();

        client = WebClient.builder()
                .baseUri("https://localhost:" + port)
                .tls(tls)
                .build();
    }

    @SetUpRoute
    static void routing(HttpRouting.Builder routing) {
        routing.get("/name", (req, res) -> {
                    String name = req.remotePeer().tlsPrincipal().map(Principal::getName).orElse(null);
                    if (name == null) {
                        res.status(Http.Status.BAD_REQUEST_400).send("Expected client principal");
                    } else {
                        res.send(name);
                    }
                })
                .get("/certs", (req, res) -> {
                    Certificate[] certs = req.remotePeer().tlsCertificates().orElse(null);
                    sendCertificateString(certs, res);
                })
                .get("/reload", (req, res) -> {
                    Keys privateKeyConfig = Keys.builder()
                            .keystore(keystore -> keystore
                                    .keystore(Resource.create("second-valid/server.p12"))
                                    .passphrase("password"))
                            .build();

                    Tls tls = Tls.builder()
                            .clientAuth(TlsClientAuth.REQUIRED)
                            .privateKey(privateKeyConfig.privateKey().get())
                            .privateKeyCertChain(privateKeyConfig.certChain())
                            .trustAll(true)
                            // insecure setup, as we have self-signed certificate
                            .endpointIdentificationAlgorithm(Tls.ENDPOINT_IDENTIFICATION_NONE)
                            .build();

                    server.reloadTls(tls);
                    res.status(Http.Status.OK_200).send();
                })
                .get("/serverCert", (req, res) -> {
                    Certificate[] certs = req.localPeer().tlsCertificates().orElse(null);
                    sendCertificateString(certs, res);
                });
    }

    @SetUpServer
    static void server(WebServerConfig.Builder builder) {
        Keys privateKeyConfig = Keys.builder()
                .keystore(keystore -> keystore
                        .keystore(Resource.create("server.p12"))
                        .passphrase("password"))
                .build();

        Tls tls = Tls.builder()
                .clientAuth(TlsClientAuth.REQUIRED)
                .privateKey(privateKeyConfig.privateKey().get())
                .privateKeyCertChain(privateKeyConfig.certChain())
                .trustAll(true)
                // insecure setup, as we have self-signed certificate
                .endpointIdentificationAlgorithm(Tls.ENDPOINT_IDENTIFICATION_NONE)
                .build();

        builder.tls(tls);
    }

    @Test
    void testMutualTlsPrincipal() {
        ClientResponseTyped<String> response = client.method(Http.Method.GET)
                .uri("/name")
                .request(String.class);

        assertThat(response.status(), is(Http.Status.OK_200));
        assertThat(response.entity(), is("CN=Helidon-Test-Client"));
    }

    @Test
    void testMutualTlsCertificates() {
        ClientResponseTyped<String> response = client.method(Http.Method.GET)
                .uri("/certs")
                .request(String.class);

        assertThat(response.status(), is(Http.Status.OK_200));
        assertThat(response.entity(), is("X.509:CN=Helidon-Test-Client|X.509:CN=Helidon-Test-CA"));
    }

    @Test
    void testTlsReload() {
        ClientResponseTyped<String> response = client.method(Http.Method.GET)
                .uri("/serverCert")
                .request(String.class);

        assertThat(response.status(), is(Http.Status.OK_200));
        assertThat(response.entity(), is("X.509:CN=Helidon-Test-Server|X.509:CN=Helidon-Test-CA"));

        response = client.method(Http.Method.GET)
                .uri("/reload")
                .request(String.class);

        assertThat(response.status(), is(Http.Status.OK_200));

        response = client.method(Http.Method.GET)
                .uri("/serverCert")
                .request(String.class);

        assertThat(response.status(), is(Http.Status.OK_200));
        assertThat(response.entity(), is("X.509:CN=Helidon-Test-Server-Secondary|X.509:CN=Helidon-Test-CA"));
    }

    private static void sendCertificateString(Certificate[] certs, ServerResponse res) {
        if (certs == null) {
            res.status(Http.Status.BAD_REQUEST_400).send("Expected client certificate");
        } else {
            List<String> certDefs = new LinkedList<>();
            for (Certificate cert : certs) {
                if (cert instanceof X509Certificate x509) {
                    certDefs.add("X.509:" + x509.getSubjectX500Principal().getName());
                } else {
                    certDefs.add(cert.getType());
                }
            }

            res.send(String.join("|", certDefs));
        }
    }
}