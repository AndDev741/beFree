package org.beFree.whatsapp;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.beFree.whatsapp.WhatsAppApi.MediaInfo;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Two-step media download: the Graph API turns a media id into a temporary
 * URL on a different host, so the second hop uses the JDK client.
 */
@ApplicationScoped
public class WhatsAppMedia {

    /** Meta caps images at 5 MB; this only guards against surprises. */
    private static final long MAX_BYTES = 10L * 1024 * 1024;

    @Inject
    WhatsAppConfig config;

    @Inject
    @RestClient
    WhatsAppApi api;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public record Downloaded(byte[] bytes, String mimeType) {
    }

    public Downloaded download(String mediaId) throws IOException, InterruptedException {
        String bearer = "Bearer " + config.accessToken()
                .orElseThrow(() -> new IllegalStateException("whatsapp.access-token not configured"));
        MediaInfo info = api.mediaInfo(mediaId, bearer);
        if (info == null || info.url() == null) {
            throw new IOException("no download URL for media " + mediaId);
        }
        if (info.fileSize() > MAX_BYTES) {
            throw new IOException("media " + mediaId + " too large: " + info.fileSize() + " bytes");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(info.url()))
                .header("Authorization", bearer)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("media download failed: HTTP " + response.statusCode());
        }
        return new Downloaded(response.body(), info.mimeType() != null ? info.mimeType() : "image/jpeg");
    }
}
