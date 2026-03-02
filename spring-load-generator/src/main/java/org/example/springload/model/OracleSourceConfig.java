package org.example.springload.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public record OracleSourceConfig(
        String version,
        String connector,
        String name,
        long sourceTimestampMs,
        String snapshot,
        String db,
        @JsonProperty("schema") String schemaName,
        String table,
        String transactionPrefix,
        String scnPrefix
) {
    @JsonCreator
    public static OracleSourceConfig fromYaml(
            @JsonProperty("version") String version,
            @JsonProperty("connector") String connector,
            @JsonProperty("name") String name,
            @JsonProperty("source_timestamp_ms") Long sourceTimestampMs,
            @JsonProperty("snapshot") String snapshot,
            @JsonProperty("db") String db,
            @JsonProperty("schema") String schemaName,
            @JsonProperty("table") String table,
            @JsonProperty("transaction_prefix") String transactionPrefix,
            @JsonProperty("scn_prefix") String scnPrefix
    ) {
        return new OracleSourceConfig(
                defaultString(version, "2.6.0.Final"),
                defaultString(connector, "oracle"),
                defaultString(name, "oracle-cdc"),
                sourceTimestampMs == null ? System.currentTimeMillis() : sourceTimestampMs,
                defaultString(snapshot, "false"),
                defaultString(db, "ORCLCDB"),
                defaultString(schemaName, "DEBEZIUM"),
                defaultString(table, "CUSTOMERS"),
                defaultString(transactionPrefix, "tx"),
                defaultString(scnPrefix, "scn")
        );
    }

    public OracleSourceConfig() {
        this(
                "2.6.0.Final",
                "oracle",
                "oracle-cdc",
                System.currentTimeMillis(),
                "false",
                "ORCLCDB",
                "DEBEZIUM",
                "CUSTOMERS",
                "tx",
                "scn"
        );
    }

    private static String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
