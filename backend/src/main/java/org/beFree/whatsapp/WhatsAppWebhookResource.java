package org.beFree.whatsapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.beFree.whatsapp.WebhookPayload.Change;
import org.beFree.whatsapp.WebhookPayload.Entry;
import org.beFree.whatsapp.WebhookPayload.InboundMessage;
import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Meta calls GET once to verify the subscription, then POSTs every event.
 * The only public-facing endpoint in beFree; the tunnel routes nothing else.
 */
@Path("/webhooks/whatsapp")
public class WhatsAppWebhookResource {

    private static final Logger LOG = Logger.getLogger(WhatsAppWebhookResource.class);

    @Inject
    WhatsAppConfig config;

    @Inject
    WhatsAppService service;

    @Inject
    ObjectMapper mapper;

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public Response verify(@QueryParam("hub.mode") String mode,
                           @QueryParam("hub.verify_token") String token,
                           @QueryParam("hub.challenge") String challenge) {
        boolean ok = "subscribe".equals(mode)
                && token != null
                && config.verifyToken().map(expected -> constantTimeEquals(expected, token)).orElse(false);
        return ok ? Response.ok(challenge).build() : Response.status(Response.Status.FORBIDDEN).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response receive(@HeaderParam("X-Hub-Signature-256") String signature, String rawBody) throws Exception {
        if (!signatureValid(signature, rawBody)) {
            LOG.warn("Rejected WhatsApp webhook call with missing or invalid signature");
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        WebhookPayload payload = mapper.readValue(rawBody, WebhookPayload.class);
        if (payload.entry() != null) {
            for (Entry entry : payload.entry()) {
                if (entry.changes() == null) continue;
                for (Change change : entry.changes()) {
                    if (change.value() == null || change.value().messages() == null) continue;
                    for (InboundMessage message : change.value().messages()) {
                        try {
                            service.handle(message);
                        } catch (Exception e) {
                            // Ack anyway: Meta retries non-2xx, and a poison message must not block the rest
                            LOG.errorf(e, "Failed to handle WhatsApp message %s", message.id());
                        }
                    }
                }
            }
        }
        return Response.ok().build();
    }

    private boolean signatureValid(String header, String body) throws Exception {
        if (header == null || !header.startsWith("sha256=") || config.appSecret().isEmpty()) {
            return false;
        }
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(config.appSecret().get().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        return constantTimeEquals(expected, header.substring("sha256=".length()));
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
