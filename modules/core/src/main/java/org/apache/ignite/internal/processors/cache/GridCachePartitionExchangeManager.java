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
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Queue;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteSystemProperties;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.events.DiscoveryEvent;
import org.apache.ignite.events.Event;
import org.apache.ignite.internal.IgniteClientDisconnectedCheckedException;
import org.apache.ignite.internal.IgniteFutureTimeoutCheckedException;
import org.apache.ignite.internal.IgniteInternalFuture;
import org.apache.ignite.internal.IgniteInterruptedCheckedException;
import org.apache.ignite.internal.cluster.ClusterTopologyCheckedException;
import org.apache.ignite.internal.events.DiscoveryCustomEvent;
import org.apache.ignite.internal.managers.eventstorage.GridLocalEventListener;
import org.apache.ignite.internal.processors.affinity.AffinityTopologyVersion;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridClientPartitionTopology;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtPartitionTopology;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridDhtTopologyFuture;
import org.apache.ignite.internal.processors.cache.distributed.dht.preloader.GridDhtPartitionDemandMessage;
import org.apache.ignite.internal.processors.cache.distributed.dht.preloader.GridDhtPartitionExchangeId;
import org.apache.ignite.internal.processors.cache.distributed.dht.preloader.GridDhtPartitionFullMap;
import org.apache.ignite.internal.processors.cache.distributed.dht.preloader.GridDhtPartitionMap;
import org.apache.ignite.internal.processors.cache.distributed.dht.preloader.GridDhtPartitionMap2;
import org.apache.ignite.internal.processors.cache.distributed.dht.preloader.GridDhtPartitionSupplyMessageV2;
import org.apache.ignite.internal.processors.cache.distributed.dht.preloader.GridDhtPartitionsExchangeFuture;
import org.apache.ignite.internal.processors.cache.distributed.dht.preloader.GridDhtPartitionsFullMessage;
import org.apache.ignite.internal.processors.cache.distributed.dht.preloader.GridDhtPartitionsSingleMessage;
import org.apache.ignite.internal.processors.cache.distributed.dht.preloader.GridDhtPartitionsSingleRequest;
import org.apache.ignite.internal.processors.cache.distributed.dht.preloader.GridDhtPreloaderAssignments;
import org.apache.ignite.internal.processors.cache.transactions.IgniteInternalTx;
import org.apache.ignite.internal.processors.cache.transactions.IgniteTxManager;
import org.apache.ignite.internal.processors.timeout.GridTimeoutObject;
import org.apache.ignite.internal.util.GridListSet;
import org.apache.ignite.internal.util.future.GridFutureAdapter;
import org.apache.ignite.internal.util.lang.IgnitePair;
import org.apache.ignite.internal.util.tostring.GridToStringExclude;
import org.apache.ignite.internal.util.tostring.GridToStringInclude;
import org.apache.ignite.internal.util.typedef.CI1;
import org.apache.ignite.internal.util.typedef.CI2;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.internal.CU;
import org.apache.ignite.internal.util.typedef.internal.GPC;
import org.apache.ignite.internal.util.typedef.internal.S;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.internal.util.worker.GridWorker;
import org.apache.ignite.lang.IgniteBiInClosure;
import org.apache.ignite.lang.IgniteProductVersion;
import org.apache.ignite.lang.IgniteUuid;
import org.apache.ignite.thread.IgniteThread;
import org.jetbrains.annotations.Nullable;
import org.jsr166.ConcurrentHashMap8;
import org.jsr166.ConcurrentLinkedDeque8;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.apache.ignite.IgniteSystemProperties.IGNITE_PRELOAD_RESEND_TIMEOUT;
import static org.apache.ignite.IgniteSystemProperties.IGNITE_THREAD_DUMP_ON_EXCHANGE_TIMEOUT;
import static org.apache.ignite.IgniteSystemProperties.getLong;
import static org.apache.ignite.events.EventType.EVT_CACHE_REBALANCE_STARTED;
import static org.apache.ignite.events.EventType.EVT_NODE_FAILED;
import static org.apache.ignite.events.EventType.EVT_NODE_JOINED;
import static org.apache.ignite.events.EventType.EVT_NODE_LEFT;
import static org.apache.ignite.internal.GridTopic.TOPIC_CACHE;
import static org.apache.ignite.internal.events.DiscoveryCustomEvent.EVT_DISCOVERY_CUSTOM_EVT;
import static org.apache.ignite.internal.managers.communication.GridIoPolicy.SYSTEM_POOL;
import static org.apache.ignite.internal.processors.cache.distributed.dht.preloader.GridDhtPreloader.DFLT_PRELOAD_RESEND_TIMEOUT;

/**
 * Partition exchange manager.
 */
public class GridCachePartitionExchangeManager<K, V> extends GridCacheSharedManagerAdapter<K, V> {
    /** Exchange history size. */
    private static final int EXCHANGE_HISTORY_SIZE = 1000;

    /** Atomic reference for pending timeout object. */
    private AtomicReference<ResendTimeoutObject> pendingResend = new AtomicReference<>();

    /** Partition resend timeout after eviction. */
    private final long partResendTimeout = getLong(IGNITE_PRELOAD_RESEND_TIMEOUT, DFLT_PRELOAD_RESEND_TIMEOUT);

    /** */
    private final ReadWriteLock busyLock = new ReentrantReadWriteLock();

    /** Last partition refresh. */
    private final AtomicLong lastRefresh = new AtomicLong(-1);

    /** */
    @GridToStringInclude
    private ExchangeWorker exchWorker;

    /** */
    @GridToStringExclude
    private final ConcurrentMap<Integer, GridClientPartitionTopology> clientTops = new ConcurrentHashMap8<>();

    /** */
    private volatile GridDhtPartitionsExchangeFuture lastInitializedFut;

    /** */
    private final ConcurrentMap<AffinityTopologyVersion, AffinityReadyFuture> readyFuts = new ConcurrentHashMap8<>();

    /** */
    private final ConcurrentSkipListMap<AffinityTopologyVersion, IgnitePair<IgniteProductVersion>> nodeVers =
        new ConcurrentSkipListMap<>();

    /** */
    private final AtomicReference<AffinityTopologyVersion> readyTopVer =
        new AtomicReference<>(AffinityTopologyVersion.NONE);

    /** */
    private GridFutureAdapter<?> reconnectExchangeFut;

    /** */
    private final Queue<Callable<Boolean>> rebalanceQ = new ConcurrentLinkedDeque8<>();

    /**
     * Partition map futures.
     * This set also contains already completed exchange futures to address race conditions when coordinator
     * leaves grid and new coordinator sends full partition message to a node which has not yet received
     * discovery event. In case if remote node will retry partition exchange, completed future will indicate
     * that full partition map should be sent to requesting node right away.
     */
    private ExchangeFutureSet exchFuts = new ExchangeFutureSet();

    /** */
    private volatile IgniteCheckedException stopErr;

