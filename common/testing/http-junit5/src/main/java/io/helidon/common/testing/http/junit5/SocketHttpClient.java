/*
 * Copyright (c) 2017, 2022 Oracle and/or its affiliates.
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

package io.helidon.common.testing.http.junit5;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.helidon.common.http.ClientResponseHeaders;
import io.helidon.common.http.Http;
import io.helidon.common.http.WritableHeaders;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * The SocketHttpClient provides means to simply pass any bytes over the network
 * and to see how a server deals with such a case.
 */
public class SocketHttpClient implements AutoCloseable {

    private static final System.Logger LOGGER = System.getLogger(SocketHttpClient.class.getName());

    private static final String EOL = "\r\n";
    private static final Pattern FIRST_LINE_PATTERN = Pattern.compile("HTTP/\\d+\\.\\d+ (\\d\\d\\d) (.*)");
    private final String host;
    private final int port;
    private final Duration timeout;

    private Socket socket;
    private boolean connected;

    protected SocketHttpClient(String host, int port, Duration timeout) {
        this.host = host;
        this.port = port;
        this.timeout = timeout;
    }

    /**
     * Socket client that allows sending any content.
     *
     * @param host    host to connect to
     * @param port    port to connect to
     * @param timeout socket timeout
     * @return a new (disconnected) client
     */
    public static SocketHttpClient create(String host, int port, Duration timeout) {
        return new SocketHttpClient(host, port, timeout);
    }

    /**
     * Socket client that allows sending any content.
     * Uses localhost and timeout of 5 seconds.
     *
     * @param port    port to connect to
     * @return a new (disconnected) client
     */
    public static SocketHttpClient create(int port) {
        return create("localhost", port, Duration.ofSeconds(5));
    }

    /**
     * Generates at least {@code bytes} number of bytes as a sequence of decimal numbers delimited
     * by newlines.
     *
     * @param bytes the amount of bytes to generate (might get little bit more than that)
     * @return the generated bytes as a sequence of decimal numbers delimited by newlines
     */
    public static StringBuilder longData(int bytes) {
        StringBuilder data = new StringBuilder(bytes);
        for (int i = 0; data.length() < bytes; ++i) {
            data.append(i)
                    .append("\n");
        }
        return data;
    }

    /**
     * Find headers in response and parse them.
     *
     * @param response full HTTP response
     * @return headers map
     */
    public static ClientResponseHeaders headersFromResponse(String response) {
        WritableHeaders<?> headers = WritableHeaders.create();

        assertThat(response, notNullValue());
        int index = response.indexOf("\n\n");
        if (index < 0) {
            throw new AssertionError("Missing end of headers in response!");
        }
        String hdrsPart = response.substring(0, index);
        String[] lines = hdrsPart.split("\\n");
        if (lines.length <= 1) {
            return ClientResponseHeaders.create(headers);
        }

        boolean first = true;
        for (String line : lines) {
            if (first) {
                first = false;
                continue;
            }
            int i = line.indexOf(':');
            if (i < 0) {
                throw new AssertionError("Header without semicolon - " + line);
            }
            headers.add(Http.Header.create(line.substring(0, i).trim()), line.substring(i + 1).trim());
        }
        return ClientResponseHeaders.create(headers);
    }

    /**
     * Find the status line and return response HTTP status.
     *
     * @param response full HTTP response
     * @return status
     */
    public static Http.Status statusFromResponse(String response) {
        // response should start with HTTP/1.1 000 reasonPhrase\n
        int eol = response.indexOf('\n');
        assertThat("There must be at least a line end after first line: " + response, eol > -1);
        String firstLine = response.substring(0, eol).trim();

        Matcher matcher = FIRST_LINE_PATTERN.matcher(firstLine);
        assertThat("Status line must match the patter of 'HTTP/0.0 000 ReasonPhrase', but is: " + response,
                   matcher.matches());

        int statusCode = Integer.parseInt(matcher.group(1));
        String phrase = matcher.group(2);

        return Http.Status.create(statusCode, phrase);
    }

