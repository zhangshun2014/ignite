/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.cache;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteLogger;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.DataPageEvictionMode;
import org.apache.ignite.events.CacheRebalancingEvent;
import org.apache.ignite.internal.IgniteClientDisconnectedCheckedException;
import org.apache.ignite.internal.IgniteInternalFuture;
import org.apache.ignite.internal.processors.affinity.AffinityAssignment;
import org.apache.ignite.internal.processors.affinity.AffinityTopologyVersion;
import org.apache.ignite.internal.processors.affinity.GridAffinityAssignmentCache;
import org.apache.ignite.internal.processors.cache.database.MemoryPolicy;
import org.apache.ignite.internal.processors.cache.database.freelist.FreeList;
import org.apache.ignite.internal.processors.cache.database.tree.reuse.ReuseList;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtAffinityAssignmentRequest;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtAffinityAssignmentResponse;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtCacheEntry;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtPartitionTopology;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtPartitionTopologyImpl;
import org.apache.ignite.internal.processors.cache.distributed.dht.preloader.GridDhtPreloader;
import org.apache.ignite.internal.processors.query.QueryUtils;
import org.apache.ignite.internal.util.typedef.CI1;
import org.apache.ignite.internal.util.typedef.internal.LT;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.lang.IgniteBiInClosure;
import org.apache.ignite.lang.IgniteFuture;
import org.apache.ignite.lang.IgniteUuid;
import org.jetbrains.annotations.Nullable;

import static org.apache.ignite.cache.CacheMode.LOCAL;
import static org.apache.ignite.cache.CacheRebalanceMode.NONE;
import static org.apache.ignite.events.EventType.EVT_CACHE_REBALANCE_PART_UNLOADED;
import static org.apache.ignite.internal.managers.communication.GridIoPolicy.AFFINITY_POOL;

/**
 *
 */
public class CacheGroupInfrastructure {
    /** */
    private final IgniteLogger log;

    /** */
    private GridAffinityAssignmentCache aff;

    /** */
    private int grpId;

    /** */
    private UUID rcvdFrom;

    /** */
    private final AffinityTopologyVersion locStartVer;

    /** */
    private final CacheConfiguration ccfg;

    /** */
    private final GridCacheSharedContext ctx;

    /** */
    private GridDhtPartitionTopologyImpl top;

    /** */
    private IgniteCacheOffheapManager offheapMgr;

    /** Preloader. */
    private GridCachePreloader preldr;

    /** */
    private final boolean affNode;

    /** Memory policy. */
    private final MemoryPolicy memPlc;

    /** */
    private final CacheObjectContext cacheObjCtx;

    /** FreeList instance this group is associated with. */
    private final FreeList freeList;

    /** ReuseList instance this group is associated with */
    private final ReuseList reuseList;

    /** IO policy. */
    private final byte ioPlc;

    /** */
    private boolean depEnabled;

    /** */
    private boolean storeCacheId;

    /** Flag indicating that this cache is in a recovery mode. */
    // TODO IGNITE-5075 see GridCacheContext#needsRecovery
    private boolean needsRecovery;

    /** */
    private final List<GridCacheContext> caches;

    /**
     * @param grpId Group ID.
     * @param ctx Context.
     * @param cacheType Cache type.
     * @param ccfg Cache configuration.
     * @param affNode Affinity node flag.
     * @param cacheObjCtx Cache object context.
     */
    CacheGroupInfrastructure(GridCacheSharedContext ctx,
        int grpId,
        UUID rcvdFrom,
        CacheType cacheType,
        CacheConfiguration ccfg,
        boolean affNode,
        MemoryPolicy memPlc,
        CacheObjectContext cacheObjCtx,
        FreeList freeList,
        ReuseList reuseList,
        AffinityTopologyVersion locStartVer) {
        assert grpId != 0 : "Invalid group ID [cache=" + ccfg.getName() + ", grpName=" + ccfg.getGroupName() + ']';
        assert ccfg != null;
        assert memPlc != null || !affNode;

        this.grpId = grpId;
        this.rcvdFrom = rcvdFrom;
        this.ctx = ctx;
        this.ccfg = ccfg;
        this.affNode = affNode;
        this.memPlc = memPlc;
        this.cacheObjCtx = cacheObjCtx;
        this.freeList = freeList;
        this.reuseList = reuseList;
        this.locStartVer = locStartVer;

        ioPlc = cacheType.ioPolicy();

        depEnabled = ctx.kernalContext().deploy().enabled() && !ctx.kernalContext().cacheObjects().isBinaryEnabled(ccfg);

        storeCacheId = affNode && memPlc.config().getPageEvictionMode() != DataPageEvictionMode.DISABLED;

        log = ctx.kernalContext().log(getClass());

        caches = new ArrayList<>();
    }

