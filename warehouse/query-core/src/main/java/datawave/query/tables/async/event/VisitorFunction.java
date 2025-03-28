package datawave.query.tables.async.event;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import javax.annotation.Nullable;

import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.data.Range;
import org.apache.commons.jexl2.parser.ASTJexlScript;
import org.apache.commons.jexl2.parser.ParseException;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.log4j.Logger;

import com.google.common.base.Function;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import datawave.core.iterators.filesystem.FileSystemCache;
import datawave.query.config.ShardQueryConfiguration;
import datawave.query.exceptions.DatawaveFatalQueryException;
import datawave.query.exceptions.InvalidQueryException;
import datawave.query.iterator.QueryOptions;
import datawave.query.jexl.JexlASTHelper;
import datawave.query.jexl.visitors.DateIndexCleanupVisitor;
import datawave.query.jexl.visitors.ExecutableDeterminationVisitor;
import datawave.query.jexl.visitors.ExecutableDeterminationVisitor.STATE;
import datawave.query.jexl.visitors.IvaratorRequiredVisitor;
import datawave.query.jexl.visitors.JexlStringBuildingVisitor;
import datawave.query.jexl.visitors.PrintingVisitor;
import datawave.query.jexl.visitors.PullupUnexecutableNodesVisitor;
import datawave.query.jexl.visitors.PushdownLargeFieldedListsVisitor;
import datawave.query.jexl.visitors.PushdownUnexecutableNodesVisitor;
import datawave.query.jexl.visitors.TermCountingVisitor;
import datawave.query.jexl.visitors.TreeEqualityVisitor;
import datawave.query.jexl.visitors.whindex.WhindexVisitor;
import datawave.query.planner.DefaultQueryPlanner;
import datawave.query.postprocessing.tf.TermOffsetPopulator;
import datawave.query.tables.SessionOptions;
import datawave.query.tables.async.ScannerChunk;
import datawave.query.util.MetadataHelper;
import datawave.util.StringUtils;
import datawave.util.time.DateHelper;
import datawave.webservice.query.Query;
import datawave.webservice.query.exception.BadRequestQueryException;
import datawave.webservice.query.exception.DatawaveErrorCode;
import datawave.webservice.query.exception.PreConditionFailedQueryException;

/**
 * Purpose: Perform intermediate transformations on ScannerChunks as they are before being sent to the tablet server.
 *
 * Justification: The benefit of using this Function is that we can perform necessary transformations in the context of a thread about to send a request to
 * tablet servers. This parallelizes requests sent to the tablet servers.
 */
public class VisitorFunction implements Function<ScannerChunk,ScannerChunk> {

    protected static FileSystemCache fileSystemCache = null;

    private ShardQueryConfiguration config;
    protected MetadataHelper metadataHelper;
    protected Set<String> indexedFields;
    protected Set<String> indexOnlyFields;
    protected Set<String> nonEventFields;

    // thread-safe cache where the key is the original query, and the value is the expanded query
    private Cache<String,String> queryCache;

    private static final Logger log = Logger.getLogger(VisitorFunction.class);

    public VisitorFunction(ShardQueryConfiguration config, MetadataHelper metadataHelper) throws MalformedURLException {
        this.config = config;

        if (VisitorFunction.fileSystemCache == null && this.config.getHdfsSiteConfigURLs() != null) {
            VisitorFunction.fileSystemCache = new FileSystemCache(this.config.getHdfsSiteConfigURLs());
        }

        this.metadataHelper = metadataHelper;

        if (config.getIndexedFields() != null && !config.getIndexedFields().isEmpty()) {
            indexedFields = config.getIndexedFields();
        } else {
            try {
                indexedFields = this.metadataHelper.getIndexedFields(config.getDatatypeFilter());
            } catch (TableNotFoundException e) {
                throw new RuntimeException(e);
            }
        }

        try {
            indexOnlyFields = this.metadataHelper.getIndexOnlyFields(config.getDatatypeFilter());
        } catch (TableNotFoundException e) {
            throw new RuntimeException(e);
        }

        try {
            nonEventFields = this.metadataHelper.getNonEventFields(config.getDatatypeFilter());
        } catch (TableNotFoundException e) {
            throw new RuntimeException(e);
        }

        // Note: By default, the concurrency (which determines the number of simultaneous writes) is set to 4
        // @formatter:off
        queryCache = CacheBuilder.newBuilder()
                .maximumWeight(config.getVisitorFunctionMaxWeight())
                .weigher((String key, String value) -> key.length() + value.length())
                .build();
        // @formatter:on
    }