    /** Discovery listener. */
    private final GridLocalEventListener discoLsnr = new GridLocalEventListener() {
        @Override public void onEvent(Event evt) {
            if (!enterBusy())
                return;

            try {
                DiscoveryEvent e = (DiscoveryEvent)evt;

                ClusterNode loc = cctx.localNode();

                assert e.type() == EVT_NODE_JOINED || e.type() == EVT_NODE_LEFT || e.type() == EVT_NODE_FAILED ||
                    e.type() == EVT_DISCOVERY_CUSTOM_EVT;

                final ClusterNode n = e.eventNode();

                GridDhtPartitionExchangeId exchId = null;
                GridDhtPartitionsExchangeFuture exchFut = null;

                if (e.type() != EVT_DISCOVERY_CUSTOM_EVT) {
                    assert !loc.id().equals(n.id());

                    if (e.type() == EVT_NODE_LEFT || e.type() == EVT_NODE_FAILED) {
                        assert cctx.discovery().node(n.id()) == null;

                        // Avoid race b/w initial future add and discovery event.
                        GridDhtPartitionsExchangeFuture initFut = null;

                        if (readyTopVer.get().equals(AffinityTopologyVersion.NONE)) {
                            initFut = exchangeFuture(initialExchangeId(), null, null, null);

                            initFut.onNodeLeft(n);
                        }

                        for (GridDhtPartitionsExchangeFuture f : exchFuts.values()) {
                            if (f != initFut)
                                f.onNodeLeft(n);
                        }
                    }

                    assert
                        e.type() != EVT_NODE_JOINED || n.order() > loc.order() :
                        "Node joined with smaller-than-local " +
                            "order [newOrder=" + n.order() + ", locOrder=" + loc.order() + ']';

                    exchId = exchangeId(n.id(),
                        affinityTopologyVersion(e),
                        e.type());

                    exchFut = exchangeFuture(exchId, e, null, null);
                }
                else {
                    DiscoveryCustomEvent customEvt = (DiscoveryCustomEvent)e;

                    if (customEvt.customMessage() instanceof DynamicCacheChangeBatch) {
                        DynamicCacheChangeBatch batch = (DynamicCacheChangeBatch)customEvt.customMessage();

                        Collection<DynamicCacheChangeRequest> valid = new ArrayList<>(batch.requests().size());

                        // Validate requests to check if event should trigger partition exchange.
                        for (final DynamicCacheChangeRequest req : batch.requests()) {
                            if (req.exchangeNeeded())
                                valid.add(req);
                            else {
                                IgniteInternalFuture<?> fut = null;

                                if (req.cacheFutureTopologyVersion() != null)
                                    fut = affinityReadyFuture(req.cacheFutureTopologyVersion());

                                if (fut == null || fut.isDone())
                                    cctx.cache().completeStartFuture(req);
                                else {
                                    fut.listen(new CI1<IgniteInternalFuture<?>>() {
                                        @Override public void apply(IgniteInternalFuture<?> fut) {
                                            cctx.cache().completeStartFuture(req);
                                        }
                                    });
                                }
                            }
                        }

                        if (!F.isEmpty(valid)) {
                            exchId = exchangeId(n.id(), affinityTopologyVersion(e), e.type());

                            exchFut = exchangeFuture(exchId, e, valid, null);
                        }
                    }
                    else if (customEvt.customMessage() instanceof CacheAffinityChangeMessage) {
                        CacheAffinityChangeMessage msg = (CacheAffinityChangeMessage)customEvt.customMessage();

                        if (msg.exchangeId() == null) {
                            if (msg.exchangeNeeded()) {
                                exchId = exchangeId(n.id(), affinityTopologyVersion(e), e.type());

                                exchFut = exchangeFuture(exchId, e, null, msg);
                            }
                        }
                        else
                            exchangeFuture(msg.exchangeId(), null, null, null).onAffinityChangeMessage(customEvt.eventNode(), msg);
                    }
                }

                if (exchId != null) {
                    if (log.isDebugEnabled())
                        log.debug("Discovery event (will start exchange): " + exchId);

                    // Event callback - without this callback future will never complete.
                    exchFut.onEvent(exchId, e);

                    // Start exchange process.
                    addFuture(exchFut);
                }
                else {
                    if (log.isDebugEnabled())
                        log.debug("Do not start exchange for discovery event: " + evt);
                }
            }
            finally {
                leaveBusy();
            }
        }
    };

    /** {@inheritDoc} */
    @Override protected void start0() throws IgniteCheckedException {
        super.start0();

        exchWorker = new ExchangeWorker();

        cctx.gridEvents().addLocalEventListener(discoLsnr, EVT_NODE_JOINED, EVT_NODE_LEFT, EVT_NODE_FAILED,
            EVT_DISCOVERY_CUSTOM_EVT);

        cctx.io().addHandler(0, GridDhtPartitionsSingleMessage.class,
            new MessageHandler<GridDhtPartitionsSingleMessage>() {
                @Override public void onMessage(ClusterNode node, GridDhtPartitionsSingleMessage msg) {
                    processSinglePartitionUpdate(node, msg);
                }
            });

        cctx.io().addHandler(0, GridDhtPartitionsFullMessage.class,
            new MessageHandler<GridDhtPartitionsFullMessage>() {
                @Override public void onMessage(ClusterNode node, GridDhtPartitionsFullMessage msg) {
                    processFullPartitionUpdate(node, msg);
                }
            });

        cctx.io().addHandler(0, GridDhtPartitionsSingleRequest.class,
            new MessageHandler<GridDhtPartitionsSingleRequest>() {
                @Override public void onMessage(ClusterNode node, GridDhtPartitionsSingleRequest msg) {
                    processSinglePartitionRequest(node, msg);
                }
            });
    }

    /**
     * @return Reconnect partition exchange future.
     */
    public IgniteInternalFuture<?> reconnectExchangeFuture() {
        return reconnectExchangeFut;
    }

    /**
     * @return Initial exchange ID.
     */
    private GridDhtPartitionExchangeId initialExchangeId() {
        DiscoveryEvent discoEvt = cctx.discovery().localJoinEvent();

        assert discoEvt != null;

        final AffinityTopologyVersion startTopVer = affinityTopologyVersion(discoEvt);

        assert discoEvt.topologyVersion() == startTopVer.topologyVersion();

        return exchangeId(cctx.localNode().id(), startTopVer, EVT_NODE_JOINED);
    }

