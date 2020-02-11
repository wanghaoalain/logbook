package org.zalando.logbook.netty;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.web.embedded.netty.NettyWebServer;
import org.springframework.boot.web.server.WebServer;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.adapter.HttpWebHandlerAdapter;
import org.zalando.logbook.Correlation;
import org.zalando.logbook.DefaultHttpLogFormatter;
import org.zalando.logbook.DefaultSink;
import org.zalando.logbook.HttpLogWriter;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.Precorrelation;
import org.zalando.logbook.TestStrategy;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

import java.io.IOException;
import java.time.Duration;

import static java.lang.String.format;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.web.reactive.function.server.RequestPredicates.path;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;
import static org.springframework.web.reactive.function.server.RouterFunctions.toWebHandler;
import static org.springframework.web.reactive.function.server.ServerResponse.noContent;
import static org.springframework.web.reactive.function.server.ServerResponse.ok;

final class LogbookClientHandlerSpringWebClientTest {

    private final HttpLogWriter writer = mock(HttpLogWriter.class);

    private final Logbook logbook = Logbook.builder()
            .strategy(new TestStrategy())
            .sink(new DefaultSink(new DefaultHttpLogFormatter(), writer))
            .build();

    private final WebServer server = new NettyWebServer(
            HttpServer.create(),
            new ReactorHttpHandlerAdapter(
                    new HttpWebHandlerAdapter(toWebHandler(route()
                            .route(path("/discard"), request ->
                                    noContent().build())
                            .route(path("/echo"), request ->
                                    ok().body(request.bodyToMono(String.class), String.class))
                            .build()))),
            Duration.ofSeconds(1));

    private final WebClient client;

    LogbookClientHandlerSpringWebClientTest() {
        this.server.start();
        this.client = WebClient.builder()
                .baseUrl("http://localhost:" + server.getPort())
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create()
                        .tcpConfiguration(tcpClient ->
                                tcpClient.doOnConnected(connection ->
                                        connection.addHandlerLast(new LogbookClientHandler(logbook))))))
                .build();
    }

    @BeforeEach
    void defaultBehaviour() {
        when(writer.isActive()).thenCallRealMethod();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void shouldLogRequestWithoutBody() throws IOException {
        sendAndReceive();

        final String message = captureRequest();

        assertThat(message, startsWith("Outgoing Request:"));
        assertThat(message, containsString(format("GET http://localhost:%d/echo HTTP/1.1", server.getPort())));
        assertThat(message, not(containsStringIgnoringCase("Content-Type")));
        assertThat(message, not(containsString("Hello, world!")));
    }

    @Test
    void shouldLogRequestWithBody() throws IOException {
        client.post()
                .uri("/discard")
                .bodyValue("Hello, world!")
                .exchange()
                .block();

        final String message = captureRequest();

        assertThat(message, startsWith("Outgoing Request:"));
        assertThat(message, containsString(format("POST http://localhost:%d/discard HTTP/1.1", server.getPort())));
        assertThat(message, containsStringIgnoringCase("Content-Type: text/plain"));
        assertThat(message, containsString("Hello, world!"));
    }

    private String captureRequest() throws IOException {
        final ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(writer).write(any(Precorrelation.class), captor.capture());
        return captor.getValue();
    }

    @Test
    void shouldNotLogRequestIfInactive() throws IOException {
        when(writer.isActive()).thenReturn(false);

        sendAndReceive();

        verify(writer, never()).write(any(Precorrelation.class), any());
    }

    @Test
    void shouldLogResponseWithoutBody() throws IOException {
        sendAndReceive("/discard");

        final String message = captureResponse();

        assertThat(message, startsWith("Incoming Response:"));
        assertThat(message, containsString("HTTP/1.1 204 No Content"));
        assertThat(message, not(containsStringIgnoringCase("Content-Type")));
        assertThat(message, not(containsString("Hello, world!")));
    }

    @Test
    void shouldLogResponseWithBody() throws IOException {
        final String response = client.post()
                .uri("/echo")
                .bodyValue("Hello, world!")
                .retrieve()
                .bodyToMono(String.class)
                .block();

        assertThat(response, is("Hello, world!"));

        final String message = captureResponse();

        assertThat(message, startsWith("Incoming Response:"));
        assertThat(message, containsString("HTTP/1.1 200 OK"));
        assertThat(message, containsStringIgnoringCase("Content-Type: text/plain"));
        assertThat(message, containsString("Hello, world!"));
    }

    private String captureResponse() throws IOException {
        final ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(writer).write(any(Correlation.class), captor.capture());
        return captor.getValue();
    }

    @Test
    void shouldNotLogResponseIfInactive() throws IOException {
        when(writer.isActive()).thenReturn(false);

        sendAndReceive();

        verify(writer, never()).write(any(Correlation.class), any());
    }

    @Test
    void shouldIgnoreBodies() throws IOException {
        final String response = client.post()
                .uri("/echo")
                .header("Ignore", "true")
                .bodyValue("Hello, world!")
                .retrieve()
                .bodyToMono(String.class)
                .block();

        assertThat(response, is("Hello, world!"));

        {
            final String message = captureRequest();

            assertThat(message, startsWith("Outgoing Request:"));
            assertThat(message, containsString(format("POST http://localhost:%d/echo HTTP/1.1", server.getPort())));
            assertThat(message, containsStringIgnoringCase("Content-Type: text/plain"));
            assertThat(message, not(containsString("Hello, world!")));
        }

        {
            final String message = captureResponse();

            assertThat(message, startsWith("Incoming Response:"));
            assertThat(message, containsString("HTTP/1.1 200 OK"));
            assertThat(message, containsStringIgnoringCase("Content-Type: text/plain"));
            assertThat(message, not(containsString("Hello, world!")));
        }
    }

    private void sendAndReceive() {
        sendAndReceive("/echo");
    }

    private void sendAndReceive(final String uri) {
        client.get()
                .uri(uri)
                .retrieve()
                .bodyToMono(Void.class)
                .block();
    }

}
