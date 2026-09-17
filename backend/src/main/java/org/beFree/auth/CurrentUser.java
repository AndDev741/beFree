package org.beFree.auth;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Who owns the row being written.
 *
 * Every row carries an owner from day one, which is the half of a multi-user
 * migration that is expensive to retrofit. Reads are not filtered by owner yet
 * because there is exactly one; that filter is a WHERE clause to add when a
 * second user exists.
 *
 * The WhatsApp channel runs on a background thread with no HTTP request and so
 * no security identity: it writes as the configured owner. Mapping a phone
 * number to a user is the other half of going multi-user.
 */
@ApplicationScoped
public class CurrentUser {

    @Inject
    SecurityIdentity identity;

    @ConfigProperty(name = "befree.owner")
    String configuredOwner;

    public String name() {
        try {
            if (identity != null && !identity.isAnonymous() && identity.getPrincipal() != null) {
                return identity.getPrincipal().getName();
            }
        } catch (Exception ignored) {
            // No request context (the WhatsApp worker thread); fall through.
        }
        return configuredOwner;
    }
}
