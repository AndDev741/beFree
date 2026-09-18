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
import org.beFree.calendar.MonthCycle;
import org.jboss.resteasy.reactive.RestResponse;

/** Lets the SPA ask "am I signed in?" without guessing from a 401 elsewhere. */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class SessionResource {

    @Inject
    SecurityIdentity identity;

    @Inject
    AssistantService assistant;

    @Inject
    MonthCycle cycle;

    /**
     * Typed on purpose. A bare Response hides the body type, so the native
     * image never registers it for reflection and every call 500s with
     * "no serializer found" while JVM mode looks perfectly healthy.
     */
    @GET
    @Path("/session")
    @PermitAll
    public RestResponse<Dto.Session> get() {
        if (identity == null || identity.isAnonymous()) {
            return RestResponse.status(RestResponse.Status.UNAUTHORIZED);
        }
        return RestResponse.ok(new Dto.Session(identity.getPrincipal().getName(), assistant.enabled(), cycle.defaultStartDay()));
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
