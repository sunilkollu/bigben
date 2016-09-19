package com.walmart.gmp.feeds;

import com.google.common.base.Function;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListenableFuture;
import com.walmart.gmp.ingestion.platform.framework.data.core.DataManager;
import com.walmart.gmp.ingestion.platform.framework.data.core.Entity;
import com.walmart.gmp.ingestion.platform.framework.data.core.Selector;
import com.walmart.gmp.ingestion.platform.framework.data.core.TaskExecutor;
import com.walmart.gmp.ingestion.platform.framework.data.model.FeedItemStatusKey;
import com.walmart.gmp.ingestion.platform.framework.data.model.FeedStatusEntity;
import com.walmart.gmp.ingestion.platform.framework.data.model.ItemStatusEntity;
import com.walmart.gmp.ingestion.platform.framework.data.model.impl.v2.index.V2ItemStatusIndexEntity;
import com.walmart.gmp.ingestion.platform.framework.data.model.impl.v2.index.V2ItemStatusIndexKey;
import com.walmart.gmp.ingestion.platform.framework.feed.FeedData.FeedStatus;
import com.walmart.partnerapi.error.GatewayError;
import com.walmart.partnerapi.model.v2.ItemStatus;
import com.walmart.services.nosql.data.CqlDAO;
import com.walmartlabs.components.scheduler.entities.Event;
import com.walmartlabs.components.scheduler.processors.EventProcessor;
import info.archinnov.achilles.persistence.AsyncManager;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.InitializingBean;

import java.util.*;
import java.util.Map.Entry;

import static com.google.common.collect.Iterables.concat;
import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.util.concurrent.Futures.*;
import static com.walmart.gmp.ingestion.platform.framework.core.Props.PROPS;
import static com.walmart.gmp.ingestion.platform.framework.data.core.DataManager.entity;
import static com.walmart.gmp.ingestion.platform.framework.data.core.DataManager.retryableExceptions;
import static com.walmart.gmp.ingestion.platform.framework.data.core.EntityVersion.V2;
import static com.walmart.gmp.ingestion.platform.framework.data.core.Selector.selector;
import static com.walmart.gmp.ingestion.platform.framework.feed.FeedData.FeedStatus.*;
import static com.walmart.gmp.ingestion.platform.framework.feed.FeedData.FeedStatus.INPROGRESS;
import static com.walmart.gmp.ingestion.platform.framework.utils.PerfUtils.measure;
import static com.walmart.partnerapi.model.v2.ItemStatus.*;
import static com.walmart.platform.soa.common.exception.util.ExceptionUtil.getRootCause;
import static com.walmart.platform.soa.common.exception.util.ExceptionUtil.getStackTraceString;
import static com.walmart.services.common.util.JsonUtil.convertToString;
import static java.lang.Integer.parseInt;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Collectors.toList;

/**
 * Created by smalik3 on 8/24/16
 */
public class FeedStatusAndCountsChecker implements EventProcessor<Event>, InitializingBean {

    private static final Logger L = Logger.getLogger(FeedStatusAndCountsChecker.class);

    private AsyncManager itemAM;

    private int fetchSize;

    private final Selector<String, FeedStatusEntity> feedSelector =
            selector(FeedStatusEntity.class, e -> {
                e.getEntity_count();
                e.getSystemErrorCount();
                e.getTimeoutErrorCount();
                e.getSuccessCount();
                e.getDataErrorCount();
                e.getFeed_status();
            });

    private final Map<String, DataManager<?, ?>> map = new HashMap<>();

    public FeedStatusAndCountsChecker(Map<String, Object> properties) throws Exception {
        createDM(properties.get("feedDMCCMPath"), "feed");
        createDM(properties.get("itemDMCCMPath"), "item");
        createDM(properties.get("itemIndexDMCCMPath"), "item_index");
        afterPropertiesSet();
    }

    public void createDM(Object ccmPath, Object dmName) throws Exception {
        final DataManager<Object, Entity<Object>> dm = new DataManager<>(ccmPath.toString());
        map.put(dmName.toString(), dm);
    }

    @SuppressWarnings("unchecked")
    private <K, T extends Entity<K>> DataManager<K, T> dm(String key) {
        return (DataManager<K, T>) map.get(key);
    }