    /**
     * Get entity from response.
     *
     * @param response             response with initial line, headers, and entity
     * @param validateHeaderFormat whether to validate headers are correctly formatted
     * @return entity string
     */
    public static String entityFromResponse(String response, boolean validateHeaderFormat) {
        assertThat(response, notNullValue());
        int index = response.indexOf("\n\n");
        if (index < 0) {
            throw new AssertionError("Missing end of headers in response!");
        }
        if (validateHeaderFormat) {
            String headers = response.substring(0, index);
            String[] lines = headers.split("\\n");
            assertThat(lines[0], startsWith("HTTP/"));
            for (int i = 1; i < lines.length; i++) {
                assertThat(lines[i], containsString(":"));
            }
        }

        return response.substring(index + 2);
    }

    @Override
    public void close() throws Exception {
        disconnect();
    }

    /**
     * Assert that the socket is working and open.
     */
    public void assertConnectionIsOpen() {
        // get
        request(Http.Method.GET, "/this/path/should/not/exist", null);
        // assert
        assertThat(receive(), containsString("HTTP/1.1"));
    }

    /**
     * Assert that the socket is closed.
     */
    public void assertConnectionIsClosed() {
        // get
        request(Http.Method.POST, null);
        // assert
        try {
            // when the connection is closed before we start reading, just "" is returned by receive()
            assertThat(receive(), is(""));
        } catch (UncheckedIOException e) {
            if (e.getCause() instanceof SocketException) {
                // "Connection reset" exception is thrown in case we were fast enough and started receiving the response
                // before it was closed
                LOGGER.log(System.Logger.Level.TRACE, "Received: " + e.getMessage());
            } else {
                throw e;
            }
        }
    }

    /**
     * A helper method that sends the given payload with the provided method to the server.
     *
     * @param method  the http method
     * @param payload the payload to send (must be without the newlines;
     *                otherwise it's not a valid payload)
     * @return the exact string returned by webserver (including {@code HTTP/1.1 200 OK} line for instance)
     */
    public String sendAndReceive(Http.Method method, String payload) {
        return sendAndReceive("/", method, payload);
    }

    /**
     * A helper method that sends the given payload at the given path with the provided method and headers to the server.
     *
     * @param path    the path to access
     * @param method  the http method
     * @param payload the payload to send (must be without the newlines;
     *                otherwise it's not a valid payload)
     * @return the exact string returned by webserver (including {@code HTTP/1.1 200 OK} line for instance)
     */
    public String sendAndReceive(String path, Http.Method method, String payload) {
        return sendAndReceive(path, method, payload, Collections.emptyList());
    }

    /**
     * A helper method that sends the given payload at the given path with the provided method to the server.
     *
     * @param path    the path to access
     * @param method  the http method
     * @param payload the payload to send (must be without the newlines;
     *                otherwise it's not a valid payload)
     * @param headers HTTP request headers
     * @return the exact string returned by webserver (including {@code HTTP/1.1 200 OK} line for instance)
     */
    public String sendAndReceive(String path,
                                 Http.Method method,
                                 String payload,
                                 Iterable<String> headers) {

        request(method, path, payload, headers);

        return receive();
    }

