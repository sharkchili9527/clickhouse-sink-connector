package com.altinity.clickhouse.sink.connector.db.batch;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.altinity.clickhouse.sink.connector.converters.ClickHouseConverter;
import com.altinity.clickhouse.sink.connector.db.DBMetadata;
import com.altinity.clickhouse.sink.connector.db.QueryFormatter;
import com.altinity.clickhouse.sink.connector.db.operations.ClickHouseAlterTable;
import com.altinity.clickhouse.sink.connector.model.CdcRecordState;
import com.altinity.clickhouse.sink.connector.model.ClickHouseStruct;
import com.clickhouse.jdbc.ClickHouseConnection;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.data.Field;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.util.*;

import static com.altinity.clickhouse.sink.connector.db.batch.CdcOperation
        .getCdcSectionBasedOnOperation;

/**
 * This class groups insert queries with their batch records.
 * <p>
 * It processes a list of ClickHouseStruct records to create a mapping
 * between an insert query template and the corresponding batch of records.
 * It also updates the topic-partition offset map with the latest offsets.
 * </p>
 */
public class GroupInsertQueryWithBatchRecords {

    private static final Logger log =
            LogManager.getLogger(GroupInsertQueryWithBatchRecords.class);

    /**
     * Re-reads the target table's column map and publishes it where the rest of
     * the batch pipeline will see it -- in practice
     * {@code DbWriter::refreshColumnNameToDataTypeMap}.
     *
     * <p>Grouping and parameter binding must agree on one column map. Refreshing
     * into a local variable here is not enough: the caller keeps its own
     * reference and uses it to bind, so a column added mid-batch would appear in
     * the generated INSERT but never get a value, and clickhouse-jdbc throws
     * NullPointerException from addBatch() on that unbound parameter. The batch
     * can then never succeed and is retried forever.
     */
    @FunctionalInterface
    public interface SchemaRefresher {
        /**
         * @return the freshly read column name to data type map.
         * @throws Exception if the metadata query fails.
         */
        Map<String, String> refresh() throws Exception;
    }

    /**
     * Groups records by their insert query template and updates the
     * topic-partition offset map.
     * <p>
     * This function processes records to build a mapping between an
     * insert query (and its associated column-to-index map) and a list of
     * records that share that query. It also updates the partition-to-offset
     * map with the highest offset per topic partition.
     * </p>
     *
     * @param records              list of ClickHouseStruct records.
     * @param queryToRecordsMap    map of query template to list of records.
     * @param partitionToOffsetMap map of TopicPartition to latest offset.
     * @param config               connector configuration.
     * @param tableName            target table name.
     * @param databaseName         target database name.
     * @param connection           JDBC connection.
     * @param columnNameToDataTypeMap map of column names to their data types.
     * @return true if grouping is successful; false otherwise.
     */
    public boolean groupQueryWithRecords(
            List<ClickHouseStruct> records,
            Map<MutablePair<String, Map<String, Integer>>,
                    List<ClickHouseStruct>> queryToRecordsMap,
            Map<TopicPartition, Long> partitionToOffsetMap,
            ClickHouseSinkConnectorConfig config,
            String tableName, String databaseName, Connection connection,
            Map<String, String> columnNameToDataTypeMap) {
        return groupQueryWithRecords(records, queryToRecordsMap,
                partitionToOffsetMap, config, tableName, databaseName,
                connection, columnNameToDataTypeMap,
                config.getString(ClickHouseSinkConnectorConfigVariables
                        .REPLACING_MERGE_TREE_DELETE_COLUMN.toString()));
    }

    public boolean groupQueryWithRecords(
            List<ClickHouseStruct> records,
            Map<MutablePair<String, Map<String, Integer>>,
                    List<ClickHouseStruct>> queryToRecordsMap,
            Map<TopicPartition, Long> partitionToOffsetMap,
            ClickHouseSinkConnectorConfig config,
            String tableName, String databaseName, Connection connection,
            Map<String, String> columnNameToDataTypeMap,
            String replacingMergeTreeDeleteColumn) {
        return groupQueryWithRecords(records, queryToRecordsMap,
                partitionToOffsetMap, config, tableName, databaseName,
                connection, columnNameToDataTypeMap,
                replacingMergeTreeDeleteColumn, null);
    }

