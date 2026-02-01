package software.coley.recaf.services.workspace.watch;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.cdi.EagerInitialization;
import software.coley.recaf.cdi.InitializationStage;
import software.coley.recaf.info.properties.builtin.InputFilePathProperty;
import software.coley.recaf.services.Service;
import software.coley.recaf.services.workspace.WorkspaceCloseListener;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.services.workspace.WorkspaceOpenListener;
import software.coley.recaf.services.workspace.io.ResourceImporter;
import software.coley.recaf.util.FxThreadUtil;
import software.coley.recaf.util.threading.ThreadPoolFactory;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Service that monitors workspace resources for external file changes.
 * When a file backing a workspace resource is modified externally, this service
 * will automatically reload the resource contents.
 *
 * @author vimasig
 */
@EagerInitialization(InitializationStage.AFTER_UI_INIT)
@ApplicationScoped
public class ResourceWatchingService implements Service, WorkspaceOpenListener, WorkspaceCloseListener {
    public static final String SERVICE_ID = "resource-watching";
    private static final Logger logger = Logging.get(ResourceWatchingService.class);
    private final Map<Workspace, WatchContext> watchContexts = new ConcurrentHashMap<>();
    private final ExecutorService watchPool = ThreadPoolFactory.newFixedThreadPool(SERVICE_ID, 2, false);
    private final ResourceImporter resourceImporter;
    private final ResourceWatchingConfig config;

    @Inject
    public ResourceWatchingService(@Nonnull WorkspaceManager workspaceManager,
            @Nonnull ResourceImporter resourceImporter,
            @Nonnull ResourceWatchingConfig config) {
        this.resourceImporter = resourceImporter;
        this.config = config;

        logger.info("Resource watching service initialized");

        // Register this service as a listener for workspace open/close events
        workspaceManager.addWorkspaceOpenListener(this);
        workspaceManager.addWorkspaceCloseListener(this);

        // Handle config changes
        config.getResourceWatching().addChangeListener((ob, old, cur) -> {
            if (cur) {
                // Re-enable watching for the current workspace if we have one
                if (workspaceManager.hasCurrentWorkspace()) {
                    onWorkspaceOpened(workspaceManager.getCurrent());
                }
            } else {
                // Disable all current watchers
                watchContexts.values().forEach(WatchContext::stop);
                watchContexts.clear();
            }
        });
    }

    @PreDestroy
    private void shutdown() {
        watchContexts.values().forEach(WatchContext::stop);
        watchContexts.clear();
        watchPool.shutdownNow();
    }

    @Override
    public void onWorkspaceOpened(@Nonnull Workspace workspace) {
        logger.info("Workspace opened, checking if resource watching should start...");
        if (!config.getResourceWatching().getValue()) {
            logger.info("Resource watching is disabled in config, skipping for workspace");
            return;
        }

        // Stop any prior context for this workspace (shouldn't happen, but just in
        // case)
        WatchContext existingContext = watchContexts.remove(workspace);
        if (existingContext != null) {
            existingContext.stop();
        }

        // Create a new watch context for this workspace
        logger.info("Creating watch context for workspace");
        WatchContext context = new WatchContext(workspace);
        watchContexts.put(workspace, context);
        context.start();
    }

    @Override
    public void onWorkspaceClosed(@Nonnull Workspace workspace) {
        WatchContext context = watchContexts.remove(workspace);
        if (context != null) {
            context.stop();
        }
    }

    @Nonnull
    @Override
    public String getServiceId() {
        return SERVICE_ID;
    }

    @Nonnull
    @Override
    public ResourceWatchingConfig getServiceConfig() {
        return config;
    }

    /**
     * Context for watching file changes for a specific workspace.
     */
    private class WatchContext {
        private final Workspace workspace;
        private final Map<Path, WatchEntry> watchedPaths = new ConcurrentHashMap<>();
        private volatile Future<?> watchFuture;
        private volatile WatchService watchService;
        private volatile boolean running;

        WatchContext(@Nonnull Workspace workspace) {
            this.workspace = workspace;
        }

