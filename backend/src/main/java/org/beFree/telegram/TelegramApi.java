package org.beFree.telegram;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;

/**
 * The slice of the Telegram Bot API beFree uses. Base URL comes from
 * quarkus.rest-client.telegram-api.url; the token is a path segment.
 */
@RegisterRestClient(configKey = "telegram-api")
@Path("/bot{token}")
public interface TelegramApi {

    @GET
    @Path("/getUpdates")
    UpdatesResponse getUpdates(@PathParam("token") String token,
                               @QueryParam("offset") Long offset,
                               @QueryParam("timeout") int timeoutSeconds);

    @POST
    @Path("/sendMessage")
    @Consumes(MediaType.APPLICATION_JSON)
    SendMessageResponse sendMessage(@PathParam("token") String token, SendMessageRequest body);

    record UpdatesResponse(boolean ok, List<Update> result) {
    }

    record Update(@JsonProperty("update_id") long updateId, Message message) {
    }

    record Message(long date, Chat chat, String text) {
    }

    record Chat(long id) {
    }

    record SendMessageRequest(@JsonProperty("chat_id") long chatId, String text) {
    }

    record SendMessageResponse(boolean ok) {
    }
}