    /** {@inheritDoc} */
    @Override protected void onKernalStart0(boolean reconnect) throws IgniteCheckedException {
        super.onKernalStart0(reconnect);

        ClusterNode loc = cctx.localNode();

        long startTime = loc.metrics().getStartTime();

        assert startTime > 0;

        // Generate dummy discovery event for local node joining.
        DiscoveryEvent discoEvt = cctx.discovery().localJoinEvent();

        GridDhtPartitionExchangeId exchId = initialExchangeId();

        GridDhtPartitionsExchangeFuture fut = exchangeFuture(exchId, discoEvt, null, null);

        if (reconnect)
            reconnectExchangeFut = new GridFutureAdapter<>();

        exchWorker.futQ.addFirst(fut);

        if (!cctx.kernalContext().clientNode()) {
            for (int cnt = 0; cnt < cctx.gridConfig().getRebalanceThreadPoolSize(); cnt++) {
                final int idx = cnt;

                cctx.io().addOrderedHandler(rebalanceTopic(cnt), new CI2<UUID, GridCacheMessage>() {
                    @Override public void apply(final UUID id, final GridCacheMessage m) {
                        if (!enterBusy())
                            return;

                        try {
                            GridCacheContext cacheCtx = cctx.cacheContext(m.cacheId);

                            if (cacheCtx != null) {
                                if (m instanceof GridDhtPartitionSupplyMessageV2)
                                    cacheCtx.preloader().handleSupplyMessage(
                                        idx, id, (GridDhtPartitionSupplyMessageV2)m);
                                else if (m instanceof GridDhtPartitionDemandMessage)
                                    cacheCtx.preloader().handleDemandMessage(
                                        idx, id, (GridDhtPartitionDemandMessage)m);
                                else
                                    U.error(log, "Unsupported message type: " + m.getClass().getName());
                            }
                        }
                        finally {
                            leaveBusy();
                        }
                    }
                });
            }
        }

        new IgniteThread(cctx.gridName(), "exchange-worker", exchWorker).start();

        if (reconnect) {
            fut.listen(new CI1<IgniteInternalFuture<AffinityTopologyVersion>>() {
                @Override public void apply(IgniteInternalFuture<AffinityTopologyVersion> fut) {
                    try {
                        fut.get();

                        for (GridCacheContext cacheCtx : cctx.cacheContexts())
                            cacheCtx.preloader().onInitialExchangeComplete(null);

                        reconnectExchangeFut.onDone();
                    }
                    catch (IgniteCheckedException e) {
                        for (GridCacheContext cacheCtx : cctx.cacheContexts())
                            cacheCtx.preloader().onInitialExchangeComplete(e);

                        reconnectExchangeFut.onDone(e);
                    }
                }
            });
        }
        else {
            if (log.isDebugEnabled())
                log.debug("Beginning to wait on local exchange future: " + fut);

            boolean first = true;

            while (true) {
                try {
                    fut.get(cctx.preloadExchangeTimeout());

                    break;
                }
                catch (IgniteFutureTimeoutCheckedException ignored) {
                    if (first) {
                        U.warn(log, "Failed to wait for initial partition map exchange. " +
                            "Possible reasons are: " + U.nl() +
                            "  ^-- Transactions in deadlock." + U.nl() +
                            "  ^-- Long running transactions (ignore if this is the case)." + U.nl() +
                            "  ^-- Unreleased explicit locks.");

                        first = false;
                    }
                    else
                        U.warn(log, "Still waiting for initial partition map exchange [fut=" + fut + ']');
                }
            }

            for (GridCacheContext cacheCtx : cctx.cacheContexts()) {
                if (cacheCtx.startTopologyVersion() == null)
                    cacheCtx.preloader().onInitialExchangeComplete(null);
            }

            if (log.isDebugEnabled())
                log.debug("Finished waiting for initial exchange: " + fut.exchangeId());
        }
    }

    /**
     * @param idx Index.
     * @return Topic for index.
     */
    public static Object rebalanceTopic(int idx) {
        return TOPIC_CACHE.topic("Rebalance", idx);
    }

    /** {@inheritDoc} */
    @Override protected void onKernalStop0(boolean cancel) {
        cctx.gridEvents().removeLocalEventListener(discoLsnr);

        cctx.io().removeHandler(0, GridDhtPartitionsSingleMessage.class);
        cctx.io().removeHandler(0, GridDhtPartitionsFullMessage.class);
        cctx.io().removeHandler(0, GridDhtPartitionsSingleRequest.class);

        stopErr = cctx.kernalContext().clientDisconnected() ?
            new IgniteClientDisconnectedCheckedException(cctx.kernalContext().cluster().clientReconnectFuture(),
                "Client node disconnected: " + cctx.gridName()) :
            new IgniteInterruptedCheckedException("Node is stopping: " + cctx.gridName());

        // Finish all exchange futures.
        ExchangeFutureSet exchFuts0 = exchFuts;

        if (exchFuts0 != null) {
            for (GridDhtPartitionsExchangeFuture f : exchFuts.values())
                f.onDone(stopErr);
        }

        for (AffinityReadyFuture f : readyFuts.values())
            f.onDone(stopErr);

        if (!cctx.kernalContext().clientNode()) {
            for (int cnt = 0; cnt < cctx.gridConfig().getRebalanceThreadPoolSize(); cnt++)
                cctx.io().removeOrderedHandler(rebalanceTopic(cnt));
        }

        U.cancel(exchWorker);

        if (log.isDebugEnabled())
            log.debug("Before joining on exchange worker: " + exchWorker);

        U.join(exchWorker, log);

        ResendTimeoutObject resendTimeoutObj = pendingResend.getAndSet(null);

        if (resendTimeoutObj != null)
            cctx.time().removeTimeoutObject(resendTimeoutObj);
    }

    /** {@inheritDoc} */
    @SuppressWarnings("LockAcquiredButNotSafelyReleased")
    @Override protected void stop0(boolean cancel) {
        super.stop0(cancel);

        // Do not allow any activity in exchange manager after stop.
        busyLock.writeLock().lock();

        exchFuts = null;
    }

    /**
     * @param cacheId Cache ID.
     * @param exchFut Exchange future.
     * @return Topology.
     */
    public GridDhtPartitionTopology clientTopology(int cacheId, GridDhtPartitionsExchangeFuture exchFut) {
        GridClientPartitionTopology top = clientTops.get(cacheId);

        if (top != null)
            return top;

        GridClientPartitionTopology old = clientTops.putIfAbsent(cacheId,
            top = new GridClientPartitionTopology(cctx, cacheId, exchFut));

        return old != null ? old : top;
    }

    /**
     * @return Collection of client topologies.
     */
    public Collection<GridClientPartitionTopology> clientTopologies() {
        return clientTops.values();
    }

    /**
     * @param cacheId Cache ID.
     * @return Client partition topology.
     */
    public GridClientPartitionTopology clearClientTopology(int cacheId) {
        return clientTops.remove(cacheId);
    }

    /**
     * Gets topology version of last partition exchange, it is possible that last partition exchange
     * is not completed yet.
     *
     * @return Topology version.
     */
    public AffinityTopologyVersion topologyVersion() {
        GridDhtPartitionsExchangeFuture lastInitializedFut0 = lastInitializedFut;

        return lastInitializedFut0 != null
            ? lastInitializedFut0.exchangeId().topologyVersion() : AffinityTopologyVersion.NONE;
    }

    /**
     * @return Topology version of latest completed partition exchange.
     */
    public AffinityTopologyVersion readyAffinityVersion() {
        return readyTopVer.get();
    }

    /**
     * @return Last completed topology future.
     */
    public GridDhtTopologyFuture lastTopologyFuture() {
        return lastInitializedFut;
    }

    /**
     * @param ver Topology version.
     * @return Future or {@code null} is future is already completed.
     */
    @Nullable public IgniteInternalFuture<?> affinityReadyFuture(AffinityTopologyVersion ver) {
        GridDhtPartitionsExchangeFuture lastInitializedFut0 = lastInitializedFut;

        if (lastInitializedFut0 != null && lastInitializedFut0.topologyVersion().compareTo(ver) == 0) {
            if (log.isDebugEnabled())
                log.debug("Return lastInitializedFut for topology ready future " +
                    "[ver=" + ver + ", fut=" + lastInitializedFut0 + ']');

            return lastInitializedFut0;
        }

        AffinityTopologyVersion topVer = readyTopVer.get();

        if (topVer.compareTo(ver) >= 0) {
            if (log.isDebugEnabled())
                log.debug("Return finished future for topology ready future [ver=" + ver + ", topVer=" + topVer + ']');

            return null;
        }

        GridFutureAdapter<AffinityTopologyVersion> fut = F.addIfAbsent(readyFuts, ver,
            new AffinityReadyFuture(ver));

        if (log.isDebugEnabled())
            log.debug("Created topology ready future [ver=" + ver + ", fut=" + fut + ']');

        topVer = readyTopVer.get();

        if (topVer.compareTo(ver) >= 0) {
            if (log.isDebugEnabled())
                log.debug("Completing created topology ready future " +
                    "[ver=" + topVer + ", topVer=" + topVer + ", fut=" + fut + ']');

            fut.onDone(topVer);
        }
        else if (stopErr != null)
            fut.onDone(stopErr);

        return fut;
    }

