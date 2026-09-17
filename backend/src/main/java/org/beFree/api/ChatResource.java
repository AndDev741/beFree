package org.beFree.api;

import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.core.MediaType;
import org.beFree.assistant.AssistantService;
import org.beFree.auth.CurrentUser;
import org.beFree.media.MediaIngest;
import org.beFree.transaction.Source;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.jboss.logging.Logger;

import java.nio.file.Files;
import java.util.UUID;

/**
 * The assistant inside the app. Same thread as WhatsApp: both key the
 * conversation on the owner, so "apaga o ultimo" works from either channel.
 */
@Path("/api/chat")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ChatResource {

    private static final Logger LOG = Logger.getLogger(ChatResource.class);

    @Inject
    AssistantService assistant;

    @Inject
    CurrentUser currentUser;

    @Inject
    MediaIngest ingest;

    @POST
    public Dto.ChatReply send(Dto.ChatRequest req) {
        if (req == null || req.message() == null || req.message().isBlank()) {
            throw new BadRequestException("message is required");
        }
        return new Dto.ChatReply(answer(req.message().trim()));
    }

    /**
     * A photo, a voice note or a PDF from the app. It runs through the same
     * pipeline as WhatsApp, so a receipt read here and a receipt read there end
     * up as the same text in the same conversation.
     */
    @POST
    @Path("/media")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Dto.ChatReply media(@RestForm("file") FileUpload file, @RestForm("message") String caption) {
        if (file == null) {
            throw new BadRequestException("file is required");
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file.uploadedFile());
        } catch (Exception e) {
            LOG.warnf(e, "Could not read the uploaded file %s", file.fileName());
            throw new BadRequestException("could not read the uploaded file");
        }
        if (bytes.length == 0) {
            throw new BadRequestException("the uploaded file is empty");
        }
        var read = ingest.read(bytes, file.contentType(), file.fileName(), caption, "app-upload");
        return switch (read) {
            case MediaIngest.Result.Rejected(String reason) -> new Dto.ChatReply(reason);
            case MediaIngest.Result.Framed(String text) -> new Dto.ChatReply(answer(text));
        };
    }

    private String answer(String text) {
        if (!assistant.enabled()) {
            throw new ServiceUnavailableException("the assistant is not configured");
        }
        String owner = currentUser.name();
        try {
            return assistant.chat(owner, Source.MANUAL, "app-" + UUID.randomUUID(), text);
        } catch (Exception e) {
            LOG.warnf(e, "Assistant failed for an in-app message from %s", owner);
            throw new ServiceUnavailableException("the assistant could not answer right now");
        }
    }
}
