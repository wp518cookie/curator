/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.curator.framework.recipes.nodes;

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.CuratorCache;
import org.apache.curator.framework.recipes.cache.CuratorCacheBridge;
import org.apache.curator.framework.recipes.cache.CuratorCacheListener;
import org.apache.curator.framework.recipes.cache.CuratorCacheStorage;
import org.apache.curator.utils.CloseableUtils;
import org.apache.curator.utils.ThreadUtils;
import org.apache.curator.utils.ZKPaths;
import org.apache.zookeeper.CreateMode;
import java.io.Closeable;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Group membership management. Adds this instance into a group and
 * keeps a cache of members in the group
 */
public class GroupMember implements Closeable
{
    private final PersistentNode pen;
    private final CuratorCacheBridge cache;
    private final String thisId;
    private final CuratorCacheStorage storage = CuratorCacheStorage.standard();

    /**
     * @param client client
     * @param membershipPath the path to use for membership
     * @param thisId ID of this group member. MUST be unique for the group
     */
    public GroupMember(CuratorFramework client, String membershipPath, String thisId)
    {
        this(client, membershipPath, thisId, CuratorFrameworkFactory.getLocalAddress());
    }

    /**
     * @param client client
     * @param membershipPath the path to use for membership
     * @param thisId ID of this group member. MUST be unique for the group
     * @param payload the payload to write in our member node
     */
    public GroupMember(CuratorFramework client, String membershipPath, String thisId, byte[] payload)
    {
        this.thisId = Preconditions.checkNotNull(thisId, "thisId cannot be null");

        cache = newCache(client, membershipPath);
        pen = newPersistentEphemeralNode(client, membershipPath, thisId, payload);
    }

    /**
     * Start the group membership. Register thisId as a member and begin
     * caching all members
     */
    public void start()
    {
        pen.start();
        try
        {
            cache.start();
        }
        catch ( Exception e )
        {
            ThreadUtils.checkInterrupted(e);
            Throwables.propagate(e);
        }
    }

    /**
     * Change the data stored in this instance's node
     *
     * @param data new data (cannot be null)
     */
    public void setThisData(byte[] data)
    {
        try
        {
            pen.setData(data);
        }
        catch ( Exception e )
        {
            ThreadUtils.checkInterrupted(e);
            Throwables.propagate(e);
        }
    }

    /**
     * Have thisId leave the group and stop caching membership
     */
    @Override
    public void close()
    {
        CloseableUtils.closeQuietly(cache);
        CloseableUtils.closeQuietly(pen);
    }

    /**
     * Return the current view of membership. The keys are the IDs
     * of the members. The values are each member's payload
     *
     * @return membership
     */
    public Map<String, byte[]> getCurrentMembers()
    {
        Map<String, byte[]> map = new HashMap<>();
        map.put(thisId, pen.getData());
        return storage.stream()
            .map(data -> new AbstractMap.SimpleEntry<>(idFromPath(data.getPath()), data.getData()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (k, v) -> v, () -> map));
    }

    /**
     * Given a full ZNode path, return the member ID
     *
     * @param path full ZNode path
     * @return id
     */
    public String idFromPath(String path)
    {
        return ZKPaths.getNodeFromPath(path);
    }

    protected PersistentNode newPersistentEphemeralNode(CuratorFramework client, String membershipPath, String thisId, byte[] payload)
    {
        return new PersistentNode(client, CreateMode.EPHEMERAL, false, ZKPaths.makePath(membershipPath, thisId), payload);
    }

    protected CuratorCacheBridge newCache(CuratorFramework client, String membershipPath)
    {
        CuratorCacheBridge cache = CuratorCache.builder(client, membershipPath).buildBridge();
        CuratorCacheListener listener = CuratorCacheListener.builder()
            .forCreatesAndChanges((__, newNode) -> storage.put(newNode))
            .forDeletes(data -> storage.remove(data.getPath()))
            .withPathFilter(path -> !path.equals(membershipPath))
            .build();
        cache.listenable().addListener(listener);
        return cache;
    }
}