    /**
     * Gets minimum node version for the given topology version.
     *
     * @param topVer Topology version to get minimum node version for.
     * @return Minimum node version.
     */
    public IgniteProductVersion minimumNodeVersion(AffinityTopologyVersion topVer) {
        IgnitePair<IgniteProductVersion> vers = nodeVers.get(topVer);

        return vers == null ? cctx.localNode().version() : vers.get1();
    }

    /**
     * @return {@code true} if entered to busy state.
     */
    private boolean enterBusy() {
        if (busyLock.readLock().tryLock())
            return true;

        if (log.isDebugEnabled())
            log.debug("Failed to enter to busy state (exchange manager is stopping): " + cctx.localNodeId());

        return false;
    }

    /**
     *
     */
    private void leaveBusy() {
        busyLock.readLock().unlock();
    }

    /**
     * @return Exchange futures.
     */
    public List<GridDhtPartitionsExchangeFuture> exchangeFutures() {
        return exchFuts.values();
    }

    /**
     * @return {@code True} if pending future queue is empty.
     */
    public boolean hasPendingExchange() {
        return !exchWorker.futQ.isEmpty();
    }

    /**
     * @param evt Discovery event.
     * @return Affinity topology version.
     */
    private AffinityTopologyVersion affinityTopologyVersion(DiscoveryEvent evt) {
        if (evt.type() == DiscoveryCustomEvent.EVT_DISCOVERY_CUSTOM_EVT)
            return ((DiscoveryCustomEvent)evt).affinityTopologyVersion();

        return new AffinityTopologyVersion(evt.topologyVersion());
    }

    /**
     * @param exchFut Exchange future.
     * @param reassign Dummy reassign flag.
     */
    public void forceDummyExchange(boolean reassign,
        GridDhtPartitionsExchangeFuture exchFut) {
        exchWorker.addFuture(
            new GridDhtPartitionsExchangeFuture(cctx, reassign, exchFut.discoveryEvent(), exchFut.exchangeId()));
    }

    /**
     * Forces preload exchange.
     *
     * @param exchFut Exchange future.
     */
    public void forcePreloadExchange(GridDhtPartitionsExchangeFuture exchFut) {
        exchWorker.addFuture(
            new GridDhtPartitionsExchangeFuture(cctx, exchFut.discoveryEvent(), exchFut.exchangeId()));
    }

    /**
     * Schedules next full partitions update.
     */
    public void scheduleResendPartitions() {
        ResendTimeoutObject timeout = pendingResend.get();

        if (timeout == null || timeout.started()) {
            ResendTimeoutObject update = new ResendTimeoutObject();

            if (pendingResend.compareAndSet(timeout, update))
                cctx.time().addTimeoutObject(update);
        }
    }

    /**
     * Partition refresh callback.
     */
    void refreshPartitions() {
        ClusterNode oldest = CU.oldestAliveCacheServerNode(cctx, AffinityTopologyVersion.NONE);

        if (oldest == null) {
            if (log.isDebugEnabled())
                log.debug("Skip partitions refresh, there are no server nodes [loc=" + cctx.localNodeId() + ']');

            return;
        }

        if (log.isDebugEnabled())
            log.debug("Refreshing partitions [oldest=" + oldest.id() + ", loc=" + cctx.localNodeId() + ']');

        Collection<ClusterNode> rmts;

        // If this is the oldest node.
        if (oldest.id().equals(cctx.localNodeId())) {
            rmts = CU.remoteNodes(cctx, AffinityTopologyVersion.NONE);

            if (log.isDebugEnabled())
                log.debug("Refreshing partitions from oldest node: " + cctx.localNodeId());

            sendAllPartitions(rmts);
        }
        else {
            if (log.isDebugEnabled())
                log.debug("Refreshing local partitions from non-oldest node: " +
                    cctx.localNodeId());

            sendLocalPartitions(oldest, null);
        }
    }

    /**
     * @param nodes Nodes.
     * @return {@code True} if message was sent, {@code false} if node left grid.
     */
    private boolean sendAllPartitions(Collection<? extends ClusterNode> nodes) {
        GridDhtPartitionsFullMessage m = new GridDhtPartitionsFullMessage(null, null, AffinityTopologyVersion.NONE);

        boolean useOldApi = false;

        for (ClusterNode node : nodes) {
            if (node.version().compareTo(GridDhtPartitionMap2.SINCE) < 0)
                useOldApi = true;
        }

        for (GridCacheContext cacheCtx : cctx.cacheContexts()) {
            if (!cacheCtx.isLocal() && cacheCtx.started()) {
                GridDhtPartitionFullMap locMap = cacheCtx.topology().partitionMap(true);

                if (useOldApi) {
                    locMap = new GridDhtPartitionFullMap(locMap.nodeId(),
                        locMap.nodeOrder(),
                        locMap.updateSequence(),
                        locMap);
                }

                m.addFullPartitionsMap(cacheCtx.cacheId(), locMap);
            }
        }

        // It is important that client topologies be added after contexts.
        for (GridClientPartitionTopology top : cctx.exchange().clientTopologies())
            m.addFullPartitionsMap(top.cacheId(), top.partitionMap(true));

        if (log.isDebugEnabled())
            log.debug("Sending all partitions [nodeIds=" + U.nodeIds(nodes) + ", msg=" + m + ']');

        for (ClusterNode node : nodes) {
            try {
                cctx.io().sendNoRetry(node, m, SYSTEM_POOL);
            }
            catch (ClusterTopologyCheckedException ignore) {
                if (log.isDebugEnabled())
                    log.debug("Failed to send partition update to node because it left grid (will ignore) [node=" +
                        node.id() + ", msg=" + m + ']');
            }
            catch (IgniteCheckedException e) {
                U.warn(log, "Failed to send partitions full message [node=" + node + ", err=" + e + ']');
            }
        }

        return true;
    }