    /**
     * @return Node ID initiated cache group start.
     */
    public UUID receivedFrom() {
        return rcvdFrom;
    }

    /**
     * @return {@code True} if cacheId should be stored in data pages.
     */
    public boolean storeCacheIdInDataPage() {
        return storeCacheId;
    }

    /**
     * @return {@code True} if deployment is enabled.
     */
    public boolean deploymentEnabled() {
        return depEnabled;
    }

    /**
     * @return Preloader.
     */
    public GridCachePreloader preloader() {
        return preldr;
    }

    /**
     * @return IO policy for the given cache group.
     */
    public byte ioPolicy() {
        return ioPlc;
    }

    /**
     * @param cctx Cache context.
     * @throws IgniteCheckedException If failed.
     */
    public void onCacheStarted(GridCacheContext cctx) throws IgniteCheckedException {
        addCacheContext(cctx);

        offheapMgr.onCacheStarted(cctx);
    }

    /**
     * @param cctx Cache context.
     */
    private void addCacheContext(GridCacheContext cctx) {
        synchronized (caches) {
            assert sharedGroup() || caches.isEmpty();

            boolean add = caches.add(cctx);

            assert add : cctx.name();
        }
    }

    /**
     * @param cctx Cache context.
     */
    private void removeCacheContext(GridCacheContext cctx) {
        synchronized (caches) {
            assert sharedGroup() || caches.size() == 1 : caches.size();

            boolean rmv = caches.remove(cctx);

            assert rmv : cctx.name();
        }
    }

    /**
     * @return Cache context if group contains single cache.
     */
    public GridCacheContext singleCacheContext() {
        synchronized (caches) {
            assert !sharedGroup() && caches.size() == 1;

            return caches.get(0);
        }
    }

    /**
     *
     */
    public void unwindUndeploys() {
        synchronized (caches) {
            for (int i = 0; i < caches.size(); i++) {
                GridCacheContext cctx = caches.get(i);

                cctx.deploy().unwind(cctx);
            }
        }
    }

    /**
     * @param type Event type to check.
     * @return {@code True} if given event type should be recorded.
     */
    public boolean eventRecordable(int type) {
        return ctx.gridEvents().isRecordable(type);
    }

    /**
     * Adds preloading event.
     *
     * @param part Partition.
     * @param type Event type.
     * @param discoNode Discovery node.
     * @param discoType Discovery event type.
     * @param discoTs Discovery event timestamp.
     */
    public void addRebalanceEvent(int part, int type, ClusterNode discoNode, int discoType, long discoTs) {
        assert discoNode != null;
        assert type > 0;
        assert discoType > 0;
        assert discoTs > 0;

        if (!eventRecordable(type))
            LT.warn(log, "Added event without checking if event is recordable: " + U.gridEventName(type));

        synchronized (caches) {
            for (int i = 0; i < caches.size(); i++) {
                GridCacheContext cctx = caches.get(i);

                if (cctx.recordEvent(type)) {
                    cctx.gridEvents().record(new CacheRebalancingEvent(cctx.name(),
                        cctx.localNode(),
                        "Cache rebalancing event.",
                        type,
                        part,
                        discoNode,
                        discoType,
                        discoTs));
                }
            }
        }
    }
    /**
     * Adds partition unload event.
     *
     * @param part Partition.
     */
    public void addUnloadEvent(int part) {
        if (!eventRecordable(EVT_CACHE_REBALANCE_PART_UNLOADED))
            LT.warn(log, "Added event without checking if event is recordable: " +
                U.gridEventName(EVT_CACHE_REBALANCE_PART_UNLOADED));

        synchronized (caches) {
            for (int i = 0; i < caches.size(); i++) {
                GridCacheContext cctx = caches.get(i);

                cctx.gridEvents().record(new CacheRebalancingEvent(cctx.name(),
                    cctx.localNode(),
                    "Cache unloading event.",
                    EVT_CACHE_REBALANCE_PART_UNLOADED,
                    part,
                    null,
                    0,
                    0));
            }
        }
    }

