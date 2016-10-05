/*
 * (C) Copyright 2012-2016 Nuxeo SA (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Anahide Tchertchian
 *     Florent Guillaume
 */
package org.nuxeo.ecm.directory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Generic {@link BaseDirectoryDescriptor} registry holding registered descriptors and instantiated {@link Directory}
 * objects.
 * <p>
 * The directory descriptors have two special boolean flags that control how merge works:
 * <ul>
 * <li>{@code remove="true"}: this removes the definition of the directory. The next definition (if any) will be done
 * from scratch.
 * <li>{@code template="true"}: this defines an abstract descriptor which cannot be directly instantiated as a
 * directory. However another descriptor can extend it through {@code extends="templatename"} to inherit all its
 * properties.
 * </ul>
 *
 * @since 8.2
 */
public class DirectoryRegistry {

    private static final Log log = LogFactory.getLog(DirectoryRegistry.class);

    /** All descriptors registered. */
    // used under synchronization
    protected Map<String, List<BaseDirectoryDescriptor>> allDescriptors = new HashMap<>();

    /** Effective descriptors. */
    // used under synchronization
    protected Map<String, BaseDirectoryDescriptor> descriptors = new HashMap<>();

    /** Effective instantiated directories. */
    // used under synchronization
    protected Map<String, Directory> directories = new HashMap<>();

    public synchronized void addContribution(BaseDirectoryDescriptor contrib) {
        String id = contrib.name;
        log.info("Registered directory" + (contrib.template ? " template" : "") + ": " + id);
        allDescriptors.computeIfAbsent(id, k -> new ArrayList<>()).add(contrib);
        contributionChanged(contrib);
    }

    public synchronized void removeContribution(BaseDirectoryDescriptor contrib) {
        String id = contrib.name;
        log.info("Unregistered directory" + (contrib.template ? " template" : "") + ": " + id);
        allDescriptors.getOrDefault(id, Collections.emptyList()).remove(contrib);
        contributionChanged(contrib);
    }

    protected void contributionChanged(BaseDirectoryDescriptor contrib) {
        Set<String> done = new HashSet<>();
        Deque<String> todo = new LinkedList<String>(Collections.singleton(contrib.name));
        while (!todo.isEmpty()) {
            String id = todo.pop();
            if (!done.add(id)) {
                continue;
            }
            contrib = recomputeDescriptor(id);
            if (contrib != null && contrib.template) {
                // find dependent directories
                allDescriptors.values().forEach(
                        contribList -> contribList.stream().filter(c -> id.equals(c.extendz)).forEach(
                                c -> todo.add(c.name)));
            }
        }
    }

    protected void removeDirectory(String id) {
        Directory dir = directories.remove(id);
        if (dir != null) {
            shutdownDirectory(dir);
        }
    }

    /** Recomputes the effective descriptor for a directory id. */
    protected BaseDirectoryDescriptor recomputeDescriptor(String id) {
        removeDirectory(id);
        // compute effective descriptor
        List<BaseDirectoryDescriptor> list = allDescriptors.getOrDefault(id, Collections.emptyList());
        BaseDirectoryDescriptor contrib = null;
        for (BaseDirectoryDescriptor next : list) {
            // removal
            if (next.remove) {
                contrib = null;
                continue;
            }
            // extending templates
            String extendz = next.extendz;
            if (extendz != null) {
                // merge from base
                BaseDirectoryDescriptor base = descriptors.get(extendz);
                if (base != null && base.template) {
                    // merge generic base descriptor into specific one from the template
                    base = base.clone();
                    base.template = false;
                    base.name = next.name;
                    next = merge(base, next);
                }
            }
            // merge from previous
            if (contrib == null) {
                // first descriptor or first one after a remove
                contrib = next.clone();
            } else {
                contrib = merge(contrib, next);
            }
        }
        if (contrib == null) {
            descriptors.remove(id);
        } else {
            descriptors.put(id, contrib);
        }
        return contrib;
    }

    /**
     * Merge two descriptors. If one of them is a BaseDirectoryDescriptor then use the other's class for the merge.
     */
    protected BaseDirectoryDescriptor merge(BaseDirectoryDescriptor a, BaseDirectoryDescriptor b) {
        if (b.getClass().isAssignableFrom(a.getClass())) {
            // a is b or a subclass of b
            a.merge(b);
            return a;
        }
        if (a.getClass().isAssignableFrom(b.getClass())) {
            // b is a subclass of a
            BaseDirectoryDescriptor c;
            try {
                c = b.getClass().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
            c.merge(a);
            c.merge(b);
            return c;
        }
        // we'll lose information but do the merge anyway
        a.merge(b);
        return a;
    }

    /**
     * Gets the effective directory descriptor with the given id.
     * <p>
     * Templates are not returned.
     *
     * @param id the directory id
     * @return the effective directory descriptor, or {@code null} if not found
     */
    public synchronized BaseDirectoryDescriptor getDirectoryDescriptor(String id) {
        BaseDirectoryDescriptor descriptor = descriptors.get(id);
        return descriptor.template ? null : descriptor;
    }

    /**
     * Gets the directory with the given id.
     *
     * @param id the directory id
     * @return the directory, or {@code null} if not found
     */
    public synchronized Directory getDirectory(String id) {
        Directory dir = directories.get(id);
        if (dir == null) {
            BaseDirectoryDescriptor descriptor = descriptors.get(id);
            if (descriptor != null && !descriptor.template) {
                dir = descriptor.newDirectory();
                directories.put(id,  dir);
            }
        }
        return dir;
    }

    /**
     * Gets all the directory ids.
     *
     * @return the directory ids
     */
    public synchronized List<String> getDirectoryIds() {
        List<String> list = new ArrayList<>();
        for (BaseDirectoryDescriptor descriptor : descriptors.values()) {
            if (descriptor.template) {
                continue;
            }
            list.add(descriptor.name);
        }
        return list;
    }

    /**
     * Gets all the directories.
     *
     * @return the directories
     */
    public synchronized List<Directory> getDirectories() {
        List<Directory> list = new ArrayList<>();
        for (BaseDirectoryDescriptor descriptor : descriptors.values()) {
            if (descriptor.template) {
                continue;
            }
            list.add(getDirectory(descriptor.name));
        }
        return list;
    }

    /**
     * Shuts down all directories and clears the registry.
     */
    public synchronized void shutdown() {
        for (Directory dir : directories.values()) {
            shutdownDirectory(dir);
        }
        allDescriptors.clear();
        descriptors.clear();
        directories.clear();
    }

    /**
     * Shuts down the given directory and catches any {@link DirectoryException}.
     *
     * @param dir the directory
     */
    protected static void shutdownDirectory(Directory dir) {
        try {
            dir.shutdown();
        } catch (DirectoryException e) {
            log.error("Error while shutting down directory:" + dir.getName(), e);
        }
    }

}
