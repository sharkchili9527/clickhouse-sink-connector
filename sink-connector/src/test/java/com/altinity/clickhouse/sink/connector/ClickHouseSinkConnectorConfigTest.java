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
}
