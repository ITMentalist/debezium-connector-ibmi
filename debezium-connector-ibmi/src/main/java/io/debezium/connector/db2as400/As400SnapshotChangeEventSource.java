/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fnz.db2.journal.retrieve.JournalPosition;

import io.debezium.connector.db2as400.As400OffsetContext.Loader;
import io.debezium.jdbc.MainConnectionProvidingConnectionFactory;
import io.debezium.pipeline.EventDispatcher;
import io.debezium.pipeline.source.spi.SnapshotProgressListener;
import io.debezium.pipeline.spi.SnapshotResult;
import io.debezium.relational.RelationalSnapshotChangeEventSource;
import io.debezium.relational.Table;
import io.debezium.relational.TableId;
import io.debezium.schema.SchemaChangeEvent;
import io.debezium.schema.SchemaChangeEvent.SchemaChangeEventType;
import io.debezium.util.Clock;

public class As400SnapshotChangeEventSource
		extends RelationalSnapshotChangeEventSource<As400Partition, As400OffsetContext> {
	private static Logger log = LoggerFactory.getLogger(As400SnapshotChangeEventSource.class);

	private final As400ConnectorConfig connectorConfig;
	private final As400JdbcConnection jdbcConnection;
	private final As400RpcConnection rpcConnection;
	private final As400DatabaseSchema schema;

	public As400SnapshotChangeEventSource(As400ConnectorConfig connectorConfig, As400RpcConnection rpcConnection,
			MainConnectionProvidingConnectionFactory<As400JdbcConnection> jdbcConnectionFactory,
			As400DatabaseSchema schema, EventDispatcher<As400Partition, TableId> dispatcher, Clock clock,
			SnapshotProgressListener<As400Partition> snapshotProgressListener) {

		super(connectorConfig, jdbcConnectionFactory, schema, dispatcher, clock, snapshotProgressListener);

		this.connectorConfig = connectorConfig;
		this.rpcConnection = rpcConnection;
		this.jdbcConnection = jdbcConnectionFactory.mainConnection();
		this.schema = schema;
	}

	@Override
	public SnapshotResult<As400OffsetContext> execute(ChangeEventSourceContext context, As400Partition partition,
			As400OffsetContext previousOffset) throws InterruptedException {
		final SnapshottingTask snapshottingTask = getSnapshottingTask(partition, previousOffset);
		if (snapshottingTask.shouldSkipSnapshot()) {
			log.info("snapshotting skipped but fetching structure");
			final RelationalSnapshotContext<As400Partition, As400OffsetContext> ctx;
			try {
				ctx = (RelationalSnapshotContext<As400Partition, As400OffsetContext>) prepare(partition);
				determineTables(ctx);
				readTableStructure(context, ctx, previousOffset);
			} catch (final Exception e) {
				throw new RuntimeException("Failed to initialize snapshot context.", e);
			}
			log.info("finished fetching structure");
		}
		return super.execute(context, partition, previousOffset);
	}

	void determineTables(RelationalSnapshotContext<As400Partition, As400OffsetContext> ctx) throws Exception {
		final Set<TableId> allTableIds = getAllTableIds(ctx);
		final Set<TableId> snapshottedTableIds = determineDataCollectionsToBeSnapshotted(allTableIds)
				.collect(Collectors.toSet());

		final Set<TableId> capturedTables = new HashSet<>();
		final Set<TableId> capturedSchemaTables = new HashSet<>();

		for (final TableId tableId : allTableIds) {
			if (connectorConfig.getTableFilters().eligibleForSchemaDataCollectionFilter().isIncluded(tableId)) {
				capturedSchemaTables.add(tableId);
			}
		}

		for (final TableId tableId : snapshottedTableIds) {
			if (connectorConfig.getTableFilters().dataCollectionFilter().isIncluded(tableId)) {
				capturedTables.add(tableId);
			}
		}

		ctx.capturedTables = capturedTables;
		ctx.capturedSchemaTables = capturedSchemaTables.stream().sorted()
				.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	@Override
	protected Set<TableId> getAllTableIds(RelationalSnapshotContext<As400Partition, As400OffsetContext> snapshotContext)
			throws Exception {
		final Set<TableId> tables = jdbcConnection.readTableNames(jdbcConnection.getRealDatabaseName(),
				connectorConfig.getSchema(), null, new String[] { "TABLE" });
		return tables;
	}

	@Override
	protected void lockTablesForSchemaSnapshot(ChangeEventSourceContext sourceContext,
			RelationalSnapshotContext<As400Partition, As400OffsetContext> snapshotContext) throws Exception {
		// TODO lock tables
	}

	@Override
	protected void determineSnapshotOffset(
			RelationalSnapshotContext<As400Partition, As400OffsetContext> snapshotContext,
			As400OffsetContext previousOffset) throws Exception {
		final JournalPosition position = rpcConnection.getCurrentPosition();
		// set last entry to processed so we don't process it again
		position.setProcessed(true);
		snapshotContext.offset = new As400OffsetContext(connectorConfig, position);
	}

	@Override
	protected void readTableStructure(ChangeEventSourceContext sourceContext,
			RelationalSnapshotContext<As400Partition, As400OffsetContext> snapshotContext,
			As400OffsetContext offsetContext) throws Exception {
		final Set<String> schemas = snapshotContext.capturedTables.stream().map(TableId::schema)
				.collect(Collectors.toSet());

		// reading info only for the schemas we're interested in as per the set of
		// captured tables;
		// while the passed table name filter alone would skip all non-included tables,
		// reading the schema
		// would take much longer that way
		for (final String schema : schemas) {
			if (!sourceContext.isRunning()) {
				throw new InterruptedException("Interrupted while reading structure of schema " + schema);
			}

			log.info("Reading structure of schema '{}'", schema);

			jdbcConnection.readSchema(snapshotContext.tables, // use snapshotContext.capturedSchemaTables?
					jdbcConnection.getRealDatabaseName(), schema,
					connectorConfig.getTableFilters().eligibleDataCollectionFilter(), null, false);

			try {
				jdbcConnection.getAllSystemNames(schema);
			} catch (final Exception e) {
				log.warn("failure fetching table names", e);
			}
		}

		for (final TableId id : snapshotContext.capturedTables) {
			final Table table = snapshotContext.tables.forTable(id);
			if (table == null) {
				log.error("table schema not found for {}", id, new Exception("missing table definition"));
			} else {
				schema.addSchema(table);
			}
		}
	}

	@Override
	protected void releaseSchemaSnapshotLocks(
			RelationalSnapshotContext<As400Partition, As400OffsetContext> snapshotContext) throws Exception {
		// TODO unlock tables
	}

	@Override
	protected SchemaChangeEvent getCreateTableEvent(
			RelationalSnapshotContext<As400Partition, As400OffsetContext> snapshotContext, Table table) {
		return SchemaChangeEvent.of(SchemaChangeEventType.CREATE, snapshotContext.partition, snapshotContext.offset,
				snapshotContext.catalogName, table.id().schema(), null, table, true);
	}

	@Override
	protected Optional<String> getSnapshotSelect(
			RelationalSnapshotContext<As400Partition, As400OffsetContext> snapshotContext, TableId tableId,
			List<String> columns) {
		return Optional.of(String.format("SELECT * FROM %s.%s", tableId.schema(), tableId.table()));
	}

	@Override
	protected SnapshottingTask getSnapshottingTask(As400Partition partition, As400OffsetContext previousOffset) {

		if (previousOffset != null && previousOffset.isSnapshotCompplete()) {
			if (previousOffset instanceof As400OffsetContext) {
				final As400OffsetContext ctx = previousOffset;
				if (!ctx.hasNewTables()) {
					return new SnapshottingTask(false, false);
				}
			}
		}
		return new SnapshottingTask(false, connectorConfig.getSnapshotMode().includeData());
	}

	@Override
	protected SnapshotContext<As400Partition, As400OffsetContext> prepare(As400Partition partition) throws Exception {
		return new RelationalSnapshotContext<>(partition, jdbcConnection.getRealDatabaseName());
	}

	@Override
	protected As400OffsetContext copyOffset(
			RelationalSnapshotContext<As400Partition, As400OffsetContext> snapshotContext) {
		return new Loader(connectorConfig).load(snapshotContext.offset.getOffset());
	}
}
