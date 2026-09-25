/*
 * Copyright 2026 Martin Vysny
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.github.mvysny.tinymcpserver;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A least-recently-used map with a fixed maximum size. When an insertion
 * would exceed the cap, the least-recently <em>accessed</em> entry is dropped
 * (both {@link #put} and {@link #get} count as access).
 *
 * <p>Thread-safe: every method holds the intrinsic lock, which is cheap
 * enough at the caps used here (a few dozen entries).
 */
public final class BoundedLRUMap<K, V> {

    private final int cap;
    private final LinkedHashMap<K, V> map;

    /**
     * @param cap maximum number of entries; must be at least 1
     * @throws IllegalArgumentException if {@code cap < 1}
     */
    public BoundedLRUMap(int cap) {
        if (cap < 1) {
            throw new IllegalArgumentException("cap must be >= 1, was " + cap);
        }
        this.cap = cap;
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > BoundedLRUMap.this.cap;
            }
        };
    }

    /**
     * Inserts or replaces an entry, evicting the eldest if over the cap.
     *
     * @return the previous value for {@code key}, or {@code null}
     */
    public synchronized V put(K key, V value) {
        return map.put(key, value);
    }

    /** Returns the value for {@code key}, or {@code null}; counts as access. */
    public synchronized V get(K key) {
        return map.get(key);
    }

    public synchronized int size() {
        return map.size();
    }

    public int cap() {
        return cap;
    }
}
