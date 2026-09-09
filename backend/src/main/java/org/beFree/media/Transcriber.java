package org.beFree.media;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Speech to text through any OpenAI-compatible /audio/transcriptions endpoint
 * (Groq's Whisper by default). Hand-built multipart: one small request shape,
 * no extra dependency, nothing for the native image to trip on.
 */
@ApplicationScoped
public class Transcriber {

    @ConfigProperty(name = "befree.transcription.base-url", defaultValue = "https://api.groq.com/openai/v1")
    String baseUrl;

    @ConfigProperty(name = "befree.transcription.api-key")
    Optional<String> apiKey;

    @ConfigProperty(name = "befree.transcription.model", defaultValue = "whisper-large-v3-turbo")
    String model;

    @ConfigProperty(name = "befree.transcription.language")
    Optional<String> language;

    @Inject
    ObjectMapper mapper;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public boolean enabled() {
        return apiKey.filter(k -> !k.isBlank()).isPresent();
    }

    public String transcribe(byte[] audio, String mimeType) throws IOException, InterruptedException {
        String boundary = "----befree" + UUID.randomUUID();
        String filename = "voice." + extensionFor(mimeType);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        field(body, boundary, "model", model);
        language.filter(l -> !l.isBlank()).ifPresent(l -> field(body, boundary, "language", l));
        field(body, boundary, "response_format", "json");
        write(body, "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + (mimeType == null ? "application/octet-stream" : mimeType) + "\r\n\r\n");
        body.writeBytes(audio);
        write(body, "\r\n--" + boundary + "--\r\n");

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$", "") + "/audio/transcriptions"))
                .header("Authorization", "Bearer " + apiKey.orElseThrow())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofSeconds(90))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("transcription failed: HTTP " + response.statusCode() + " " + response.body());
        }
        return mapper.readTree(response.body()).path("text").asText("");
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value) {
        write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
    }

    private static void write(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String extensionFor(String mimeType) {
        if (mimeType == null) return "ogg";
        String m = mimeType.toLowerCase();
        if (m.contains("ogg") || m.contains("opus")) return "ogg";
        if (m.contains("mpeg") || m.contains("mp3")) return "mp3";
        if (m.contains("mp4") || m.contains("m4a") || m.contains("aac")) return "m4a";
        if (m.contains("wav")) return "wav";
        if (m.contains("webm")) return "webm";
        return "ogg";
    }
}
