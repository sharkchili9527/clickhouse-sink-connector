package com.altinity.clickhouse.sink.connector;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClickHouseSinkConnectorConfigTest {

    @Test
    void legacyClickHouseDefaultsToSignColumn() {
        assertEquals("_sign",
                ClickHouseSinkConnectorConfig.resolveReplacingMergeTreeDeleteColumn(
                        null, false));
        assertEquals("_sign",
                ClickHouseSinkConnectorConfig.resolveReplacingMergeTreeDeleteColumn(
                        "", false));
    }

    @Test
    void currentClickHouseDefaultsToNativeDeleteColumn() {
        assertEquals("is_deleted",
                ClickHouseSinkConnectorConfig.resolveReplacingMergeTreeDeleteColumn(
                        null, true));
    }

    @Test
    void explicitDeleteColumnOverridesVersionDefault() {
        assertEquals("deleted_flag",
                ClickHouseSinkConnectorConfig.resolveReplacingMergeTreeDeleteColumn(
                        "  deleted_flag  ", false));
        assertEquals("deleted_flag",
                ClickHouseSinkConnectorConfig.resolveReplacingMergeTreeDeleteColumn(
                        "  deleted_flag  ", true));
    }

    @Test
    void markerResolutionPrefixesSourceColumnCollisions() {
        assertEquals("__sign",
                ClickHouseSinkConnectorConfig.resolveReplacingMergeTreeDeleteColumn(
                        null, false, List.of("`_sign`")));
        assertEquals("___sign",
                ClickHouseSinkConnectorConfig.resolveReplacingMergeTreeDeleteColumn(
                        null, false, List.of("_sign", "__sign")));
        assertEquals("_deleted_flag",
                ClickHouseSinkConnectorConfig.resolveReplacingMergeTreeDeleteColumn(
                        "deleted_flag", true, List.of("deleted_flag")));
    }

    @Test
    void configInstanceUsesTheSameVersionAwareResolution() {
        Map<String, String> properties = new HashMap<>();
        ClickHouseSinkConnectorConfig config =
                new ClickHouseSinkConnectorConfig(properties);

        assertEquals("_sign", config.getReplacingMergeTreeDeleteColumn(false));
        assertEquals("is_deleted", config.getReplacingMergeTreeDeleteColumn(true));
    }

    @Test
    void errorTableDefaultIsATableNameNotTheConfigKey() {
        Map<String, String> properties = new HashMap<>();
        ClickHouseSinkConnectorConfig config =
                new ClickHouseSinkConnectorConfig(properties);

        // The default used to be the config KEY ("default.error.table"), which
        // ErrorLogger turned into the three-part, invalid
        // "system.default.error.table" -- so every attempt to record a failed
        // DDL failed as well. A default with a dot in it can never be a valid
        // single table identifier here.
        String errorTable = config.getString(
                ClickHouseSinkConnectorConfigVariables.ERROR_TABLE_NAME.toString());
        assertEquals("error_table", errorTable);
    }

    @Test
    void errorTableNameCanStillBeOverridden() {
        Map<String, String> properties = new HashMap<>();
        properties.put(
                ClickHouseSinkConnectorConfigVariables.ERROR_TABLE_NAME.toString(),
                "my_errors");
        ClickHouseSinkConnectorConfig config =
                new ClickHouseSinkConnectorConfig(properties);

        assertEquals("my_errors", config.getString(
                ClickHouseSinkConnectorConfigVariables.ERROR_TABLE_NAME.toString()));
    }
}
