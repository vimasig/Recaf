package software.coley.recaf.services.workspace.watch;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.observables.ObservableBoolean;
import software.coley.recaf.config.BasicConfigContainer;
import software.coley.recaf.config.BasicConfigValue;
import software.coley.recaf.config.ConfigGroups;
import software.coley.recaf.services.ServiceConfig;

/**
 * Configuration for {@link ResourceWatchingService}.
 *
 * @author vimasig
 */
@ApplicationScoped
public class ResourceWatchingConfig extends BasicConfigContainer implements ServiceConfig {
    public static final String SERVICE_ID = "resource-watching";
    private final ObservableBoolean resourceWatching = new ObservableBoolean(true);

    @Inject
    public ResourceWatchingConfig() {
        super(ConfigGroups.SERVICE_IO, SERVICE_ID + CONFIG_SUFFIX);
        addValue(new BasicConfigValue<>("resource-watching", boolean.class, resourceWatching));
    }

    /**
     * @return Observable boolean for enabling/disabling resource file watching.
     */
    @Nonnull
    public ObservableBoolean getResourceWatching() {
        return resourceWatching;
    }
}
