package com.automation.grafana.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;

@Component
public class GrafanaClient {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpClient createHttpClient() throws Exception {

        TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {}

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
        };

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustAllCerts, new SecureRandom());

        SSLParameters sslParameters = new SSLParameters();
        sslParameters.setEndpointIdentificationAlgorithm("");

        return HttpClient.newBuilder()
                // Avoid HTTP/2 negotiation quirks through intermediate
                // proxies/ingress that don't fully support it.
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .sslContext(sslContext)
                .sslParameters(sslParameters)
                .build();
    }

    private RestClient client(String baseUrl, String token) throws Exception {

        return RestClient.builder()
                .requestFactory(new JdkClientHttpRequestFactory(createHttpClient()))
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .requestInterceptor(loggingInterceptor())
                .build();
    }

    /**
     * Logs the literal bytes handed to the transport. Kept from the last
     * round of debugging - if a future body ever looks wrong again, this
     * is ground truth, not a separately re-serialized guess.
     */
    private ClientHttpRequestInterceptor loggingInterceptor() {

        return (request, body, execution) -> {

            if (body != null && body.length > 0) {

                System.out.println("===== WIRE REQUEST "
                        + request.getMethod() + " " + request.getURI()
                        + " (" + body.length + " bytes) =====");

                System.out.println(new String(body, StandardCharsets.UTF_8));
            }

            return execution.execute(request, body);
        };
    }

    /**
     * Serializes with our own ObjectMapper and sends the result as a plain
     * String body. This sidesteps whichever HttpMessageConverter RestClient
     * would otherwise auto-select for an Object/JsonNode body - in this
     * project that converter was reflecting over JsonNode's isXxx() methods
     * instead of using Jackson's built-in JsonNode serialization, producing
     * a payload like {"array":false,"object":true,"nodeType":"OBJECT",...}
     * instead of the actual dashboard JSON. A String body only ever goes
     * through the String message converter, which just writes the bytes.
     */
    private String toJson(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    public String get(String baseUrl, String token, String uri) throws Exception {

        return client(baseUrl, token)
                .get()
                .uri(uri)
                .retrieve()
                .body(String.class);
    }

    public String post(String baseUrl, String token, String uri, Object body) throws Exception {

        String json = toJson(body);

        System.out.println("===== POST BODY (" + uri + ") =====");
        System.out.println(json);

        try {
            return client(baseUrl, token)
                    .post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json)
                    .retrieve()
                    .body(String.class);

        } catch (HttpStatusCodeException ex) {

            System.out.println("HTTP Status : " + ex.getStatusCode());
            System.out.println("Response Body:");
            System.out.println(ex.getResponseBodyAsString());

            throw ex;
        }
    }

    public String put(String baseUrl, String token, String path, Object body) throws Exception {

        String json = toJson(body);

        System.out.println("===== PUT BODY (" + path + ") =====");
        System.out.println(json);

        try {
            return client(baseUrl, token)
                    .put()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json)
                    .retrieve()
                    .body(String.class);

        } catch (HttpStatusCodeException ex) {

            System.out.println("HTTP Status : " + ex.getStatusCode());
            System.out.println("Response Body:");
            System.out.println(ex.getResponseBodyAsString());

            throw ex;
        }
    }
}