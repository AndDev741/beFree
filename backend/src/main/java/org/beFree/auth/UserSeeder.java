package org.beFree.auth;

import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Creates the owner account on first start from befree.auth.initial-password,
 * so no password or hash is ever committed. With no password configured the
 * account is not created and nobody can sign in, which is the safe failure.
 */
@Singleton
public class UserSeeder {

    private static final Logger LOG = Logger.getLogger(UserSeeder.class);

    @ConfigProperty(name = "befree.owner")
    String owner;

    @ConfigProperty(name = "befree.auth.initial-password")
    java.util.Optional<String> initialPassword;

    @Transactional
    void onStart(@Observes StartupEvent event) {
        if (AppUser.count("username", owner) > 0) {
            return;
        }
        String password = initialPassword.filter(p -> !p.isBlank()).orElse(null);
        if (password == null) {
            LOG.warnf("No account for '%s' and BEFREE_PASSWORD is not set, so nobody can sign in. "
                    + "Set it once and restart to create the account.", owner);
            return;
        }
        AppUser user = new AppUser();
        user.username = owner;
        user.password = BcryptUtil.bcryptHash(password);
        user.role = "owner";
        user.persist();
        LOG.infof("Created the '%s' account from BEFREE_PASSWORD.", owner);
    }
}