    /**
     * Read the data from the socket. If socket is closed, an empty string is returned.
     *
     * @return the read data
     */
    public String receive() {
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String t;
            boolean ending = false;
            int contentLength = -1;
            while ((t = br.readLine()) != null) {

                LOGGER.log(System.Logger.Level.TRACE, "Received: " + t);

                if (t.toLowerCase().startsWith("content-length")) {
                    int k = t.indexOf(':');
                    contentLength = Integer.parseInt(t.substring(k + 1).trim());
                }

                sb.append(t)
                        .append("\n");

                if ("".equalsIgnoreCase(t) && contentLength >= 0) {
                    char[] content = new char[contentLength];
                    int expected = contentLength;
                    while (expected != 0) {
                        int read = br.read(content);
                        if (read == 0) {
                            throw new IllegalStateException("Read zero bytes from a blocking stream, this is a bug");
                        }
                        if (read == -1) {
                            throw new IllegalStateException("Received end of stream while expecting more bytes");
                        }
                        sb.append(content, 0, read);
                        expected -= read;
                    }
                    break;
                }
                if (ending && "".equalsIgnoreCase(t)) {
                    break;
                }
                if (!ending && ("0".equalsIgnoreCase(t))) {
                    ending = true;
                }
            }
            return sb.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Sends a request to the server.
     *
     * @param method the http method
     */
    public void request(Http.Method method) {
        request(method, null);
    }

    /**
     * Sends a request to the server.
     *
     * @param method  the http method
     * @param payload the payload to send (must be without the newlines;
     *                otherwise it's not a valid payload)
     */
    public void request(Http.Method method, String payload) {
        request(method, "/", payload);
    }

    /**
     * Sends a request to the server.
     *
     * @param path    the path to access
     * @param method  the http method
     * @param payload the payload to send (must be without the newlines;
     *                otherwise it's not a valid payload)
     */
    public void request(Http.Method method, String path, String payload) {
        request(method, path, payload, List.of("Content-Type: application/x-www-form-urlencoded"));
    }

    /**
     * Sends a request to the server.
     *
     * @param path    the path to access
     * @param method  the http method
     * @param payload the payload to send (must be without the newlines;
     *                otherwise it's not a valid payload)
     * @param headers the headers (e.g., {@code Content-Type: application/json})
     */
    public void request(Http.Method method, String path, String payload, Iterable<String> headers) {
        request(method.text(), path, "HTTP/1.1", "127.0.0.1", headers, payload);
    }

    /**
     * Send raw data to the server.
     *
     * @param method   HTTP Method
     * @param path     path
     * @param protocol protocol
     * @param host     host header value (if null, host header is not sent)
     * @param headers  headers (if null, additional headers are not sent)
     * @param payload  entity (if null, entity is not sent)
     */
    public void request(String method, String path, String protocol, String host, Iterable<String> headers, String payload) {
        if (socket == null) {
            connect();
        }

        try {
            List<String> usedHeaders = new LinkedList<>();
            if (headers != null) {
                headers.forEach(usedHeaders::add);
            }
            if (host != null) {
                usedHeaders.add(0, "Host: " + host);
            }
            PrintWriter pw = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            pw.print(method);
            pw.print(" ");
            pw.print(path);
            pw.print(" ");
            pw.print(protocol);
            pw.print(EOL);

            for (String header : usedHeaders) {
                pw.print(header);
                pw.print(EOL);
            }

            sendPayload(pw, payload);

            pw.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Disconnect from server socket.
     */
    public void disconnect() {
        if (socket == null) {
            return;
        }
        try {
            connected = false;
            socket.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        socket = null;
    }

    /**
     * Connect with default timeout.
     */
    public void connect() {
        connect(timeout);
    }

    /**
     * Connect with custom connect timeout.
     *
     * @param timeout timeout to use
     */
    public void connect(Duration timeout) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                // ignored
            }
            connected = false;
        }
        try {
            socket = new Socket(host, port);
            socket.setSoTimeout((int) timeout.toMillis());
            connected = true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Whether this client is connected.
     *
     * @return whether connected to server socket
     */
    public boolean connected() {
        return connected;
    }

    /**
     * Override this to send a specific payload.
     *
     * @param pw      the print writer where to write the payload
     * @param payload the payload as provided in the {@link #receive()} methods.
     */
    protected void sendPayload(PrintWriter pw, String payload) {
        if (payload == null) {
            // end headers
            pw.print(EOL);
        } else {
            pw.print("Content-Length: " + payload.length());
            pw.print(EOL);
            pw.print(EOL);
            pw.print(payload);
        }
    }
}