    /**
     * Same as above, plus a {@link SchemaRefresher} so that a column map
     * re-read here is published to whatever the caller uses to bind
     * parameters. Pass {@code DbWriter::refreshColumnNameToDataTypeMap};
     * {@code null} falls back to refreshing a local copy only, which is
     * unsafe for callers that bind from their own map.
     *
     * @param schemaRefresher publishes a freshly read column map, or null.
     * @return true if grouping is successful; false otherwise.
     */
    public boolean groupQueryWithRecords(
            List<ClickHouseStruct> records,
            Map<MutablePair<String, Map<String, Integer>>,
                    List<ClickHouseStruct>> queryToRecordsMap,
            Map<TopicPartition, Long> partitionToOffsetMap,
            ClickHouseSinkConnectorConfig config,
            String tableName, String databaseName, Connection connection,
            Map<String, String> columnNameToDataTypeMap,
            String replacingMergeTreeDeleteColumn,
            SchemaRefresher schemaRefresher) {
        boolean result = false;

        // Refresh the column metadata ONCE PER BATCH, not once per record.
        // The per-record refresh this replaces cost two system.columns queries
        // for every single row -- measured 664k metadata SELECTs for 332k
        // snapshot rows, which burned 2.6 of 4 cores inside ClickHouse and made
        // the metadata lookup, not the INSERT, the snapshot bottleneck
        // (1.5M rows: 28m48s before, ~70s after).
        // It cannot be dropped entirely: it is also how the writer notices that
        // the ClickHouse table lost a column out-of-band. Without any refresh,
        // the cached map still lists the dropped column, the INSERT references
        // it, and ClickHouse answers Code 16 NO_SUCH_COLUMN_IN_TABLE, which this
        // connector treats as fatal and stops the task. Once per batch keeps
        // that self-healing at 1/max.batch.size of the cost.
        if (config.getBoolean(ClickHouseSinkConnectorConfigVariables
                .ENABLE_SCHEMA_EVOLUTION.toString())) {
            columnNameToDataTypeMap = refreshColumnMap(schemaRefresher, config,
                    tableName, connection, databaseName,
                    columnNameToDataTypeMap);
        }

        // Co4 = {ClickHouseStruct@9220} de block to create a Map of Query ->
        // list of records so that all records belonging to the same query
        // can be inserted as a batch.
        Iterator iterator = records.iterator();
        while (iterator.hasNext()) {
            ClickHouseStruct record = (ClickHouseStruct) iterator.next();
            if (record != null && record.getKafkaPartition() != null &&
                    record.getTopic() != null) {
                updatePartitionOffsetMap(partitionToOffsetMap,
                        record.getKafkaPartition(), record.getTopic(),
                        record.getKafkaOffset());
            }
            boolean enableSchemaEvolution = config.getBoolean(
                    ClickHouseSinkConnectorConfigVariables.ENABLE_SCHEMA_EVOLUTION
                            .toString());

            if (CdcRecordState.CDC_RECORD_STATE_BEFORE ==
                    getCdcSectionBasedOnOperation(record.getCdcOperation())) {
                result = updateQueryToRecordsMap(record,
                        record.getBeforeModifiedFields(), queryToRecordsMap,
                        tableName, config, columnNameToDataTypeMap,
                        replacingMergeTreeDeleteColumn);
            } else if (CdcRecordState.CDC_RECORD_STATE_AFTER ==
                    getCdcSectionBasedOnOperation(record.getCdcOperation())) {
                if (enableSchemaEvolution) {
                    try {
                        // Re-read the column metadata ONLY when alterTable
                        // actually added a column. This used to run
                        // unconditionally, once per record, and each call is
                        // two system.columns queries -- measured 664k metadata
                        // SELECTs for 332k snapshot rows, which burned 2.6 of
                        // the 4 available cores inside ClickHouse and made the
                        // metadata lookup, not the INSERT, the bottleneck
                        // (1.5M rows: 28m48s before, 69s after).
                        boolean tableAltered = new ClickHouseAlterTable().alterTable(
                                record.getAfterStruct().schema().fields(),
                                tableName, connection, columnNameToDataTypeMap, config);
                        if (tableAltered) {
                            // Must publish, not just refresh locally: the
                            // caller binds parameters from its own map, and a
                            // column added here has to be visible there too.
                            columnNameToDataTypeMap = refreshColumnMap(
                                    schemaRefresher, config, tableName,
                                    connection, databaseName,
                                    columnNameToDataTypeMap);
                        }
                    } catch (Exception e) {
                        log.error("**** ERROR ALTER TABLE: " + tableName, e);
                    }
                }
                // columnNameToDataTypeMap = new DBMetadata().getColumnsDataTypesForTable(
                // tableName, connection, databaseName, config );
                result = updateQueryToRecordsMap(record,
                        record.getAfterModifiedFields(), queryToRecordsMap,
                        tableName, config, columnNameToDataTypeMap,
                        replacingMergeTreeDeleteColumn);
            }
            // UPDATE: This creates 2 records, one with before and another one with after.
            else if (CdcRecordState.CDC_RECORD_STATE_BOTH ==
                    getCdcSectionBasedOnOperation(record.getCdcOperation())) {
                // if replication history is enabled, then dont split to 2 records.
                if (config.getBoolean(ClickHouseSinkConnectorConfigVariables.REPLICATION_HISTORY_ENABLE.toString())) {
                        result = updateQueryToRecordsMap(record,
                                record.getAfterModifiedFields(), queryToRecordsMap,
                                tableName, config, columnNameToDataTypeMap,
                                replacingMergeTreeDeleteColumn);
                        // `continue`, NOT `return`: this is inside the per-record
                        // loop. Returning here abandoned every remaining record in
                        // the batch the moment the first UPDATE was seen -- silently,
                        // with no error and no metric, while the offset still
                        // advanced past the discarded rows. A single MySQL statement
                        // touching N rows emits N records in one batch, so all but
                        // the first were lost. Only history mode took this branch,
                        // which is why the standard flow was unaffected.
                        continue;
                }
                                
                if (record.getBeforeModifiedFields() != null) {
                    result = updateQueryToRecordsMap(record,
                            record.getBeforeModifiedFields(), queryToRecordsMap,
                            tableName, config, columnNameToDataTypeMap,
                            replacingMergeTreeDeleteColumn);
                }
                if (record.getAfterModifiedFields() != null) {
                    result = updateQueryToRecordsMap(record,
                            record.getAfterModifiedFields(), queryToRecordsMap,
                            tableName, config, columnNameToDataTypeMap,
                            replacingMergeTreeDeleteColumn);
                }
            } else {
                log.error("************ RECORD DROPPED: INVALID CDC RECORD " +
                        "STATE *****************" + record.getSourceRecord());
            }
        }
        return result;
    }