    private Date getEarliestBeginDate(Collection<Range> ranges) {
        SimpleDateFormat sdf = new SimpleDateFormat(DateHelper.DATE_FORMAT_STRING_TO_DAY);
        Date minDate = null;
        try {
            for (Range range : ranges) {
                String stringDate = range.getStartKey().getRow().toString();
                if (stringDate.length() >= DateHelper.DATE_FORMAT_STRING_TO_DAY.length()) {
                    stringDate = stringDate.substring(0, DateHelper.DATE_FORMAT_STRING_TO_DAY.length());
                    Date date = sdf.parse(stringDate);
                    if (minDate == null || date.compareTo(minDate) < 0) {
                        minDate = date;
                    }
                }
            }
        } catch (Exception e) {
            throw new DatawaveFatalQueryException(e);
        }
        return minDate;
    }

    @Override
    @Nullable
    public ScannerChunk apply(@Nullable ScannerChunk input) {

        SessionOptions options = input.getOptions();

        ScannerChunk newSettings = new ScannerChunk(null, input.getRanges(), input.getLastKnownLocation());

        SessionOptions newOptions = new SessionOptions(options);

        for (IteratorSetting setting : options.getIterators()) {

            final String query = setting.getOptions().get(QueryOptions.QUERY);
            if (null != query) {
                IteratorSetting newIteratorSetting = new IteratorSetting(setting.getPriority(), setting.getName(), setting.getIteratorClass());

                newIteratorSetting.addOptions(setting.getOptions());
                try {

                    ASTJexlScript script = null;

                    boolean evaluatedPreviously = true;
                    String newQuery = queryCache.getIfPresent(query);
                    if (newQuery == null) {
                        evaluatedPreviously = false;
                        newQuery = query;
                    }

                    boolean madeChange = false;

                    if (!evaluatedPreviously && config.isCleanupShardsAndDaysQueryHints()) {
                        script = JexlASTHelper.parseAndFlattenJexlQuery(query);
                        script = DateIndexCleanupVisitor.cleanup(script);
                        madeChange = true;
                    }

                    LinkedList<String> debug = null;
                    if (log.isTraceEnabled()) {
                        debug = new LinkedList<>();
                    }

                    if (!config.isDisableWhindexFieldMappings() && !evaluatedPreviously) {
                        if (null == script) {
                            script = JexlASTHelper.parseAndFlattenJexlQuery(query);
                        }

                        // apply the whindex using the shard date
                        ASTJexlScript rebuiltScript = WhindexVisitor.apply(script, config, getEarliestBeginDate(newSettings.getRanges()), metadataHelper);

                        // if the query changed, save it, and mark it as such
                        if (!TreeEqualityVisitor.isEqual(script, rebuiltScript)) {
                            log.debug("[" + config.getQuery().getId() + "] The WhindexVisitor updated the query: "
                                            + JexlStringBuildingVisitor.buildQuery(script));
                            script = rebuiltScript;
                            madeChange = true;
                        }
                    }

                    if (!config.isBypassExecutabilityCheck() || !evaluatedPreviously) {
                        // if the script is not set, recreate it using newQuery
                        // if evaluatedPreviously is true, newQuery will be set to the new, expanded query
                        // if evaluatedPreviously is false, newQuery will be set to the original query
                        if (null == script) {
                            script = JexlASTHelper.parseAndFlattenJexlQuery(newQuery);
                        }

                        if (!ExecutableDeterminationVisitor.isExecutable(script, config, indexedFields, indexOnlyFields, nonEventFields, true, debug,
                                        this.metadataHelper)) {

                            if (log.isTraceEnabled()) {
                                log.trace("Need to pull up non-executable query: " + JexlStringBuildingVisitor.buildQuery(script));
                                for (String debugStatement : debug) {
                                    log.trace(debugStatement);
                                }
                                DefaultQueryPlanner.logQuery(script, "Failing query:");
                            }
                            script = (ASTJexlScript) PullupUnexecutableNodesVisitor.pullupDelayedPredicates(script, true, config, indexedFields,
                                            indexOnlyFields, nonEventFields, metadataHelper);
                            madeChange = true;

                            STATE state = ExecutableDeterminationVisitor.getState(script, config, indexedFields, indexOnlyFields, nonEventFields, true, debug,
                                            metadataHelper);

                            /**
                             * We could achieve better performance if we live with the small number of queries that error due to the full table scan exception.
                             *
                             * Either look at improving PushdownUnexecutableNodesVisitor or avoid the process altogether.
                             */
                            if (state != STATE.EXECUTABLE) {
                                if (log.isTraceEnabled()) {
                                    log.trace("Need to push down non-executable query: " + JexlStringBuildingVisitor.buildQuery(script));
                                    for (String debugStatement : debug) {
                                        log.trace(debugStatement);
                                    }
                                }
                                script = (ASTJexlScript) PushdownUnexecutableNodesVisitor.pushdownPredicates(script, true, config, indexedFields,
                                                indexOnlyFields, nonEventFields, metadataHelper);
                            }

                            state = ExecutableDeterminationVisitor.getState(script, config, indexedFields, indexOnlyFields, nonEventFields, true, debug,
                                            metadataHelper);

                            if (state != STATE.EXECUTABLE) {
                                if (state == STATE.ERROR) {
                                    log.warn("After expanding the query, it is determined that the query cannot be executed due to index-only fields mixed with expressions that cannot be run against the index.");
                                    BadRequestQueryException qe = new BadRequestQueryException(DatawaveErrorCode.INDEX_ONLY_FIELDS_MIXED_INVALID_EXPRESSIONS);
                                    throw new InvalidQueryException(qe);
                                }
                                log.warn("After expanding the query, it is determined that the query cannot be executed against the field index and a full table scan is required");
                                if (!config.getFullTableScanEnabled()) {

                                    if (log.isTraceEnabled()) {
                                        log.trace("Full Table fail of " + JexlStringBuildingVisitor.buildQuery(script));
                                        for (String debugStatement : debug) {
                                            log.trace(debugStatement);
                                        }
                                        DefaultQueryPlanner.logQuery(script, "Failing query:");
                                    }
                                    PreConditionFailedQueryException qe = new PreConditionFailedQueryException(
                                                    DatawaveErrorCode.FULL_TABLE_SCAN_REQUIRED_BUT_DISABLED);
                                    throw new DatawaveFatalQueryException(qe);
                                }
                            }

                            if (log.isTraceEnabled()) {
                                for (String debugStatement : debug) {
                                    log.trace(debugStatement);
                                }
                                DefaultQueryPlanner.logQuery(script, "Query pushing down large fielded lists:");
                            }
                        }
                    }

                    if (config.getSerializeQueryIterator()) {
                        serializeQuery(newIteratorSetting);
                    } else {
                        if (!evaluatedPreviously && config.getHdfsSiteConfigURLs() != null) {
                            // if we have an hdfs configuration, then we can pushdown large fielded lists to an ivarator
                            if (null == script) {
                                script = JexlASTHelper.parseAndFlattenJexlQuery(query);
                            }
                            try {
                                script = pushdownLargeFieldedLists(config, script);
                                madeChange = true;
                            } catch (IOException ioe) {
                                log.error("Unable to pushdown large fielded lists....leaving in expanded form", ioe);
                            }
                        }
                    }

                    // only recompile the script if changes were made to the query
                    if (madeChange) {
                        newQuery = JexlStringBuildingVisitor.buildQuery(script);
                    }

                    pruneIvaratorConfigs(script, newIteratorSetting);

                    pruneEmptyOptions(newIteratorSetting);

                    if (config.getReduceQueryFields()) {
                        reduceQueryFields(script, newIteratorSetting);
                    }

                    if (config.getPruneQueryOptions()) {
                        pruneQueryOptions(script, newIteratorSetting);
                    }

                    try {
                        queryCache.put(query, newQuery);
                    } catch (NullPointerException npe) {
                        throw new DatawaveFatalQueryException(String.format("New query is null! madeChange: %b, qid: %s", madeChange,
                                        setting.getOptions().get(QueryOptions.QUERY_ID)), npe);
                    }

                    // test the final script for thresholds
                    DefaultQueryPlanner.validateQuerySize("VisitorFunction", script, config.getMaxDepthThreshold(), config.getFinalMaxTermThreshold());

                    newIteratorSetting.addOption(QueryOptions.QUERY, newQuery);
                    newOptions.removeScanIterator(setting.getName());
                    newOptions.addScanIterator(newIteratorSetting);

                    if (log.isDebugEnabled()) {
                        log.debug("VisitorFunction result: " + newSettings.getRanges());
                    }

                    if (log.isTraceEnabled()) {
                        DefaultQueryPlanner.logTrace(PrintingVisitor.formattedQueryStringList(script), "VistorFunction::apply method");
                    } else if (log.isDebugEnabled()) {
                        DefaultQueryPlanner.logDebug(PrintingVisitor.formattedQueryStringList(script, DefaultQueryPlanner.maxChildNodesToPrint),
                                        "VistorFunction::apply method");
                    }

                } catch (ParseException e) {
                    throw new DatawaveFatalQueryException(e);
                }
            }

        }

        newSettings.setOptions(newOptions);
        return newSettings;
    }

