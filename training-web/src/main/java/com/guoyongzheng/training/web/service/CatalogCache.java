package com.guoyongzheng.training.web.service;

import com.guoyongzheng.training.catalog.CatalogLoader;
import com.guoyongzheng.training.catalog.TrainingCatalog;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Thread-safe lazily-initialized catalog cache. The question indexes are read-only
 * JSON files that never change during runtime, so a single load is sufficient.
 */
@Component
public class CatalogCache {
    private final WorkspaceLocator workspaceLocator;
    private final CatalogLoader catalogLoader = new CatalogLoader();

    private volatile TrainingCatalog cached;

    public CatalogCache(WorkspaceLocator workspaceLocator) {
        this.workspaceLocator = workspaceLocator;
    }

    public TrainingCatalog get() {
        TrainingCatalog local = cached;
        if (local == null) {
            synchronized (this) {
                local = cached;
                if (local == null) {
                    Path workspace = workspaceLocator.locate();
                    local = catalogLoader.load(workspace);
                    cached = local;
                }
            }
        }
        return local;
    }

    /** Forces a reload on next access (useful for tests or admin refresh). */
    public synchronized void invalidate() {
        cached = null;
    }
}