    /**
     * Re-reads the column map, publishing it through {@code schemaRefresher}
     * when one was supplied so the caller's binding path sees the same map.
     *
     * @param schemaRefresher publisher, or null to refresh a local copy only.
     * @param config          connector configuration.
     * @param tableName       target table name.
     * @param connection      JDBC connection.
     * @param databaseName    target database name.
     * @param current         map to keep if the refresh fails.
     * @return the refreshed map, or {@code current} when the refresh failed.
     */
    private Map<String, String> refreshColumnMap(
            SchemaRefresher schemaRefresher,
            ClickHouseSinkConnectorConfig config, String tableName,
            Connection connection, String databaseName,
            Map<String, String> current) {
        try {
            Map<String, String> refreshed;
            if (schemaRefresher != null) {
                refreshed = schemaRefresher.refresh();
            } else {
                refreshed = new DBMetadata(config)
                        .getColumnsDataTypesForTable(tableName, connection,
                                databaseName);
            }
            // An empty result means the metadata query failed or raced with a
            // DROP; keeping the previous map is strictly better than generating
            // an INSERT with no columns.
            return (refreshed == null || refreshed.isEmpty())
                    ? current : refreshed;
        } catch (Exception e) {
            log.error("Error refreshing column metadata for " + tableName
                    + ", continuing with the cached map", e);
            return current;
        }
    }

