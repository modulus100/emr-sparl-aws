package org.example.springload.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class OracleSourceConfig {
    private String version = "2.6.0.Final";
    private String connector = "oracle";
    private String name = "oracle-cdc";
    private long sourceTimestampMs = System.currentTimeMillis();
    private String snapshot = "false";
    private String db = "ORCLCDB";

    @JsonProperty("schema")
    private String schemaName = "DEBEZIUM";

    private String table = "CUSTOMERS";
    private String transactionPrefix = "tx";
    private String scnPrefix = "scn";

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getConnector() {
        return connector;
    }

    public void setConnector(String connector) {
        this.connector = connector;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getSourceTimestampMs() {
        return sourceTimestampMs;
    }

    public void setSourceTimestampMs(long sourceTimestampMs) {
        this.sourceTimestampMs = sourceTimestampMs;
    }

    public String getSnapshot() {
        return snapshot;
    }

    public void setSnapshot(String snapshot) {
        this.snapshot = snapshot;
    }

    public String getDb() {
        return db;
    }

    public void setDb(String db) {
        this.db = db;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public void setSchemaName(String schemaName) {
        this.schemaName = schemaName;
    }

    public String getTable() {
        return table;
    }

    public void setTable(String table) {
        this.table = table;
    }

    public String getTransactionPrefix() {
        return transactionPrefix;
    }

    public void setTransactionPrefix(String transactionPrefix) {
        this.transactionPrefix = transactionPrefix;
    }

    public String getScnPrefix() {
        return scnPrefix;
    }

    public void setScnPrefix(String scnPrefix) {
        this.scnPrefix = scnPrefix;
    }
}