    /**
     * @param node Node.
     * @param id ID.
     */
    private void sendLocalPartitions(ClusterNode node, @Nullable GridDhtPartitionExchangeId id) {
        GridDhtPartitionsSingleMessage m = new GridDhtPartitionsSingleMessage(id,
            cctx.kernalContext().clientNode(),
            cctx.versions().last());

        for (GridCacheContext cacheCtx : cctx.cacheContexts()) {
            if (!cacheCtx.isLocal()) {
                GridDhtPartitionMap2 locMap = cacheCtx.topology().localPartitionMap();

                if (node.version().compareTo(GridDhtPartitionMap2.SINCE) < 0)
                    locMap = new GridDhtPartitionMap(locMap.nodeId(), locMap.updateSequence(), locMap.map());

                m.addLocalPartitionMap(cacheCtx.cacheId(), locMap);
            }
        }

        for (GridClientPartitionTopology top : clientTops.values()) {
            GridDhtPartitionMap2 locMap = top.localPartitionMap();

            m.addLocalPartitionMap(top.cacheId(), locMap);
        }

        if (log.isDebugEnabled())
            log.debug("Sending local partitions [nodeId=" + node.id() + ", msg=" + m + ']');

        try {
            cctx.io().sendNoRetry(node, m, SYSTEM_POOL);
        }
        catch (ClusterTopologyCheckedException ignore) {
            if (log.isDebugEnabled())
                log.debug("Failed to send partition update to node because it left grid (will ignore) [node=" +
                    node.id() + ", msg=" + m + ']');
        }
        catch (IgniteCheckedException e) {
            U.error(log, "Failed to send local partition map to node [node=" + node + ", exchId=" + id + ']', e);
        }
    }

    /**
     * @param nodeId Cause node ID.
     * @param topVer Topology version.
     * @param evt Event type.
     * @return Activity future ID.
     */
    private GridDhtPartitionExchangeId exchangeId(UUID nodeId, AffinityTopologyVersion topVer, int evt) {
        return new GridDhtPartitionExchangeId(nodeId, evt, topVer);
    }

    /**
     * @param exchId Exchange ID.
     * @param discoEvt Discovery event.
     * @param reqs Cache change requests.
     * @param affChangeMsg Affinity change message.
     * @return Exchange future.
     */
    GridDhtPartitionsExchangeFuture exchangeFuture(GridDhtPartitionExchangeId exchId,
        @Nullable DiscoveryEvent discoEvt,
        @Nullable Collection<DynamicCacheChangeRequest> reqs,
        @Nullable CacheAffinityChangeMessage affChangeMsg) {
        GridDhtPartitionsExchangeFuture fut;

        GridDhtPartitionsExchangeFuture old = exchFuts.addx(
            fut = new GridDhtPartitionsExchangeFuture(cctx, busyLock, exchId, reqs, affChangeMsg));

        if (old != null) {
            fut = old;

            if (reqs != null)
                fut.cacheChangeRequests(reqs);

            if (affChangeMsg != null)
                fut.affinityChangeMessage(affChangeMsg);
        }

        if (discoEvt != null)
            fut.onEvent(exchId, discoEvt);

        if (stopErr != null)
            fut.onDone(stopErr);

        return fut;
    }

    /**
     * @param exchFut Exchange.
     * @param err Error.
     */
    public void onExchangeDone(GridDhtPartitionsExchangeFuture exchFut, @Nullable Throwable err) {
        AffinityTopologyVersion topVer = exchFut.topologyVersion();

        if (log.isDebugEnabled())
            log.debug("Exchange done [topVer=" + topVer + ", fut=" + exchFut + ", err=" + err + ']');

        IgniteProductVersion minVer = cctx.localNode().version();
        IgniteProductVersion maxVer = cctx.localNode().version();

        if (err == null) {
            if (!F.isEmpty(exchFut.discoveryEvent().topologyNodes())) {
                for (ClusterNode node : exchFut.discoveryEvent().topologyNodes()) {
                    IgniteProductVersion ver = node.version();

                    if (ver.compareTo(minVer) < 0)
                        minVer = ver;

                    if (ver.compareTo(maxVer) > 0)
                        maxVer = ver;
                }
            }
        }

        nodeVers.put(topVer, new IgnitePair<>(minVer, maxVer));

        AffinityTopologyVersion histVer = new AffinityTopologyVersion(topVer.topologyVersion() - 10, 0);

        for (AffinityTopologyVersion oldVer : nodeVers.headMap(histVer).keySet())
            nodeVers.remove(oldVer);

        if (err == null) {
            while (true) {
                AffinityTopologyVersion readyVer = readyTopVer.get();

                if (readyVer.compareTo(topVer) >= 0)
                    break;

                if (readyTopVer.compareAndSet(readyVer, topVer))
                    break;
            }

            for (Map.Entry<AffinityTopologyVersion, AffinityReadyFuture> entry : readyFuts.entrySet()) {
                if (entry.getKey().compareTo(topVer) <= 0) {
                    if (log.isDebugEnabled())
                        log.debug("Completing created topology ready future " +
                            "[ver=" + topVer + ", fut=" + entry.getValue() + ']');

                    entry.getValue().onDone(topVer);
                }
            }
        }
        else {
            for (Map.Entry<AffinityTopologyVersion, AffinityReadyFuture> entry : readyFuts.entrySet()) {
                if (entry.getKey().compareTo(topVer) <= 0) {
                    if (log.isDebugEnabled())
                        log.debug("Completing created topology ready future with error " +
                            "[ver=" + topVer + ", fut=" + entry.getValue() + ']');

                    entry.getValue().onDone(err);
                }
            }
        }

        ExchangeFutureSet exchFuts0 = exchFuts;

        if (exchFuts0 != null) {
            int skipped = 0;

            for (GridDhtPartitionsExchangeFuture fut : exchFuts0.values()) {
                if (exchFut.exchangeId().topologyVersion().compareTo(fut.exchangeId().topologyVersion()) < 0)
                    continue;

                skipped++;

                if (skipped > 10)
                    fut.cleanUp();
            }
        }
    }

    /**
     * @param fut Future.
     * @return {@code True} if added.
     */
    private boolean addFuture(GridDhtPartitionsExchangeFuture fut) {
        if (fut.onAdded()) {
            exchWorker.addFuture(fut);

            return true;
        }

        return false;
    }

    /**
     * @param node Node.
     * @param msg Message.
     */
    private void processFullPartitionUpdate(ClusterNode node, GridDhtPartitionsFullMessage msg) {
        if (!enterBusy())
            return;

        try {
            if (msg.exchangeId() == null) {
                if (log.isDebugEnabled())
                    log.debug("Received full partition update [node=" + node.id() + ", msg=" + msg + ']');

                boolean updated = false;

                for (Map.Entry<Integer, GridDhtPartitionFullMap> entry : msg.partitions().entrySet()) {
                    Integer cacheId = entry.getKey();

                    GridCacheContext<K, V> cacheCtx = cctx.cacheContext(cacheId);

                    if (cacheCtx != null && !cacheCtx.started())
                        continue; // Can safely ignore background exchange.

                    GridDhtPartitionTopology top = null;

                    if (cacheCtx == null)
                        top = clientTops.get(cacheId);
                    else if (!cacheCtx.isLocal())
                        top = cacheCtx.topology();

                    if (top != null)
                        updated |= top.update(null, entry.getValue(), null) != null;
                }

                if (!cctx.kernalContext().clientNode() && updated)
                    refreshPartitions();
            }
            else
                exchangeFuture(msg.exchangeId(), null, null, null).onReceive(node, msg);
        }
        finally {
            leaveBusy();
        }
    }

