/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.debezium.connector.postgresql;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.fest.assertions.Assertions;
import org.fest.assertions.MapAssert;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import io.debezium.config.Configuration;
import io.debezium.connector.postgresql.PostgresConnectorConfig.SnapshotMode;
import io.debezium.connector.postgresql.connection.PostgresConnection;
import io.debezium.embedded.AbstractConnectorTest;
import io.debezium.util.Testing;

public class IncrementalSnapshotIT extends AbstractConnectorTest {

    private static final int ROW_COUNT = 1_000;
    private static final String TOPIC_NAME = "test_server.s1.a";
    private static final int MAXIMUM_NO_RECORDS_CONSUMES = 3;

    private static final String SETUP_TABLES_STMT = "DROP SCHEMA IF EXISTS s1 CASCADE;" +
            "CREATE SCHEMA s1; " +
            "CREATE SCHEMA s2; " +
            "CREATE TABLE s1.a (pk SERIAL, aa integer, PRIMARY KEY(pk));" +
            "CREATE TABLE s1.debezium_signal (id varchar(64), type varchar(32), data varchar(2048));";

    @Before
    public void before() throws SQLException {
        TestHelper.dropAllSchemas();
        initializeConnectorTestFramework();
    }

    @After
    public void after() {
        stopConnector();
        TestHelper.dropDefaultReplicationSlot();
        TestHelper.dropPublication();
    }

    private void populateTable() throws SQLException {
        try (final PostgresConnection pgConnection = TestHelper.create()) {
            pgConnection.setAutoCommit(false);
            for (int i = 0; i < ROW_COUNT; i++) {
                pgConnection.executeWithoutCommitting(String.format("INSERT INTO s1.a (aa) VALUES (%s)", i));
            }
            pgConnection.commit();
        }
    }

    protected Map<Integer, Integer> consumeMixedWithIncrementalSnapshot(int recordCount) throws InterruptedException {
        final Map<Integer, Integer> dbChanges = new HashMap<>();
        int noRecords = 0;
        for (;;) {
            final SourceRecords records = consumeRecordsByTopic(1);
            final List<SourceRecord> dataRecords = records.recordsForTopic(TOPIC_NAME);
            if (dataRecords == null || dataRecords.isEmpty()) {
                noRecords++;
                Assertions.assertThat(noRecords)
                        .describedAs("Too many no data record results")
                        .isLessThan(MAXIMUM_NO_RECORDS_CONSUMES);
                continue;
            }
            noRecords = 0;
            dataRecords.forEach(record -> {
                final int id = ((Struct) record.key()).getInt32("pk");
                final int value = ((Struct) record.value()).getStruct("after").getInt32("aa");
                dbChanges.put(id, value);
            });
            if (dbChanges.size() >= recordCount) {
                break;
            }
        }

        Assertions.assertThat(dbChanges).hasSize(recordCount);
        return dbChanges;
    }

    protected Map<Integer, Integer> consumeMixedWithIncrementalSnapshotVariableCount(int recordCount) throws InterruptedException {
        final Map<Integer, Integer> dbChanges = new HashMap<>();
        int noRecords = 0;
        for (;;) {
            final SourceRecords records = consumeRecordsByTopic(1);
            final List<SourceRecord> dataRecords = records.recordsForTopic(TOPIC_NAME);
            if (dataRecords == null || dataRecords.isEmpty()) {
                noRecords++;
                if (noRecords > MAXIMUM_NO_RECORDS_CONSUMES) {
                    break;
                }
                continue;
            }
            noRecords = 0;
            dataRecords.forEach(record -> {
                final int id = ((Struct) record.key()).getInt32("pk");
                final int value = ((Struct) record.value()).getStruct("after").getInt32("aa");
                dbChanges.put(id, value);
            });
            if (dbChanges.size() > recordCount) {
                break;
            }
        }

        Assertions.assertThat(dbChanges).hasSize(recordCount);
        return dbChanges;
    }

    @Test
    public void snapshotOnly() throws Exception {
        Testing.Print.enable();

        TestHelper.dropDefaultReplicationSlot();
        TestHelper.execute(SETUP_TABLES_STMT);
        populateTable();
        Configuration config = TestHelper.defaultConfig()
                .with(PostgresConnectorConfig.SNAPSHOT_MODE, SnapshotMode.NEVER.getValue())
                .with(PostgresConnectorConfig.DROP_SLOT_ON_STOP, Boolean.TRUE)
                .with(PostgresConnectorConfig.SIGNAL_DATA_COLLECTION, "s1.debezium_signal")
                .with(PostgresConnectorConfig.INCREMENTAL_SNAPSHOT_CHUNK_SIZE, 10)
                .build();
        start(PostgresConnector.class, config);
        assertConnectorIsRunning();
        TestHelper.waitForDefaultReplicationSlotBeActive();

        waitForAvailableRecords(100, TimeUnit.MILLISECONDS);
        // there shouldn't be any snapshot records
        assertNoRecordsToConsume();

        // Insert the signal record
        TestHelper.execute("INSERT INTO s1.debezium_signal VALUES('ad-hoc', 'execute-snapshot', '{\"data-collections\": [\"s1.a\"]}')");

        final int expectedRecordCount = ROW_COUNT;
        final Map<Integer, Integer> dbChanges = consumeMixedWithIncrementalSnapshot(expectedRecordCount);
        for (int i = 0; i < expectedRecordCount; i++) {
            Assertions.assertThat(dbChanges).includes(MapAssert.entry(i + 1, i));
        }
    }

