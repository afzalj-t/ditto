/*
 * Copyright (c) 2017 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/index.php
 *
 * Contributors:
 *    Bosch Software Innovations GmbH - initial contribution
 */
package org.eclipse.ditto.services.authorization.util.cache;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.ditto.services.authorization.util.cache.entry.Entry;
import org.eclipse.ditto.services.models.authorization.EntityId;
import org.eclipse.ditto.services.utils.cache.Cache;

/**
 * Cache that returns the key as result.
 */
final class IdentityCache implements Cache<EntityId, Entry<EntityId>> {

    @Override
    public CompletableFuture<Optional<Entry<EntityId>>> get(final EntityId key) {
        return CompletableFuture.completedFuture(getBlocking(key));
    }

    @Override
    public Optional<Entry<EntityId>> getBlocking(final EntityId key) {
        return Optional.of(Entry.permanent(key));
    }

    @Override
    public void invalidate(final EntityId key) {
        // do nothing
    }

    @Override
    public void put(final EntityId key, final Entry<EntityId> value) {
        // do nothing
    }

    @Override
    public ConcurrentMap<EntityId, Entry<EntityId>> asMap() {
        throw new UnsupportedOperationException("IdentityCache may not be viewed as map");
    }
}