    /**
     * @param node Node ID.
     * @param msg Message.
     */
    private void processSinglePartitionUpdate(final ClusterNode node, final GridDhtPartitionsSingleMessage msg) {
        if (!enterBusy())
            return;

        try {
            if (msg.exchangeId() == null) {
                if (log.isDebugEnabled())
                    log.debug("Received local partition update [nodeId=" + node.id() + ", parts=" +
                        msg + ']');

                boolean updated = false;

                for (Map.Entry<Integer, GridDhtPartitionMap2> entry : msg.partitions().entrySet()) {
                    Integer cacheId = entry.getKey();

                    GridCacheContext<K, V> cacheCtx = cctx.cacheContext(cacheId);

                    if (cacheCtx != null && cacheCtx.startTopologyVersion() != null &&
                        entry.getValue() != null &&
                        entry.getValue().topologyVersion() != null && // Backward compatibility.
                        cacheCtx.startTopologyVersion().compareTo(entry.getValue().topologyVersion()) > 0)
                        continue;

                    GridDhtPartitionTopology top = null;

                    if (cacheCtx == null)
                        top = clientTops.get(cacheId);
                    else if (!cacheCtx.isLocal())
                        top = cacheCtx.topology();

                    if (top != null) {
                        updated |= top.update(null, entry.getValue(), null) != null;

                        cctx.affinity().checkRebalanceState(top, cacheId);
                    }
                }

                if (updated)
                    scheduleResendPartitions();
            }
            else {
                if (msg.client()) {
                    final GridDhtPartitionsExchangeFuture exchFut = exchangeFuture(msg.exchangeId(),
                        null,
                        null,
                        null);

                    exchFut.listen(new CI1<IgniteInternalFuture<AffinityTopologyVersion>>() {
                        @Override public void apply(IgniteInternalFuture<AffinityTopologyVersion> fut) {
                            // Finished future should reply only to sender client node.
                            exchFut.onReceive(node, msg);
                        }
                    });
                }
                else
                    exchangeFuture(msg.exchangeId(), null, null, null).onReceive(node, msg);
            }
        }
        finally {
            leaveBusy();
        }
    }

    /**
     * @param node Node ID.
     * @param msg Message.
     */
    private void processSinglePartitionRequest(ClusterNode node, GridDhtPartitionsSingleRequest msg) {
        if (!enterBusy())
            return;

        try {
            sendLocalPartitions(node, msg.exchangeId());
        }
        finally {
            leaveBusy();
        }
    }

    /**
     * @throws Exception If failed.
     */
    public void dumpDebugInfo() throws Exception {
        dumpDebugInfo(null);
    }

    /**
     * @param exchTopVer Optional current exchange topology version.
     * @throws Exception If failed.
     */
    public void dumpDebugInfo(@Nullable AffinityTopologyVersion exchTopVer) throws Exception {
        U.warn(log, "Ready affinity version: " + readyTopVer.get());

        U.warn(log, "Last exchange future: " + lastInitializedFut);

        U.warn(log, "Pending exchange futures:");

        for (GridDhtPartitionsExchangeFuture fut : exchWorker.futQ)
            U.warn(log, ">>> " + fut);

        if (!readyFuts.isEmpty()) {
            U.warn(log, "Pending affinity ready futures:");

            for (AffinityReadyFuture fut : readyFuts.values())
                U.warn(log, ">>> " + fut);
        }

        ExchangeFutureSet exchFuts = this.exchFuts;

        if (exchFuts != null) {
            U.warn(log, "Last 10 exchange futures (total: " + exchFuts.size() + "):");

            int cnt = 0;

            for (GridDhtPartitionsExchangeFuture fut : exchFuts.values()) {
                U.warn(log, ">>> " + fut);

                if (++cnt == 10)
                    break;
            }
        }

        dumpPendingObjects(exchTopVer);

        for (GridCacheContext cacheCtx : cctx.cacheContexts())
            cacheCtx.preloader().dumpDebugInfo();

        cctx.affinity().dumpDebugInfo();

        // Dump IO manager statistics.
        cctx.gridIO().dumpStats();
    }

    /**
     * @param exchTopVer Exchange topology version.
     */
    private void dumpPendingObjects(@Nullable AffinityTopologyVersion exchTopVer) {
        IgniteTxManager tm = cctx.tm();

        if (tm != null) {
            U.warn(log, "Pending transactions:");

            for (IgniteInternalTx tx : tm.activeTransactions()) {
                if (exchTopVer != null) {
                    U.warn(log, ">>> [txVer=" + tx.topologyVersionSnapshot() +
                        ", exchWait=" + tm.needWaitTransaction(tx, exchTopVer) +
                        ", tx=" + tx + ']');
                }
                else
                    U.warn(log, ">>> [txVer=" + tx.topologyVersionSnapshot() + ", tx=" + tx + ']');
            }
        }

        GridCacheMvccManager mvcc = cctx.mvcc();

        if (mvcc != null) {
            U.warn(log, "Pending explicit locks:");

            for (GridCacheExplicitLockSpan lockSpan : mvcc.activeExplicitLocks())
                U.warn(log, ">>> " + lockSpan);

            U.warn(log, "Pending cache futures:");

            for (GridCacheFuture<?> fut : mvcc.activeFutures())
                U.warn(log, ">>> " + fut);

            U.warn(log, "Pending atomic cache futures:");

            for (GridCacheFuture<?> fut : mvcc.atomicFutures())
                U.warn(log, ">>> " + fut);
        }

        for (GridCacheContext ctx : cctx.cacheContexts()) {
            if (ctx.isLocal())
                continue;

            GridCacheContext ctx0 = ctx.isNear() ? ctx.near().dht().context() : ctx;

            GridCachePreloader preloader = ctx0.preloader();

            if (preloader != null)
                preloader.dumpDebugInfo();

            GridCacheAffinityManager affMgr = ctx0.affinity();

            if (affMgr != null)
                affMgr.dumpDebugInfo();
        }
    }

    /**
     * @param deque Deque to poll from.
     * @param time Time to wait.
     * @param w Worker.
     * @return Polled item.
     * @throws InterruptedException If interrupted.
     */
    @Nullable private <T> T poll(BlockingQueue<T> deque, long time, GridWorker w) throws InterruptedException {
        assert w != null;

        // There is currently a case where {@code interrupted}
        // flag on a thread gets flipped during stop which causes the pool to hang.  This check
        // will always make sure that interrupted flag gets reset before going into wait conditions.
        // The true fix should actually make sure that interrupted flag does not get reset or that
        // interrupted exception gets propagated. Until we find a real fix, this method should
        // always work to make sure that there is no hanging during stop.
        if (w.isCancelled())
            Thread.currentThread().interrupt();

        return deque.poll(time, MILLISECONDS);
    }

    /**
     * Exchange future thread. All exchanges happen only by one thread and next
     * exchange will not start until previous one completes.
     */
    private class ExchangeWorker extends GridWorker {
        /** Future queue. */
        private final LinkedBlockingDeque<GridDhtPartitionsExchangeFuture> futQ =
            new LinkedBlockingDeque<>();

        /** Busy flag used as performance optimization to stop current preloading. */
        private volatile boolean busy;

        /**
         *
         */
        private ExchangeWorker() {
            super(cctx.gridName(), "partition-exchanger", GridCachePartitionExchangeManager.this.log);
        }

        /**
         * @param exchFut Exchange future.
         */
        void addFuture(GridDhtPartitionsExchangeFuture exchFut) {
            assert exchFut != null;

            if (!exchFut.dummy() || (futQ.isEmpty() && !busy))
                futQ.offer(exchFut);

            if (log.isDebugEnabled())
                log.debug("Added exchange future to exchange worker: " + exchFut);
        }