    @Test
    public void inserts() throws Exception {
        Testing.Print.enable();

        TestHelper.dropDefaultReplicationSlot();
        TestHelper.execute(SETUP_TABLES_STMT);
        populateTable();
        Configuration config = TestHelper.defaultConfig()
                .with(PostgresConnectorConfig.SNAPSHOT_MODE, SnapshotMode.NEVER.getValue())
                .with(PostgresConnectorConfig.DROP_SLOT_ON_STOP, Boolean.TRUE)
                .with(PostgresConnectorConfig.SIGNAL_DATA_COLLECTION, "s1.debezium_signal")
                .with(PostgresConnectorConfig.INCREMENTAL_SNAPSHOT_CHUNK_SIZE, 10)
                .build();
        start(PostgresConnector.class, config);
        assertConnectorIsRunning();
        TestHelper.waitForDefaultReplicationSlotBeActive();

        waitForAvailableRecords(100, TimeUnit.MILLISECONDS);
        // there shouldn't be any snapshot records
        assertNoRecordsToConsume();

        // Insert the signal record
        TestHelper.execute("INSERT INTO s1.debezium_signal VALUES('ad-hoc', 'execute-snapshot', '{\"data-collections\": [\"s1.a\"]}')");

        try (final PostgresConnection pgConnection = TestHelper.create()) {
            for (int i = 0; i < ROW_COUNT; i++) {
                pgConnection.executeWithoutCommitting(String.format("INSERT INTO s1.a (aa) VALUES (%s)", i + ROW_COUNT));
            }
        }

        final int expectedRecordCount = ROW_COUNT * 2;
        final Map<Integer, Integer> dbChanges = consumeMixedWithIncrementalSnapshot(expectedRecordCount);
        for (int i = 0; i < expectedRecordCount; i++) {
            Assertions.assertThat(dbChanges).includes(MapAssert.entry(i + 1, i));
        }
    }

    @Test
    public void updates() throws Exception {
        Testing.Print.enable();

        TestHelper.dropDefaultReplicationSlot();
        TestHelper.execute(SETUP_TABLES_STMT);
        populateTable();
        Configuration config = TestHelper.defaultConfig()
                .with(PostgresConnectorConfig.SNAPSHOT_MODE, SnapshotMode.NEVER.getValue())
                .with(PostgresConnectorConfig.DROP_SLOT_ON_STOP, Boolean.TRUE)
                .with(PostgresConnectorConfig.SIGNAL_DATA_COLLECTION, "s1.debezium_signal")
                .with(PostgresConnectorConfig.INCREMENTAL_SNAPSHOT_CHUNK_SIZE, 10)
                .build();
        start(PostgresConnector.class, config);
        assertConnectorIsRunning();
        TestHelper.waitForDefaultReplicationSlotBeActive();

        waitForAvailableRecords(100, TimeUnit.MILLISECONDS);
        // there shouldn't be any snapshot records
        assertNoRecordsToConsume();

        // Insert the signal record
        TestHelper.execute("INSERT INTO s1.debezium_signal VALUES('ad-hoc', 'execute-snapshot', '{\"data-collections\": [\"s1.a\"]}')");

        final int batchSize = 10;
        try (final PostgresConnection pgConnection = TestHelper.create()) {
            for (int i = 0; i < ROW_COUNT / batchSize; i++) {
                TestHelper.execute(String.format("UPDATE s1.a SET aa = aa + 1000 WHERE pk > %s AND pk <= %s", i * batchSize, (i + 1) * batchSize));
            }
        }

        final int expectedRecordCount = ROW_COUNT;
        final Map<Integer, Integer> dbChanges = consumeMixedWithIncrementalSnapshot(expectedRecordCount);
        for (int i = 0; i < expectedRecordCount; i++) {
            Assertions.assertThat(dbChanges).includes(MapAssert.entry(i + 1, i + 1000));
        }
    }

    @Test
    public void updatesLargeChunk() throws Exception {
        Testing.Print.enable();

        TestHelper.dropDefaultReplicationSlot();
        TestHelper.execute(SETUP_TABLES_STMT);
        populateTable();
        Configuration config = TestHelper.defaultConfig()
                .with(PostgresConnectorConfig.SNAPSHOT_MODE, SnapshotMode.NEVER.getValue())
                .with(PostgresConnectorConfig.DROP_SLOT_ON_STOP, Boolean.TRUE)
                .with(PostgresConnectorConfig.SIGNAL_DATA_COLLECTION, "s1.debezium_signal")
                .with(PostgresConnectorConfig.INCREMENTAL_SNAPSHOT_CHUNK_SIZE, ROW_COUNT)
                .build();
        start(PostgresConnector.class, config);
        assertConnectorIsRunning();
        TestHelper.waitForDefaultReplicationSlotBeActive();

        waitForAvailableRecords(100, TimeUnit.MILLISECONDS);
        // there shouldn't be any snapshot records
        assertNoRecordsToConsume();

        // Insert the signal record
        TestHelper.execute("INSERT INTO s1.debezium_signal VALUES('ad-hoc', 'execute-snapshot', '{\"data-collections\": [\"s1.a\"]}')");

        final int batchSize = 10;
        try (final PostgresConnection pgConnection = TestHelper.create()) {
            for (int i = 0; i < ROW_COUNT / batchSize; i++) {
                TestHelper.execute(String.format("UPDATE s1.a SET aa = aa + 1000 WHERE pk > %s AND pk <= %s", i * batchSize, (i + 1) * batchSize));
            }
        }

        final int expectedRecordCount = ROW_COUNT;
        final Map<Integer, Integer> dbChanges = consumeMixedWithIncrementalSnapshotVariableCount(expectedRecordCount);
        for (int i = 0; i < expectedRecordCount; i++) {
            Assertions.assertThat(dbChanges).includes(MapAssert.entry(i + 1, i + 1000));
        }
    }
}