        void start() {
            if (running)
                return;
            running = true;

            // Collect all file paths to watch from the workspace resources
            logger.info("Collecting paths from primary resource...");
            collectPaths(workspace.getPrimaryResource());
            for (WorkspaceResource library : workspace.getSupportingResources()) {
                collectPaths(library);
            }

            if (watchedPaths.isEmpty()) {
                logger.info("No file paths to watch for workspace");
                running = false;
                return;
            }

            // Start the watch task
            watchFuture = watchPool.submit(this::watch);
            logger.info("Started resource watching for {} path(s)", watchedPaths.size());
        }

        void stop() {
            running = false;
            if (watchService != null) {
                try {
                    watchService.close();
                } catch (IOException ex) {
                    logger.error("Failed to close watch service", ex);
                }
            }
            if (watchFuture != null) {
                watchFuture.cancel(true);
            }
            watchedPaths.clear();
            logger.debug("Stopped resource watching for workspace");
        }

        private void collectPaths(@Nonnull WorkspaceResource resource) {
            if (resource instanceof WorkspaceFileResource fileResource) {
                Path filePath = InputFilePathProperty.get(fileResource.getFileInfo());
                logger.debug("Checking resource file info: {}, path: {}",
                        fileResource.getFileInfo().getName(), filePath);
                if (filePath != null && Files.exists(filePath) && Files.isRegularFile(filePath)) {
                    Path normalizedPath = filePath.toAbsolutePath().normalize();
                    watchedPaths.put(normalizedPath, new WatchEntry(fileResource, filePath));
                    logger.info("Watching file for changes: {}", normalizedPath);
                } else if (filePath != null) {
                    logger.warn("File path exists but is not a regular file or doesn't exist: {}", filePath);
                } else {
                    logger.debug("No InputFilePathProperty set on resource file info: {}",
                            fileResource.getFileInfo().getName());
                }
            } else {
                logger.debug("Resource is not a WorkspaceFileResource: {}", resource.getClass().getSimpleName());
            }
        }

        private void watch() {
            try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
                this.watchService = watchService;

                // Register parent directories for all watched files
                Map<Path, WatchKey> registeredDirs = new ConcurrentHashMap<>();
                for (WatchEntry entry : watchedPaths.values()) {
                    Path parentDir = entry.path().getParent();
                    if (parentDir != null && !registeredDirs.containsKey(parentDir)) {
                        try {
                            WatchKey key = parentDir.register(watchService,
                                    StandardWatchEventKinds.ENTRY_MODIFY);
                            registeredDirs.put(parentDir, key);
                            logger.trace("Registered watch on directory: {}", parentDir);
                        } catch (IOException ex) {
                            logger.warn("Failed to register watch on directory: {}", parentDir, ex);
                        }
                    }
                }

                if (registeredDirs.isEmpty()) {
                    logger.debug("No directories could be registered for watching");
                    return;
                }

                // Process events
                long lastProcessedTime = 0;
                while (running) {
                    WatchKey key;
                    try {
                        key = watchService.poll(1, TimeUnit.SECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        break;
                    }

                    if (key == null)
                        continue;

                    for (WatchEvent<?> event : key.pollEvents()) {
                        WatchEvent.Kind<?> kind = event.kind();
                        if (kind == StandardWatchEventKinds.OVERFLOW) {
                            continue;
                        }

                        // Get the file that was modified
                        @SuppressWarnings("unchecked")
                        WatchEvent<Path> ev = (WatchEvent<Path>) event;
                        Path fileName = ev.context();
                        Path parentDir = getParentDirFromKey(registeredDirs, key);
                        if (parentDir == null)
                            continue;

                        Path fullPath = parentDir.resolve(fileName).toAbsolutePath().normalize();
                        WatchEntry entry = watchedPaths.get(fullPath);

                        if (entry != null && kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                            // Debounce: Skip if we processed this recently (within 500ms)
                            long currentTime = System.currentTimeMillis();
                            if (currentTime - lastProcessedTime < 500) {
                                continue;
                            }
                            lastProcessedTime = currentTime;

                            // File was modified, trigger reload
                            logger.info("Detected file change: {}", fullPath);
                            reloadResource(entry);
                        }
                    }

                    // Reset the key
                    boolean valid = key.reset();
                    if (!valid) {
                        logger.warn("Watch key became invalid");
                        // Remove from registered dirs
                        registeredDirs.values().remove(key);
                        if (registeredDirs.isEmpty()) {
                            break;
                        }
                    }
                }

                logger.info("Stopped watching resources for changes");
            } catch (IOException ex) {
                logger.error("IO exception in resource watch service", ex);
            } catch (ClosedWatchServiceException ignored) {
                // Expected when watch service is closed
            }
        }