        /** {@inheritDoc} */
        @Override protected void body() throws InterruptedException, IgniteInterruptedCheckedException {
            long timeout = cctx.gridConfig().getNetworkTimeout();

            boolean startEvtFired = false;

            int cnt = 0;

            IgniteInternalFuture asyncStartFut = null;

            while (!isCancelled()) {
                GridDhtPartitionsExchangeFuture exchFut = null;

                cnt++;

                try {
                    boolean preloadFinished = true;

                    for (GridCacheContext cacheCtx : cctx.cacheContexts()) {
                        preloadFinished &= cacheCtx.preloader() != null && cacheCtx.preloader().syncFuture().isDone();

                        if (!preloadFinished)
                            break;
                    }

                    // If not first preloading and no more topology events present.
                    if (!cctx.kernalContext().clientNode() && futQ.isEmpty() && preloadFinished)
                        timeout = cctx.gridConfig().getNetworkTimeout();

                    // After workers line up and before preloading starts we initialize all futures.
                    if (log.isDebugEnabled()) {
                        Collection<IgniteInternalFuture> unfinished = new HashSet<>();

                        for (GridDhtPartitionsExchangeFuture fut : exchFuts.values()) {
                            if (!fut.isDone())
                                unfinished.add(fut);
                        }

                        log.debug("Before waiting for exchange futures [futs" + unfinished + ", worker=" + this + ']');
                    }

                    // Take next exchange future.
                    exchFut = poll(futQ, timeout, this);

                    if (exchFut == null)
                        continue; // Main while loop.

                    busy = true;

                    Map<Integer, GridDhtPreloaderAssignments> assignsMap = null;

                    boolean dummyReassign = exchFut.dummyReassign();
                    boolean forcePreload = exchFut.forcePreload();

                    try {
                        if (isCancelled())
                            break;

                        if (!exchFut.dummy() && !exchFut.forcePreload()) {
                            lastInitializedFut = exchFut;

                            exchFut.init();

                            int dumpedObjects = 0;

                            while (true) {
                                try {
                                    exchFut.get(2 * cctx.gridConfig().getNetworkTimeout(), TimeUnit.MILLISECONDS);

                                    break;
                                }
                                catch (IgniteFutureTimeoutCheckedException ignored) {
                                    U.warn(log, "Failed to wait for partition map exchange [" +
                                        "topVer=" + exchFut.topologyVersion() +
                                        ", node=" + cctx.localNodeId() + "]. " +
                                        "Dumping pending objects that might be the cause: ");

                                    if (dumpedObjects < GridDhtPartitionsExchangeFuture.DUMP_PENDING_OBJECTS_THRESHOLD) {
                                        try {
                                            dumpDebugInfo(exchFut.topologyVersion());
                                        }
                                        catch (Exception e) {
                                            U.error(log, "Failed to dump debug information: " + e, e);
                                        }

                                        if (IgniteSystemProperties.getBoolean(IGNITE_THREAD_DUMP_ON_EXCHANGE_TIMEOUT, false))
                                            U.dumpThreads(log);

                                        dumpedObjects++;
                                    }
                                }
                            }


                            if (log.isDebugEnabled())
                                log.debug("After waiting for exchange future [exchFut=" + exchFut + ", worker=" +
                                    this + ']');

                            if (exchFut.exchangeId().nodeId().equals(cctx.localNodeId()))
                                lastRefresh.compareAndSet(-1, U.currentTimeMillis());

                            boolean changed = false;

                            // Just pick first worker to do this, so we don't
                            // invoke topology callback more than once for the
                            // same event.
                            for (GridCacheContext cacheCtx : cctx.cacheContexts()) {
                                if (cacheCtx.isLocal())
                                    continue;

                                changed |= cacheCtx.topology().afterExchange(exchFut);

                                // Preload event notification.
                                if (!exchFut.skipPreload() && cacheCtx.events().isRecordable(EVT_CACHE_REBALANCE_STARTED)) {
                                    if (!cacheCtx.isReplicated() || !startEvtFired) {
                                        DiscoveryEvent discoEvt = exchFut.discoveryEvent();

                                        cacheCtx.events().addPreloadEvent(-1, EVT_CACHE_REBALANCE_STARTED,
                                            discoEvt.eventNode(), discoEvt.type(), discoEvt.timestamp());
                                    }
                                }
                            }

                            startEvtFired = true;

                            if (!cctx.kernalContext().clientNode() && changed && futQ.isEmpty())
                                refreshPartitions();
                        }
                        else {
                            if (log.isDebugEnabled())
                                log.debug("Got dummy exchange (will reassign)");

                            if (!dummyReassign) {
                                timeout = 0; // Force refresh.

                                continue;
                            }
                        }

                        if (!exchFut.skipPreload()) {
                            assignsMap = new HashMap<>();

                            for (GridCacheContext cacheCtx : cctx.cacheContexts()) {
                                long delay = cacheCtx.config().getRebalanceDelay();

                                GridDhtPreloaderAssignments assigns = null;

                                // Don't delay for dummy reassigns to avoid infinite recursion.
                                if (delay == 0 || forcePreload)
                                    assigns = cacheCtx.preloader().assign(exchFut);

                                assignsMap.put(cacheCtx.cacheId(), assigns);
                            }
                        }
                    }
                    finally {
                        // Must flip busy flag before assignments are given to demand workers.
                        busy = false;
                    }

                    if (assignsMap != null) {
                        int size = assignsMap.size();

                        rebalanceQ.clear();

                        NavigableMap<Integer, List<Integer>> orderMap = new TreeMap<>();

                        for (Map.Entry<Integer, GridDhtPreloaderAssignments> e : assignsMap.entrySet()) {
                            int cacheId = e.getKey();

                            GridCacheContext<K, V> cacheCtx = cctx.cacheContext(cacheId);

                            int order = cacheCtx.config().getRebalanceOrder();

                            if (orderMap.get(order) == null)
                                orderMap.put(order, new ArrayList<Integer>(size));

                            orderMap.get(order).add(cacheId);
                        }

                        Callable<Boolean> marshR = null;
                        List<Callable<Boolean>> orderedRs = new ArrayList<>(size);

                        //Ordered rebalance scheduling.
                        for (Integer order : orderMap.keySet()) {
                            for (Integer cacheId : orderMap.get(order)) {
                                GridCacheContext<K, V> cacheCtx = cctx.cacheContext(cacheId);

                                List<String> waitList = new ArrayList<>(size - 1);

                                for (List<Integer> cIds : orderMap.headMap(order).values()) {
                                    for (Integer cId : cIds)
                                        waitList.add(cctx.cacheContext(cId).name());
                                }

                                Callable<Boolean> r = cacheCtx.preloader().addAssignments(assignsMap.get(cacheId),
                                    forcePreload,
                                    waitList,
                                    cnt);

                                if (r != null) {
                                    U.log(log, "Cache rebalancing scheduled: [cache=" + cacheCtx.name() +
                                        ", waitList=" + waitList.toString() + "]");

                                    if (cacheId == CU.cacheId(GridCacheUtils.MARSH_CACHE_NAME))
                                        marshR = r;
                                    else
                                        orderedRs.add(r);
                                }
                            }
                        }

                        if (asyncStartFut != null)
                            asyncStartFut.get(); // Wait for thread stop.

                        rebalanceQ.addAll(orderedRs);

                        if (marshR != null || !rebalanceQ.isEmpty()) {
                            if (futQ.isEmpty()) {
                                U.log(log, "Rebalancing required " +
                                    "[top=" + exchFut.topologyVersion() + ", evt=" + exchFut.discoveryEvent().name() +
                                    ", node=" + exchFut.discoveryEvent().eventNode().id() + ']');

                                if (marshR != null) {
                                    try {
                                        marshR.call(); //Marshaller cache rebalancing launches in sync way.
                                    }
                                    catch (Exception ex) {
                                        if (log.isDebugEnabled())
                                            log.debug("Failed to send initial demand request to node");

                                        continue;
                                    }
                                }

                                final GridFutureAdapter fut = new GridFutureAdapter();

                                asyncStartFut = fut;

                                cctx.kernalContext().closure().callLocalSafe(new GPC<Boolean>() {
                                    @Override public Boolean call() {
                                        try {
                                            while (true) {
                                                Callable<Boolean> r = rebalanceQ.poll();

                                                if (r == null)
                                                    return false;

                                                if (!r.call())
                                                    return false;
                                            }
                                        }
                                        catch (Exception ex) {
                                            if (log.isDebugEnabled())
                                                log.debug("Failed to send initial demand request to node");

                                            return false;
                                        }
                                        finally {
                                            fut.onDone();
                                        }
                                    }
                                }, /*system pool*/true);
                            }
                            else {
                                U.log(log, "Skipping rebalancing (obsolete exchange ID) " +
                                    "[top=" + exchFut.topologyVersion() + ", evt=" + exchFut.discoveryEvent().name() +
                                    ", node=" + exchFut.discoveryEvent().eventNode().id() + ']');
                            }
                        }
                        else {
                            U.log(log, "Skipping rebalancing (nothing scheduled) " +
                                "[top=" + exchFut.topologyVersion() + ", evt=" + exchFut.discoveryEvent().name() +
                                ", node=" + exchFut.discoveryEvent().eventNode().id() + ']');
                        }
                    }
                }
                catch (IgniteInterruptedCheckedException e) {
                    throw e;
                }
                catch (IgniteClientDisconnectedCheckedException e) {
                    return;
                }
                catch (IgniteCheckedException e) {
                    U.error(log, "Failed to wait for completion of partition map exchange " +
                        "(preloading will not start): " + exchFut, e);
                }
            }
        }
    }