    public void addCacheEvent(
        int part,
        KeyCacheObject key,
        UUID evtNodeId,
        @Nullable IgniteUuid xid,
        @Nullable Object lockId,
        int type,
        @Nullable CacheObject newVal,
        boolean hasNewVal,
        @Nullable CacheObject oldVal,
        boolean hasOldVal,
        UUID subjId,
        @Nullable String cloClsName,
        @Nullable String taskName,
        boolean keepBinary
    ) {
        synchronized (caches) {
            for (int i = 0; i < caches.size(); i++) {
                GridCacheContext cctx = caches.get(i);

                cctx.events().addEvent(part,
                    key,
                    evtNodeId,
                    xid,
                    lockId,
                    type,
                    newVal,
                    hasNewVal,
                    oldVal,
                    hasOldVal,
                    subjId,
                    cloClsName,
                    taskName,
                    keepBinary);
            }
        }
    }

    // TODO IGNITE-5075: need separate caches with/without queries?
    public boolean queriesEnabled() {
        return QueryUtils.isEnabled(ccfg);
    }

    /**
     * @return Free List.
     */
    public FreeList freeList() {
        return freeList;
    }

    /**
     * @return Reuse List.
     */
    public ReuseList reuseList() {
        return reuseList;
    }

    /**
     * @return Cache object context.
     */
    public CacheObjectContext cacheObjectContext() {
        return cacheObjCtx;
    }

    /**
     * @return Cache shared context.
     */
    public GridCacheSharedContext shared() {
        return ctx;
    }

    /**
     * @return Memory policy.
     */
    public MemoryPolicy memoryPolicy() {
        return memPlc;
    }

    /**
     * @return {@code True} if local node is affinity node.
     */
    public boolean affinityNode() {
        return affNode;
    }

    /**
     * @return Topology.
     */
    public GridDhtPartitionTopology topology() {
        if (top == null)
            throw new IllegalStateException("Topology is not initialized: " + name());

        return top;
    }

    /**
     * @return Offheap manager.
     */
    public IgniteCacheOffheapManager offheap() {
        return offheapMgr;
    }

    /**
     * @return Current cache state. Must only be modified during exchange.
     */
    public boolean needsRecovery() {
        return needsRecovery;
    }

    /**
     * @param needsRecovery Needs recovery flag.
     */
    public void needsRecovery(boolean needsRecovery) {
        this.needsRecovery = needsRecovery;
    }

    public boolean allowFastEviction() {
        // TODO IGNITE-5075 see GridCacheContext#allowFastEviction
        return false;
    }

    /**
     * @return Topology version when group was started on local node.
     */
    public AffinityTopologyVersion localStartVersion() {
        return locStartVer;
    }

    /**
     * @return {@code True} if cache is local.
     */
    public boolean isLocal() {
        return ccfg.getCacheMode() == LOCAL;
    }

    /**
     * @return Cache configuration.
     */
    public CacheConfiguration config() {
        return ccfg;
    }

    /**
     * @return Affinity.
     */
    public GridAffinityAssignmentCache affinity() {
        return aff;
    }

    /**
     * @return Group name.
     */
    @Nullable public String name() {
        return ccfg.getGroupName();
    }

    /**
     * @return Group name.
     */
    @Nullable public String nameForLog() {
        if (ccfg.getGroupName() == null)
            return "[cache, name=" + ccfg.getName() + ']';

        return "[cacheGroup, name=" + ccfg.getGroupName() + ']';
    }

    /**
     * @return Group ID.
     */
    public int groupId() {
        return grpId;
    }

    /**
     * @return {@code True} if group can contain multiple caches.
     */
    public boolean sharedGroup() {
        return ccfg.getGroupName() != null;
    }

    // TODO IGNITE-5075.
    public boolean isDrEnabled() {
        return false;
    }

    /**
     *
     */
    public void onKernalStop() {
        preldr.onKernalStop();

        offheapMgr.onKernalStop();
    }

    /**
     * @param cctx Cache context.
     * @param destroy Destroy flag.
     */
    void stopCache(GridCacheContext cctx, boolean destroy) {
        if (top != null)
            top.onCacheStopped(cctx.cacheId());

        offheapMgr.stopCache(cctx.cacheId(), destroy);

        removeCacheContext(cctx);
    }

    /**
     *
     */
    void stopGroup() {
        IgniteCheckedException err =
            new IgniteCheckedException("Failed to wait for topology update, cache (or node) is stopping.");

        aff.cancelFutures(err);

        offheapMgr.stop();

        ctx.io().removeCacheGroupHandlers(grpId);
    }

    /**
     * @return {@code True} if group contains caches.
     */
    boolean hasCaches() {
        synchronized (caches) {
            return !caches.isEmpty();
        }
    }

    /**
     * @param part Partition ID.
     */
    public void onPartitionEvicted(int part) {
        synchronized (caches) {
            for (int i = 0; i < caches.size(); i++) {
                GridCacheContext cctx = caches.get(i);

                if (cctx.isDrEnabled())
                    cctx.dr().partitionEvicted(part);

                cctx.continuousQueries().onPartitionEvicted(part);

                cctx.dataStructures().onPartitionEvicted(part);
            }
        }
    }

