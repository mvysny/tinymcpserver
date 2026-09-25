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

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BoundedLRUMapTest {

    @Test
    void rejectsNonPositiveCap() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedLRUMap<>(0));
        assertThrows(IllegalArgumentException.class, () -> new BoundedLRUMap<>(-1));
    }

    @Test
    void putGet() {
        BoundedLRUMap<String, String> m = new BoundedLRUMap<>(4);
        m.put("a", "A");
        m.put("b", "B");
        assertEquals("A", m.get("a"));
        assertEquals("B", m.get("b"));
        assertNull(m.get("missing"));
        assertEquals(2, m.size());
        assertEquals(4, m.cap());
    }

    @Test
    void evictsLeastRecentlyUsedAtCap() {
        BoundedLRUMap<Integer, Integer> m = new BoundedLRUMap<>(3);
        m.put(1, 10);
        m.put(2, 20);
        m.put(3, 30);
        // The get makes 1 most recently used, so 4 evicts 2.
        assertEquals(10, m.get(1));
        m.put(4, 40);

        assertEquals(10, m.get(1));
        assertNull(m.get(2), "least-recently-used entry should have been evicted");
        assertEquals(30, m.get(3));
        assertEquals(40, m.get(4));
        assertEquals(3, m.size());
    }

    @Test
    void overflowingByMultipleDropsOldest() {
        BoundedLRUMap<Integer, Integer> m = new BoundedLRUMap<>(2);
        for (int i = 0; i < 100; i++) {
            m.put(i, i);
        }
        assertEquals(2, m.size());
        assertNotNull(m.get(99));
        assertNotNull(m.get(98));
        assertNull(m.get(0));
        assertNull(m.get(50));
    }

    @Test
    void concurrentPutsRespectCap() throws Exception {
        // Asserts only the size invariant, not which entries survive.
        int cap = 8;
        BoundedLRUMap<Integer, Integer> m = new BoundedLRUMap<>(cap);
        int threads = 16;
        int writesPerThread = 5_000;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger maxObservedSize = new AtomicInteger();
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            workers[t] = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < writesPerThread; i++) {
                    m.put(tid * writesPerThread + i, i);
                    int s = m.size();
                    int prev;
                    do {
                        prev = maxObservedSize.get();
                        if (s <= prev) break;
                    } while (!maxObservedSize.compareAndSet(prev, s));
                }
            }, "lru-w-" + t);
            workers[t].start();
        }
        start.countDown();
        for (Thread w : workers) w.join();
        assertEquals(cap, m.size(), "size should settle at cap");
        // put trims under the same lock size() takes, so the transient cap+1 is
        // never observable.
        org.junit.jupiter.api.Assertions.assertTrue(maxObservedSize.get() <= cap,
                "size should never exceed cap; observed " + maxObservedSize.get());
    }
}