    /**
     * Partition resend timeout object.
     */
    private class ResendTimeoutObject implements GridTimeoutObject {
        /** Timeout ID. */
        private final IgniteUuid timeoutId = IgniteUuid.randomUuid();

        /** Timeout start time. */
        private final long createTime = U.currentTimeMillis();

        /** Started flag. */
        private AtomicBoolean started = new AtomicBoolean();

        /** {@inheritDoc} */
        @Override public IgniteUuid timeoutId() {
            return timeoutId;
        }

        /** {@inheritDoc} */
        @Override public long endTime() {
            return createTime + partResendTimeout;
        }

        /** {@inheritDoc} */
        @Override public void onTimeout() {
            cctx.kernalContext().closure().runLocalSafe(new Runnable() {
                @Override public void run() {
                    if (!busyLock.readLock().tryLock())
                        return;

                    try {
                        if (started.compareAndSet(false, true))
                            refreshPartitions();
                    }
                    finally {
                        busyLock.readLock().unlock();

                        cctx.time().removeTimeoutObject(ResendTimeoutObject.this);

                        pendingResend.compareAndSet(ResendTimeoutObject.this, null);
                    }
                }
            });
        }

        /**
         * @return {@code True} if timeout object started to run.
         */
        public boolean started() {
            return started.get();
        }
    }

    /**
     *
     */
    private static class ExchangeFutureSet extends GridListSet<GridDhtPartitionsExchangeFuture> {
        /** */
        private static final long serialVersionUID = 0L;

        /**
         * Creates ordered, not strict list set.
         */
        private ExchangeFutureSet() {
            super(new Comparator<GridDhtPartitionsExchangeFuture>() {
                @Override public int compare(
                    GridDhtPartitionsExchangeFuture f1,
                    GridDhtPartitionsExchangeFuture f2
                ) {
                    AffinityTopologyVersion t1 = f1.exchangeId().topologyVersion();
                    AffinityTopologyVersion t2 = f2.exchangeId().topologyVersion();

                    assert t1.topologyVersion() > 0;
                    assert t2.topologyVersion() > 0;

                    // Reverse order.
                    return t2.compareTo(t1);
                }
            }, /*not strict*/false);
        }

        /**
         * @param fut Future to add.
         * @return {@code True} if added.
         */
        @Override public synchronized GridDhtPartitionsExchangeFuture addx(
            GridDhtPartitionsExchangeFuture fut) {
            GridDhtPartitionsExchangeFuture cur = super.addx(fut);

            while (size() > EXCHANGE_HISTORY_SIZE)
                removeLast();

            // Return the value in the set.
            return cur == null ? fut : cur;
        }

        /** {@inheritDoc} */
        @Nullable @Override public synchronized GridDhtPartitionsExchangeFuture removex(
            GridDhtPartitionsExchangeFuture val
        ) {
            return super.removex(val);
        }

        /**
         * @return Values.
         */
        @Override public synchronized List<GridDhtPartitionsExchangeFuture> values() {
            return super.values();
        }

        /** {@inheritDoc} */
        @Override public synchronized String toString() {
            return S.toString(ExchangeFutureSet.class, this, super.toString());
        }
    }

    /**
     *
     */
    private abstract class MessageHandler<M> implements IgniteBiInClosure<UUID, M> {
        /** */
        private static final long serialVersionUID = 0L;

        /** {@inheritDoc} */
        @Override public void apply(UUID nodeId, M msg) {
            ClusterNode node = cctx.node(nodeId);

            if (node == null) {
                if (log.isDebugEnabled())
                    log.debug("Received message from failed node [node=" + nodeId + ", msg=" + msg + ']');

                return;
            }

            if (log.isDebugEnabled())
                log.debug("Received message from node [node=" + nodeId + ", msg=" + msg + ']');

            onMessage(node , msg);
        }

        /**
         * @param node Node.
         * @param msg Message.
         */
        protected abstract void onMessage(ClusterNode node, M msg);
    }

    /**
     * Affinity ready future.
     */
    private class AffinityReadyFuture extends GridFutureAdapter<AffinityTopologyVersion> {
        /** */
        private static final long serialVersionUID = 0L;

        /** */
        @GridToStringInclude
        private AffinityTopologyVersion topVer;

        /**
         * @param topVer Topology version.
         */
        private AffinityReadyFuture(AffinityTopologyVersion topVer) {
            this.topVer = topVer;
        }

        /** {@inheritDoc} */
        @Override public boolean onDone(AffinityTopologyVersion res, @Nullable Throwable err) {
            assert res != null || err != null;

            boolean done = super.onDone(res, err);

            if (done)
                readyFuts.remove(topVer, this);

            return done;
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(AffinityReadyFuture.class, this, super.toString());
        }
    }
}