    /**
     * @throws IgniteCheckedException If failed.
     */
    public void start() throws IgniteCheckedException {
        aff = new GridAffinityAssignmentCache(ctx.kernalContext(),
            name(),
            grpId,
            ccfg.getAffinity(),
            ccfg.getNodeFilter(),
            ccfg.getBackups(),
            ccfg.getCacheMode() == LOCAL);

        if (ccfg.getCacheMode() != LOCAL) {
            GridCacheMapEntryFactory entryFactory = new GridCacheMapEntryFactory() {
                @Override public GridCacheMapEntry create(
                    GridCacheContext ctx,
                    AffinityTopologyVersion topVer,
                    KeyCacheObject key
                ) {
                    return new GridDhtCacheEntry(ctx, topVer, key);
                }
            };

            top = new GridDhtPartitionTopologyImpl(ctx, this, entryFactory);

            if (!ctx.kernalContext().clientNode()) {
                ctx.io().addHandler(true, groupId(), GridDhtAffinityAssignmentRequest.class,
                    new IgniteBiInClosure<UUID, GridDhtAffinityAssignmentRequest>() {
                        @Override public void apply(UUID nodeId, GridDhtAffinityAssignmentRequest msg) {
                            processAffinityAssignmentRequest(nodeId, msg);
                        }
                    });
            }

            preldr = new GridDhtPreloader(this);

            preldr.start();
        }
        else
            preldr = new GridCachePreloaderAdapter(this);

        // TODO IGNITE-5075 get from plugin.
        offheapMgr = new IgniteCacheOffheapManagerImpl();

        offheapMgr.start(ctx, this);

        ctx.affinity().onCacheGroupCreated(this);
    }

    /**
     * @param nodeId Node ID.
     * @param req Request.
     */
    private void processAffinityAssignmentRequest(final UUID nodeId,
        final GridDhtAffinityAssignmentRequest req) {
        if (log.isDebugEnabled())
            log.debug("Processing affinity assignment request [node=" + nodeId + ", req=" + req + ']');

        IgniteInternalFuture<AffinityTopologyVersion> fut = aff.readyFuture(req.topologyVersion());

        if (fut != null) {
            fut.listen(new CI1<IgniteInternalFuture<AffinityTopologyVersion>>() {
                @Override public void apply(IgniteInternalFuture<AffinityTopologyVersion> fut) {
                    processAffinityAssignmentRequest0(nodeId, req);
                }
            });
        }
        else
            processAffinityAssignmentRequest0(nodeId, req);
    }

    /**
     * @param nodeId Node ID.
     * @param req Request.
     */
    private void processAffinityAssignmentRequest0(UUID nodeId, final GridDhtAffinityAssignmentRequest req) {
        AffinityTopologyVersion topVer = req.topologyVersion();

        if (log.isDebugEnabled())
            log.debug("Affinity is ready for topology version, will send response [topVer=" + topVer +
                ", node=" + nodeId + ']');

        AffinityAssignment assignment = aff.cachedAffinity(topVer);

        GridDhtAffinityAssignmentResponse res = new GridDhtAffinityAssignmentResponse(
            req.futureId(),
            grpId,
            topVer,
            assignment.assignment());

        if (aff.centralizedAffinityFunction()) {
            assert assignment.idealAssignment() != null;

            res.idealAffinityAssignment(assignment.idealAssignment());
        }

        try {
            ctx.io().send(nodeId, res, AFFINITY_POOL);
        }
        catch (IgniteCheckedException e) {
            U.error(log, "Failed to send affinity assignment response to remote node [node=" + nodeId + ']', e);
        }
    }

    /**
     * @param reconnectFut Reconnect future.
     */
    public void onDisconnected(IgniteFuture reconnectFut) {
        IgniteCheckedException err = new IgniteClientDisconnectedCheckedException(reconnectFut,
            "Failed to wait for topology update, client disconnected.");

        if (aff != null)
            aff.cancelFutures(err);
    }

    /**
     * @return {@code True} if rebalance is enabled.
     */
    public boolean rebalanceEnabled() {
        return ccfg.getRebalanceMode() != NONE;
    }

    /**
     * @param grpId New group ID.
     */
    public void onReconnected(int grpId) {
        assert grpId > 0 : grpId;

        this.grpId = grpId;

        aff.onReconnected();

        if (top != null)
            top.onReconnected();

        preldr.onReconnected();
    }
}