    /**
     * Serializes the query iterator
     *
     * @param newIteratorSetting
     *            an instance of {@link IteratorSetting}
     */
    private void serializeQuery(IteratorSetting newIteratorSetting) {
        newIteratorSetting.addOption(QueryOptions.SERIAL_EVALUATION_PIPELINE, "true");
        newIteratorSetting.addOption(QueryOptions.MAX_EVALUATION_PIPELINES, "1");
        newIteratorSetting.addOption(QueryOptions.MAX_PIPELINE_CACHED_RESULTS, "1");
    }

    /**
     * Prune empty options from the iterator settings
     *
     * @param settings
     *            an instance of {@link IteratorSetting}
     */
    protected void pruneEmptyOptions(IteratorSetting settings) {
        Set<String> optionsToRemove = new HashSet<>();
        for (Map.Entry<String,String> entry : settings.getOptions().entrySet()) {
            switch (entry.getKey()) {
                case QueryOptions.INDEX_ONLY_FIELDS:
                case QueryOptions.INDEXED_FIELDS:
                    // these options won't be blank in production, but will be blank for some unit tests.
                    // leave this switch statement in until unit tests can be fixed to more accurately
                    // represent a prod-like environment.
                    continue;
                default:
                    if (org.apache.commons.lang3.StringUtils.isBlank(entry.getValue())) {
                        optionsToRemove.add(entry.getKey());
                    }
            }
        }

        for (String option : optionsToRemove) {
            settings.removeOption(option);
        }
    }

