/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fnz.db2.journal.retrieve.FileFilter;

import io.debezium.config.Configuration;
import io.debezium.config.Field;
import io.debezium.connector.base.ChangeEventQueue;
import io.debezium.connector.common.BaseSourceTask;
import io.debezium.connector.common.CdcSourceTaskContext;
import io.debezium.connector.db2as400.metrics.As400ChangeEventSourceMetricsFactory;
import io.debezium.connector.db2as400.metrics.As400StreamingChangeEventSourceMetrics;
import io.debezium.jdbc.DefaultMainConnectionProvidingConnectionFactory;
import io.debezium.jdbc.JdbcConfiguration;
import io.debezium.jdbc.MainConnectionProvidingConnectionFactory;
import io.debezium.pipeline.ChangeEventSourceCoordinator;
import io.debezium.pipeline.DataChangeEvent;
import io.debezium.pipeline.ErrorHandler;
import io.debezium.pipeline.EventDispatcher;
import io.debezium.pipeline.spi.Offsets;
import io.debezium.relational.TableId;
import io.debezium.schema.SchemaNameAdjuster;
import io.debezium.spi.topic.TopicNamingStrategy;
import io.debezium.util.Clock;

public class As400ConnectorTask extends BaseSourceTask<As400Partition, As400OffsetContext> {
	private static final Logger log = LoggerFactory.getLogger(As400ConnectorTask.class);
	private volatile ChangeEventQueue<DataChangeEvent> queue;
	private static final String CONTEXT_NAME = "db2as400-server-connector-task";
	private As400DatabaseSchema schema;

	@Override
	public String version() {
		return Module.version();
	}

	@Override
	protected ChangeEventSourceCoordinator<As400Partition, As400OffsetContext> start(Configuration config) {
		log.warn("starting connector task {}", version());
		config = addDefaultHeartbeatToConfig(config);
		config = addDefaultPrefixToConfig(config);
		final As400ConnectorConfig connectorConfig = new As400ConnectorConfig(config);
		@SuppressWarnings("unchecked")
		final TopicNamingStrategy<TableId> topicNamingStrategy = connectorConfig
				.getTopicNamingStrategy(As400ConnectorConfig.TOPIC_NAMING_STRATEGY, true);

		final SchemaNameAdjuster schemaNameAdjuster = SchemaNameAdjuster.AVRO;

		final MainConnectionProvidingConnectionFactory<As400JdbcConnection> jdbcConnectionFactory = new DefaultMainConnectionProvidingConnectionFactory<>(
				() -> new As400JdbcConnection(connectorConfig.getJdbcConfiguration()));
		final As400JdbcConnection jdbcConnection = jdbcConnectionFactory.mainConnection();

		this.schema = new As400DatabaseSchema(connectorConfig, jdbcConnection, topicNamingStrategy, schemaNameAdjuster);

		final CdcSourceTaskContext ctx = new CdcSourceTaskContext(connectorConfig.getContextName(),
				connectorConfig.getLogicalName(), schema::tableIds);

		// Set up the task record queue ...
		this.queue = new ChangeEventQueue.Builder<DataChangeEvent>().pollInterval(connectorConfig.getPollInterval())
				.maxBatchSize(connectorConfig.getMaxBatchSize()).maxQueueSize(connectorConfig.getMaxQueueSize())
				.loggingContextSupplier(() -> ctx.configureLoggingContext(CONTEXT_NAME)).build();

		final ErrorHandler errorHandler = new ErrorHandler(As400RpcConnector.class, connectorConfig, queue);

		final Offsets<As400Partition, As400OffsetContext> previousOffsetPartition = getPreviousOffsets(
				new As400Partition.Provider(connectorConfig), new As400OffsetContext.Loader(connectorConfig));
		As400OffsetContext previousOffset = previousOffsetPartition.getTheOnlyOffset();
		if (previousOffset == null) {
			log.info("previous offsets not found creating from config");
			previousOffset = new As400OffsetContext(connectorConfig);
		}

		final As400EventMetadataProvider metadataProvider = new As400EventMetadataProvider();

		final As400TaskContext taskContext = new As400TaskContext(connectorConfig, schema);
		final As400ConnectorConfig newConfig = taskContext.getConfig();

		final As400StreamingChangeEventSourceMetrics streamingMetrics = new As400StreamingChangeEventSourceMetrics(
				taskContext, queue, metadataProvider);

		final List<FileFilter> shortIncludes = jdbcConnection.shortIncludes(schema.getSchemaName(),
				newConfig.tableIncludeList());

		final As400RpcConnection rpcConnection = new As400RpcConnection(connectorConfig, streamingMetrics,
				shortIncludes);

		As400ConnectorConfig snapshotConnectorConfig = connectorConfig;
		final Set<String> additionalTables = additionalTablesInConfigTables(connectorConfig, previousOffset, newConfig);
		if (!additionalTables.isEmpty()) {
			final String newIncludes = String.join(",", additionalTables);
			log.info("found new tables to stream {}", newIncludes);

			snapshotConnectorConfig = new As400ConnectorConfig(config, newIncludes);
			previousOffset.hasNewTables(true);
		} else {
			log.info("no new tables to stream");
		}

		final EventDispatcher<As400Partition, TableId> dispatcher = new EventDispatcher<>(connectorConfig, // CommonConnectorConfig
				topicNamingStrategy, // TopicSelector
				schema, // DatabaseSchema
				queue, // ChangeEventQueue
				newConfig.getTableFilters().dataCollectionFilter(), // DataCollectionFilter
				DataChangeEvent::new, // ! ChangeEventCreator
				metadataProvider, schemaNameAdjuster);

		final Clock clock = Clock.system();

		final ChangeEventSourceCoordinator<As400Partition, As400OffsetContext> coordinator = new ChangeEventSourceCoordinator<>(
				previousOffsetPartition, errorHandler, As400JdbcConnector.class, newConfig,
				new As400ChangeEventSourceFactory(newConfig, snapshotConnectorConfig, rpcConnection,
						jdbcConnectionFactory, errorHandler, dispatcher, clock, schema),
				new As400ChangeEventSourceMetricsFactory(streamingMetrics), dispatcher, schema);

		coordinator.start(taskContext, this.queue, metadataProvider);

		return coordinator;
	}