    @Override
    public ListenableFuture<Event> process(Event event) {
        L.info(format("feedId: %s, processing event for status and counts: ", event.getXrefId()));
        final DataManager<String, FeedStatusEntity> feedDM = dm("feed");
        try {
            final String feedId = event.getXrefId();
            return measure(feedId, L, () -> catching(transformAsync(feedDM.getAsync(feedId, feedSelector), fes -> {
                final FeedStatusEntity entity = entity(FeedStatusEntity.class, feedId);
                entity.setModified_by("BigBen");
                final int itemCount = fes.getEntity_count() == null ? 0 : parseInt(fes.getEntity_count());
                final String feedStatus = fes.getFeed_status();
                if (itemCount == 0) {
                    L.debug(format("feedId: %s has item count as 0", feedId));
                    if (!ERROR.name().equals(feedStatus) || !PROCESSED.name().equals(feedStatus)) {
                        L.warn(format("feedId: %s was not marked %s or %s, marking it as %s", feedId, ERROR, PROCESSED, ERROR));
                        entity.setFeed_status(ERROR.name());
                        entity.setError_code(convertToString(createGatewayError()));
                        entity.setModified_dtm(new Date());
                        return transform(feedDM.saveAsync(entity), (Function<FeedStatusEntity, Event>) $ -> event);
                    } else {
                        L.info(format("feedId: %s was already marked as %s, nothing to do", feedId, feedStatus));
                        return immediateFuture(event);
                    }
                }
                switch (FeedStatus.valueOf(feedStatus == null ? INPROGRESS.name() : feedStatus)) {
                    case PROCESSED:
                        L.info(format("feedId: %s is already marked PROCESSED, nothing to do", feedId));
                        return immediateFuture(event);
                    case RECEIVED:
                        L.warn(format("feedId: %s, is still in received status: %s, marking all items timed out", feedId, feedStatus));
                        entity.setSuccessCount(0);
                        entity.setSystemErrorCount(0);
                        entity.setDataErrorCount(0);
                        entity.setTimeoutErrorCount(itemCount);
                        entity.setError_code(convertToString(createGatewayError()));
                        entity.setFeed_status(ERROR.name());
                        entity.setModified_dtm(new Date());
                        return transform(feedDM.saveAsync(entity), (Function<FeedStatusEntity, Event>) $ -> event);
                    case INPROGRESS:
                        L.warn(format("feedId: %s, is still in progress status: %s, initiating the time out procedure", feedId, feedStatus));
                        final Map<ItemStatus, Integer> countsMap = new HashMap<>();
                        return transformAsync(calculateCounts(feedId, itemCount, PROPS.getInteger("feeds.shard.size", 1000), countsMap), $ -> {
                            entity.setSuccessCount(countsMap.get(SUCCESS));
                            entity.setSystemErrorCount(countsMap.get(SYSTEM_ERROR));
                            entity.setDataErrorCount(countsMap.get(DATA_ERROR));
                            int nonTimeOuts = 0;
                            int timeOuts = 0;
                            for (Entry<ItemStatus, Integer> e : countsMap.entrySet()) {
                                if (e.getKey() != TIMEOUT_ERROR)
                                    nonTimeOuts += e.getValue();
                                else timeOuts += e.getValue();
                            }
                            if (nonTimeOuts + timeOuts == itemCount) {
                                L.warn(format("feedId: %s, was processed, but counts were not synced up, syncing them, counts are: %s", feedId, countsMap));
                                entity.setTimeoutErrorCount(countsMap.get(TIMEOUT_ERROR));
                            } else {
                                L.warn(format("feedId: %s has timed out, marking the final status and counts: %s", feedId, countsMap));
                                entity.setTimeoutErrorCount(itemCount - nonTimeOuts);
                            }
                            entity.setFeed_status(PROCESSED.name());
                            entity.setModified_dtm(new Date());
                            return transform(feedDM.saveAsync(entity), (Function<FeedStatusEntity, Event>) $$ -> event);
                        });
                    case ERROR:
                        L.info(format("feedId: %s is already marked ERROR, nothing to do", feedId));
                        return immediateFuture(event);
                    default:
                        throw new IllegalArgumentException(format("unknown feed status: %s for feedId: %s", feedStatus, feedId));
                }
            }), Exception.class, ex -> {
                final Throwable cause = getRootCause(ex);
                if (retryableExceptions.contains(cause.getClass())) {
                    L.warn(format("feedId: %s, event failed with a retryable error" + event, event.getXrefId()), cause);
                    event.setError(getStackTraceString(cause));
                } else
                    L.error(format("feedId: %s, event failed with a non-retryable error, will not be tried again: " + event, event.getXrefId()), cause);
                return event;
            }), "feed-status-count-checker");
        } catch (Exception e) {
            final Throwable cause = getRootCause(e);
            if (retryableExceptions.contains(cause.getClass())) {
                L.warn(format("feedId: %s, event failed with a retryable error" + event, event.getXrefId()), cause);
                event.setError(getStackTraceString(cause));
            } else
                L.error(format("feedId: %s, event failed with a non-retryable error, will not be tried again: " + event, event.getXrefId()), cause);
            return immediateFuture(event);
        }
    }