    /**
     * If the query does not require an Ivarator, remove Ivarator options from the query settings
     *
     * @param script
     *            the query script
     * @param settings
     *            an {@link IteratorSetting}
     */
    protected void pruneIvaratorConfigs(ASTJexlScript script, IteratorSetting settings) {
        if (script != null && !IvaratorRequiredVisitor.isIvaratorRequired(script)) {
            settings.removeOption(QueryOptions.IVARATOR_CACHE_BUFFER_SIZE);
            settings.removeOption(QueryOptions.IVARATOR_CACHE_DIR_CONFIG);
            settings.removeOption(QueryOptions.IVARATOR_NUM_RETRIES);
            settings.removeOption(QueryOptions.IVARATOR_PERSIST_VERIFY);
            settings.removeOption(QueryOptions.IVARATOR_PERSIST_VERIFY_COUNT);
            settings.removeOption(QueryOptions.IVARATOR_SCAN_PERSIST_THRESHOLD);
            settings.removeOption(QueryOptions.IVARATOR_SCAN_TIMEOUT);

            settings.removeOption(QueryOptions.MAX_IVARATOR_OPEN_FILES);
            settings.removeOption(QueryOptions.MAX_IVARATOR_RESULTS);
            settings.removeOption(QueryOptions.MAX_IVARATOR_SOURCES);
        }
    }

    /**
     * Reduce the serialized query fields via intersection with fields in the reduced script
     *
     * @param script
     *            the query, potentially reduced via RangeStream pruning
     * @param settings
     *            the iterator settings
     */
    protected void reduceQueryFields(ASTJexlScript script, IteratorSetting settings) {
        Set<String> queryFields = ReduceFields.getQueryFields(script);

        ReduceFields.reduceFieldsForOption(QueryOptions.CONTENT_EXPANSION_FIELDS, queryFields, settings);
        ReduceFields.reduceFieldsForOption(QueryOptions.INDEXED_FIELDS, queryFields, settings);
        ReduceFields.reduceFieldsForOption(QueryOptions.INDEX_ONLY_FIELDS, queryFields, settings);
        ReduceFields.reduceFieldsForOption(QueryOptions.TERM_FREQUENCY_FIELDS, queryFields, settings);

        // might also look at COMPOSITE_FIELDS, EXCERPT_FIELDS, and GROUP_FIELDS
    }

    /**
     * Certain query options may be pruned on a per-tablet basis
     *
     * @param script
     *            the query
     * @param settings
     *            the iterator settings
     */
    protected void pruneQueryOptions(ASTJexlScript script, IteratorSetting settings) {

        if (config.isTermFrequenciesRequired() && TermOffsetPopulator.getContentFunctions(script).isEmpty()) {
            settings.removeOption(QueryOptions.EXCERPT_FIELDS);
            settings.removeOption(QueryOptions.EXCERPT_ITERATOR);
        }
    }