	private Configuration addDefaultHeartbeatToConfig(Configuration config) {
		final int heartbeat = config.getInteger("heartbeat.interval.ms", 0);
		if (heartbeat == 0) {
			config = Configuration.copy(config).with("heartbeat.interval.ms", 60000).build();
		}
		return config;
	}

	private Configuration addDefaultPrefixToConfig(Configuration config) {
		final String prefix = config.getString("topic.prefix", "");
		if (prefix.isEmpty()) {
			config = Configuration.copy(config).with("topic.prefix", config.getString(JdbcConfiguration.HOSTNAME))
					.build();
		}
		return config;
	}

	private Set<String> additionalTablesInConfigTables(final As400ConnectorConfig connectorConfig,
			As400OffsetContext previousOffset, As400ConnectorConfig newConfig) {
		final String newInclude = newConfig.tableIncludeList();
		final String oldInclude = previousOffset.getIncludeTables();
		log.info("previous includes {} , new includes {}", oldInclude, newInclude);

		if (oldInclude != null && newInclude != null) {
			final Set<String> newset = Stream.of(newInclude.split(",", -1))
					.collect(Collectors.toCollection(HashSet::new));
			newset.removeAll(Set.of(oldInclude.split(",")));
			return newset;
		}
		return Collections.emptySet();
	}

	@Override
	protected List<SourceRecord> doPoll() throws InterruptedException {
		final List<DataChangeEvent> records = queue.poll();

		final List<SourceRecord> sourceRecords = records.stream().map(DataChangeEvent::getRecord)
				.collect(Collectors.toList());

		return sourceRecords;
	}

	@Override
	protected void doStop() {
	}

	@Override
	protected Iterable<Field> getAllConfigurationFields() {
		return As400ConnectorConfig.ALL_FIELDS;
	}

}
