package org.beFree.api;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import org.beFree.assistant.AssistantService;

/** Lets the SPA ask "am I signed in?" without guessing from a 401 elsewhere. */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class SessionResource {

    @Inject
    SecurityIdentity identity;

    @Inject
    AssistantService assistant;

    @GET
    @Path("/session")
    @PermitAll
    public Response get() {
        if (identity == null || identity.isAnonymous()) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        return Response.ok(new Dto.Session(identity.getPrincipal().getName(), assistant.enabled())).build();
    }

    /** Expires the session cookie; the browser then has nothing to send. */
    @POST
    @Path("/logout")
    @PermitAll
    public Response logout() {
        return Response.noContent()
                .cookie(new NewCookie.Builder("befree-session")
                        .value("")
                        .path("/")
                        .maxAge(0)
                        .httpOnly(true)
                        .build())
                .build();
    }
}