    private GatewayError createGatewayError() {
        GatewayError gatewayError = new GatewayError();
        gatewayError.setCode("ERR_PDI_0001");
        gatewayError.setType("SYSTEM_ERROR");
        gatewayError.setInfo("Feed SLA violated");
        gatewayError.setDescription("Feed SLA violated. Please reach out to operations team to (possibly) re-ingest this feed");
        gatewayError.setComponent("BigBen");
        return gatewayError;
    }

    private static final ListenableFuture<List<StrippedItemDO>> DONE = immediateFuture(null);

    private ListenableFuture<List<StrippedItemDO>> calculateCounts(String feedId, int itemCount, int shardSize, Map<ItemStatus, Integer> counts) {
        int numShards = itemCount % shardSize == 0 ? itemCount / shardSize : 1 + itemCount / shardSize;
        final List<Integer> shards = new ArrayList<>();
        for (int i = 0; i < numShards; i++) {
            shards.add(i);
        }
        L.info(format("feedId: %s, calculating counts, spanning over shards: %s", feedId, shards));
        return transform(allAsList(shards.stream().map(shard -> calculateCounts0(feedId, shard, -1, counts)).filter(out -> out != null).collect(toList())),
                (Function<List<List<StrippedItemDO>>, List<StrippedItemDO>>) ll -> {
                    if (ll == null) {
                        return null;
                    } else {
                        return newArrayList(concat(ll.stream().filter(strippedItemDOs -> strippedItemDOs != null).collect(toList())));
                    }
                });
    }

    private final TaskExecutor taskExecutor = new TaskExecutor(retryableExceptions);

    private ListenableFuture<List<StrippedItemDO>> calculateCounts0(String feedId, int shard, int itemIndex, Map<ItemStatus, Integer> counts) {
        @SuppressWarnings("unchecked")
        final ListenableFuture<List<StrippedItemDO>> f =
                taskExecutor.async(() -> itemAM.sliceQuery(StrippedItemDO.class).forSelect().
                        withPartitionComponents(feedId, shard).fromClusterings(itemIndex).withExclusiveBounds().
                        limit(fetchSize).get(), "fetch-feed-items:" + feedId + "(" + itemIndex + ", Inf)", 3, 1000, 2, MILLISECONDS);
        return transformAsync(f, l -> {
            final List<StrippedItemDO> inprogress = l.stream().filter(e -> "INPROGRESS".equals(e.getItem_processing_status())).collect(toList());
            final List<StrippedItemDO> doneItems = l.stream().filter(e -> !"INPROGRESS".equals(e.getItem_processing_status())).collect(toList());

            if (!inprogress.isEmpty()) {
                L.warn(format("feedId: %s, following items are still in progress, marking them timed out: " + inprogress, feedId));
            }
            if (doneItems != null) {
                doneItems.forEach(e -> {
                    final ItemStatus status = ItemStatus.valueOf(e.getItem_processing_status());
                    if (!counts.containsKey(status))
                        counts.put(status, 0);
                    counts.put(status, counts.get(status) + 1);
                });
                addCallback(updateIndexDB(doneItems), new FutureCallback<List<StrippedItemDO>>() {
                    @Override
                    public void onSuccess(List<StrippedItemDO> result) {
                        L.info(format("feedId: %s, item status updated successfully in solr: " + doneItems, feedId));
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        L.error(format("feedId: %s, failed to update item status in solr: " + doneItems, feedId), getRootCause(t));
                    }
                });
            }

            if (l.isEmpty() || l.size() < fetchSize) {
                return inprogress.isEmpty() ? DONE : tof(inprogress, feedId);
            } else
                return inprogress.isEmpty() ? calculateCounts0(feedId, shard, l.get(l.size() - 1).getId().getSkuIndex(), counts) :
                        transform(allAsList(calculateCounts0(feedId, shard, l.get(l.size() - 1).getId().getSkuIndex(), counts), tof(inprogress, feedId)),
                                new Function<List<List<StrippedItemDO>>, List<StrippedItemDO>>() {
                                    @Override
                                    public List<StrippedItemDO> apply(List<List<StrippedItemDO>> ll) {
                                        return ll.get(0);
                                    }
                                });
        });
    }

