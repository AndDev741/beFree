package org.beFree.whatsapp;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;

/**
 * Outbound slice of the WhatsApp Cloud API (Graph API). Base URL, including
 * the Graph version, comes from quarkus.rest-client.whatsapp-api.url.
 */
@RegisterRestClient(configKey = "whatsapp-api")
@Path("/")
public interface WhatsAppApi {

    @POST
    @Path("/{phoneNumberId}/messages")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    SendResponse sendMessage(@PathParam("phoneNumberId") String phoneNumberId,
                             @HeaderParam("Authorization") String bearerToken,
                             SendTextRequest body);

    /** Resolves a media id to a short-lived download URL (about 5 minutes). */
    @GET
    @Path("/{mediaId}")
    @Produces(MediaType.APPLICATION_JSON)
    MediaInfo mediaInfo(@PathParam("mediaId") String mediaId,
                        @HeaderParam("Authorization") String bearerToken);

    record MediaInfo(String url, @JsonProperty("mime_type") String mimeType,
                     @JsonProperty("file_size") long fileSize, String id) {
    }

    record SendTextRequest(@JsonProperty("messaging_product") String messagingProduct,
                           String to,
                           String type,
                           Text text) {
        static SendTextRequest text(String to, String body) {
            return new SendTextRequest("whatsapp", to, "text", new Text(body));
        }
    }

    record Text(String body) {
    }

    record SendResponse(List<MessageId> messages) {
    }

    record MessageId(String id) {
    }
}