    /**
     * Updates the mapping of query template to records.
     * <p>
     * For a given record, this function builds the insert query template
     * (using a prepared statement) and updates the mapping from that
     * template (and its column index map) to the list of records.
     * If the operation is TRUNCATE, a TRUNCATE TABLE command is added.
     * </p>
     *
     * @param record             a ClickHouseStruct record.
     * @param modifiedFields     list of modified fields.
     * @param queryToRecordsMap  map from query template to list of records.
     * @param tableName          target table name.
     * @param config             connector configuration.
     * @param columnNameToDataTypeMap map of column names to data types.
     * @return true if the mapping is updated; false otherwise.
     */
    public boolean updateQueryToRecordsMap(
            ClickHouseStruct record, List<Field> modifiedFields,
            Map<MutablePair<String, Map<String, Integer>>,
                    List<ClickHouseStruct>> queryToRecordsMap,
            String tableName, ClickHouseSinkConnectorConfig config,
            Map<String, String> columnNameToDataTypeMap) {
        return updateQueryToRecordsMap(record, modifiedFields, queryToRecordsMap,
                tableName, config, columnNameToDataTypeMap,
                config.getString(ClickHouseSinkConnectorConfigVariables
                        .REPLACING_MERGE_TREE_DELETE_COLUMN.toString()));
    }

    public boolean updateQueryToRecordsMap(
            ClickHouseStruct record, List<Field> modifiedFields,
            Map<MutablePair<String, Map<String, Integer>>,
                    List<ClickHouseStruct>> queryToRecordsMap,
            String tableName, ClickHouseSinkConnectorConfig config,
            Map<String, String> columnNameToDataTypeMap,
            String replacingMergeTreeDeleteColumn) {

        // Step 1: If its a TRUNCATE OPERATION, add a TRUNCATE TABLE command.
        if (record.getCdcOperation().getOperation()
                .equalsIgnoreCase(ClickHouseConverter.CDC_OPERATION.TRUNCATE
                        .getOperation())) {
            MutablePair<String, Map<String, Integer>> mp = new MutablePair<>();
            mp.setLeft(String.format("TRUNCATE TABLE %s", tableName));
            mp.setRight(new HashMap<String, Integer>());
            ArrayList<ClickHouseStruct> records = new ArrayList<>();
            records.add(record);
            queryToRecordsMap.put(mp, records);
            return true;
        }

        // Step 2: Create the Prepared Statement Query.
        MutablePair<String, Map<String, Integer>> response =
                new QueryFormatter().getInsertQueryUsingInputFunction(
                        tableName, modifiedFields, columnNameToDataTypeMap,
                        config.getBoolean(
                                ClickHouseSinkConnectorConfigVariables.STORE_KAFKA_METADATA
                                        .toString()),
                        config.getBoolean(
                                ClickHouseSinkConnectorConfigVariables.STORE_RAW_DATA
                                        .toString()),
                        config.getString(
                                ClickHouseSinkConnectorConfigVariables.STORE_RAW_DATA_COLUMN
                                        .toString()),
                        record.getDatabase(),
                        replacingMergeTreeDeleteColumn);

        String insertQueryTemplate = response.getKey();
        if (response.getKey() == null || response.getValue() == null) {
            log.error("********* QUERY or COLUMN TO INDEX MAP EMPTY");
            return false;
            // this.columnNametoIndexMap = response.right;
        }

        MutablePair<String, Map<String, Integer>> mp =
                new MutablePair<>();
        mp.setLeft(insertQueryTemplate);
        mp.setRight(response.getValue());

        if (!queryToRecordsMap.containsKey(mp)) {
            List<ClickHouseStruct> newList = new ArrayList<>();
            newList.add(record);
            queryToRecordsMap.put(mp, newList);
        } else {
            List<ClickHouseStruct> recordsList = queryToRecordsMap.get(mp);
            recordsList.add(record);
            queryToRecordsMap.put(mp, recordsList);
        }
        return true;
    }

    /**
     * Updates the map of TopicPartition to offset (max).
     * <p>
     * This function updates the offset map with the highest offset for a given
     * topic and partition.
     * </p>
     *
     * @param offsetToPartitionMap map from TopicPartition to offset.
     * @param partition            partition number.
     * @param topic                topic name.
     * @param offset               current record offset.
     */
    private void updatePartitionOffsetMap(
            Map<TopicPartition, Long> offsetToPartitionMap, int partition,
            String topic, long offset) {

        TopicPartition tp = new TopicPartition(topic, partition);

        // Check if record exists.
        if (!offsetToPartitionMap.containsKey(tp)) {
            // Record does not exist.
            offsetToPartitionMap.put(tp, offset);
        } else {
            // Record exists. Update only if the current offset
            // is greater than the offset stored.
            long storedOffset = offsetToPartitionMap.get(tp);
            if (offset > storedOffset) {
                offsetToPartitionMap.put(tp, offset);
            }
        }
    }
}