    private ListenableFuture<List<StrippedItemDO>> tof(List<StrippedItemDO> inprogress, String feedId) {
        return transform(allAsList(inprogress.stream().map(k -> {
            final ItemStatusEntity entity = entity(ItemStatusEntity.class,
                    FeedItemStatusKey.of(k.getId().getFeedId(), k.getId().getShard(), k.getId().getSku(), k.getId().getSkuIndex()));
            if (L.isDebugEnabled())
                L.debug(format("feedId: %s, marking item as timed out: %s", feedId, inprogress));
            entity.setItem_processing_status(TIMEOUT_ERROR.name());
            entity.setModified_dtm(new Date());
            return updateBothDBs(entity, feedId);
        }).collect(toList())), (Function<List<ItemStatusEntity>, List<StrippedItemDO>>) $ -> inprogress);
    }

    private ListenableFuture<ItemStatusEntity> updateBothDBs(ItemStatusEntity entity, String feedId) {
        final DataManager<FeedItemStatusKey, ItemStatusEntity> itemDM = dm("item");
        final DataManager<V2ItemStatusIndexKey, V2ItemStatusIndexEntity> itemIndexDM = dm("item_index");
        final V2ItemStatusIndexEntity v2entity = entity(V2ItemStatusIndexEntity.class,
                V2ItemStatusIndexKey.from(FeedItemStatusKey.of(entity.id().getFeedId(), entity.id().getShard(),
                        entity.id().getSku(), entity.id().getSkuIndex())));
        if (L.isDebugEnabled())
            L.debug(format("feedId: %s, syncing item status in in both DBs: %s", feedId, entity.id()));
        v2entity.setItem_processing_status(entity.getItem_processing_status());
        v2entity.setModified_dtm(new Date());
        addCallback(itemIndexDM.saveAsync(v2entity), new FutureCallback<V2ItemStatusIndexEntity>() {
            @Override
            public void onSuccess(V2ItemStatusIndexEntity result) {
                if (L.isDebugEnabled())
                    L.debug(format("feedId: %s, successfully updated status in index DB: %s", feedId, result.id()));
            }

            @Override
            public void onFailure(Throwable t) {
                L.error(format("feedId: %s, failed to update status in index DB", feedId), getRootCause(t));
            }
        });
        return itemDM.saveAsync(entity);
    }


    private ListenableFuture<List<StrippedItemDO>> updateIndexDB(List<StrippedItemDO> done) {
        return transform(allAsList(done.stream().map(k -> {
            final V2ItemStatusIndexEntity v2entity = entity(V2ItemStatusIndexEntity.class,
                    V2ItemStatusIndexKey.from(FeedItemStatusKey.of(k.getId().getFeedId(), k.getId().getShard(),
                            k.getId().getSku(), k.getId().getSkuIndex())));
            L.debug(format("feedId: %s, syncing item status in solr: %s", k.getId().getFeedId(), k));
            v2entity.setItem_processing_status(k.getItem_processing_status());
            v2entity.setModified_dtm(new Date());
            final DataManager<V2ItemStatusIndexKey, V2ItemStatusIndexEntity> itemIndexDM = dm("item_index");
            return itemIndexDM.saveAsync(v2entity);
        }).collect(toList())), (Function<List<V2ItemStatusIndexEntity>, List<StrippedItemDO>>) $ -> done);
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        final DataManager<FeedItemStatusKey, ItemStatusEntity> itemDM = dm("item");
        final CqlDAO<?, ?> feedCqlDAO = (CqlDAO<?, ?>) itemDM.getPrimaryDAO(V2).unwrap();
        itemAM = feedCqlDAO.cqlDriverConfig().getAsyncPersistenceManager();
        fetchSize = PROPS.getInteger("feed.items.fetch.size", 400);
    }
}