        @Nullable
        private Path getParentDirFromKey(@Nonnull Map<Path, WatchKey> registeredDirs, @Nonnull WatchKey key) {
            for (Map.Entry<Path, WatchKey> entry : registeredDirs.entrySet()) {
                if (entry.getValue() == key) {
                    return entry.getKey();
                }
            }
            return null;
        }

        private void reloadResource(@Nonnull WatchEntry entry) {
            // Retry with backoff since the file might still be being written
            int maxRetries = 5;
            int retryDelayMs = 200;

            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    // Wait a bit before attempting to read (especially on retries)
                    if (attempt > 1) {
                        Thread.sleep(retryDelayMs);
                        retryDelayMs *= 2; // Exponential backoff
                    }

                    WorkspaceResource newResource = resourceImporter.importResource(entry.path());
                    if (newResource instanceof WorkspaceFileResource newFileResource) {
                        WorkspaceFileResource oldResource = entry.resource();

                        // Update bundles on the FX thread to ensure UI listeners are properly notified
                        FxThreadUtil.run(() -> {
                            // Copy classes from new resource to old resource's bundles
                            // The put() method will trigger bundle listeners which propagate
                            // to resource listeners, ultimately updating the UI
                            // After put(), decrement history to avoid marking as "dirty"
                            newFileResource.getJvmClassBundle().forEach((name, cls) -> {
                                oldResource.getJvmClassBundle().put(cls);
                                // Decrement history to reset dirty state - external reloads
                                // should establish a new baseline, not mark as modified
                                if (oldResource.getJvmClassBundle().hasHistory(name)) {
                                    oldResource.getJvmClassBundle().decrementHistory(name);
                                }
                            });

                            // Copy files from new resource to old resource's bundle
                            newFileResource.getFileBundle().forEach((name, file) -> {
                                oldResource.getFileBundle().put(file);
                                if (oldResource.getFileBundle().hasHistory(name)) {
                                    oldResource.getFileBundle().decrementHistory(name);
                                }
                            });

                            // Handle Android bundles
                            newFileResource.getAndroidClassBundles().forEach((name, bundle) -> {
                                if (oldResource.getAndroidClassBundles().containsKey(name)) {
                                    bundle.forEach((className, androidClass) -> {
                                        oldResource.getAndroidClassBundles().get(name).put(androidClass);
                                        if (oldResource.getAndroidClassBundles().get(name).hasHistory(className)) {
                                            oldResource.getAndroidClassBundles().get(name).decrementHistory(className);
                                        }
                                    });
                                }
                            });

                            logger.info("Successfully reloaded resource: {}", entry.path().getFileName());
                        });

                        return; // Success - exit the retry loop
                    }
                    return; // Not a file resource, nothing to do
                } catch (IOException ex) {
                    if (attempt < maxRetries) {
                        logger.debug("Retry {}/{} - Failed to reload resource (file may still be writing): {}",
                                attempt, maxRetries, entry.path().getFileName());
                    } else {
                        logger.error("Failed to reload resource after {} attempts: {}", maxRetries, entry.path(), ex);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    logger.warn("Resource reload interrupted: {}", entry.path());
                    return;
                }
            }
        }

        private record WatchEntry(@Nonnull WorkspaceFileResource resource, @Nonnull Path path) {
        }
    }
}