    // push down large fielded lists. Assumes that the hdfs query cache uri and
    // site config urls are configured
    protected ASTJexlScript pushdownLargeFieldedLists(ShardQueryConfiguration config, ASTJexlScript queryTree) throws IOException {
        Query settings = config.getQuery();

        if (config.canHandleExceededValueThreshold()) {
            URI hdfsQueryCacheUri = getFstHdfsQueryCacheUri(config, settings);

            Map<String,Integer> pushdownCapacity = new HashMap<>();
            ASTJexlScript script;
            if (hdfsQueryCacheUri != null) {
                FileSystem fs = VisitorFunction.fileSystemCache.getFileSystem(hdfsQueryCacheUri);
                // Find large lists of values against the same field and push down into an Ivarator
                script = PushdownLargeFieldedListsVisitor.pushdown(config, queryTree, fs, hdfsQueryCacheUri.toString(), pushdownCapacity);
            } else {
                script = PushdownLargeFieldedListsVisitor.pushdown(config, queryTree, null, null, pushdownCapacity);
            }

            // check term limits and use the capacity map to reduce further if necessary
            int termCount = TermCountingVisitor.countTerms(script);
            if (termCount > config.getFinalMaxTermThreshold()) {
                // check if the capacity is available to get under the term limit
                // determine if its possible to reduce enough to meet the threshold
                int capacitySum = 0;
                for (Integer capacity : pushdownCapacity.values()) {
                    capacitySum += capacity;
                }

                if (termCount - capacitySum <= config.getFinalMaxTermThreshold()) {
                    // preserve the original config and set minimum thresholds for creating Value and Range ivarators
                    int originalMaxOrExpansionThreshold = config.getMaxOrExpansionThreshold();
                    int originalMaxOrRangeThreshold = config.getMaxOrRangeThreshold();

                    config.setMaxOrExpansionThreshold(2);
                    config.setMaxOrRangeThreshold(2);

                    try {
                        // invert pushdownCapacity to get the largest payoffs first
                        SortedMap<Integer,List<String>> sortedMap = new TreeMap<>();
                        for (String fieldName : pushdownCapacity.keySet()) {
                            Integer reduction = pushdownCapacity.get(fieldName);
                            List<String> fields = sortedMap.computeIfAbsent(reduction, k -> new ArrayList<>());
                            fields.add(fieldName);
                        }

                        // sort from largest to smallest reductions and make reductions until under the threshold
                        Set<String> fieldsToReduce = new HashSet<>();
                        int toReduce = termCount - config.getFinalMaxTermThreshold();
                        while (toReduce > 0) {
                            // get the highest value field out of the map
                            Integer reduction = sortedMap.lastKey();
                            List<String> fields = sortedMap.get(reduction);

                            // take the first field
                            String field = fields.remove(0);
                            fieldsToReduce.add(field);
                            toReduce -= reduction;

                            // if there are no more reductions of this size remove the reduction from pushdown capacity
                            if (fields.size() == 0) {
                                sortedMap.remove(reduction);
                            }
                        }

                        // execute the reduction
                        if (hdfsQueryCacheUri != null) {
                            FileSystem fs = VisitorFunction.fileSystemCache.getFileSystem(hdfsQueryCacheUri);
                            // Find large lists of values against the same field and push down into an Ivarator
                            script = PushdownLargeFieldedListsVisitor.pushdown(config, script, fs, hdfsQueryCacheUri.toString(), null, fieldsToReduce);
                        } else {
                            script = PushdownLargeFieldedListsVisitor.pushdown(config, script, null, null, null, fieldsToReduce);
                        }
                    } finally {
                        // reset config thresholds
                        config.setMaxOrExpansionThreshold(originalMaxOrExpansionThreshold);
                        config.setMaxOrRangeThreshold(originalMaxOrRangeThreshold);
                    }
                }
            }

            return script;
        } else {
            return queryTree;
        }
    }

    protected URI getFstHdfsQueryCacheUri(ShardQueryConfiguration config, Query settings) {
        if (config.getIvaratorFstHdfsBaseURIs() != null) {
            String[] choices = StringUtils.split(config.getIvaratorFstHdfsBaseURIs(), ',');
            int index = new Random().nextInt(choices.length);
            Path path = new Path(choices[index], settings.getId().toString());
            return path.toUri();
        }
        return null;
    }
}
