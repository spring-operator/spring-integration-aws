/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.aws.metadata;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.integration.metadata.ConcurrentMetadataStore;
import org.springframework.util.Assert;

import com.amazonaws.handlers.AsyncHandler;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsync;
import com.amazonaws.services.dynamodbv2.document.AttributeUpdate;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Expected;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.document.spec.DeleteItemSpec;
import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec;
import com.amazonaws.services.dynamodbv2.model.AttributeDefinition;
import com.amazonaws.services.dynamodbv2.model.ConditionalCheckFailedException;
import com.amazonaws.services.dynamodbv2.model.CreateTableRequest;
import com.amazonaws.services.dynamodbv2.model.CreateTableResult;
import com.amazonaws.services.dynamodbv2.model.DescribeTableRequest;
import com.amazonaws.services.dynamodbv2.model.KeySchemaElement;
import com.amazonaws.services.dynamodbv2.model.KeyType;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput;
import com.amazonaws.services.dynamodbv2.model.ResourceNotFoundException;
import com.amazonaws.services.dynamodbv2.model.ReturnValue;
import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType;
import com.amazonaws.waiters.FixedDelayStrategy;
import com.amazonaws.waiters.MaxAttemptsRetryStrategy;
import com.amazonaws.waiters.PollingStrategy;
import com.amazonaws.waiters.Waiter;
import com.amazonaws.waiters.WaiterHandler;
import com.amazonaws.waiters.WaiterParameters;

/**
 * The {@link ConcurrentMetadataStore} for the {@link AmazonDynamoDB}.
 *
 * @author Artem Bilan
 *
 * @since 1.1
 */
public class DynamoDbMetaDataStore implements ConcurrentMetadataStore, InitializingBean {

	/**
	 * The {@value DEFAULT_TABLE_NAME} default name for the metadata table in the DynamoDB.
	 */
	public static final String DEFAULT_TABLE_NAME = "SpringIntegrationMetadataStore";

	private static final Log logger = LogFactory.getLog(DynamoDbMetaDataStore.class);

	private static final String KEY = "KEY";

	private static final String VALUE = "VALUE";

	private final AmazonDynamoDBAsync dynamoDB;

	private final Table table;

	private final CountDownLatch createTableLatch = new CountDownLatch(1);

	private int createTableRetries = 25;

	private int createTableDelay = 1;

	private long readCapacity = 1L;

	private long writeCapacity = 1L;

	public DynamoDbMetaDataStore(AmazonDynamoDBAsync dynamoDB) {
		this(dynamoDB, DEFAULT_TABLE_NAME);
	}

	public DynamoDbMetaDataStore(AmazonDynamoDBAsync dynamoDB, String tableName) {
		Assert.notNull(dynamoDB, "'dynamoDB' must not be null.");
		Assert.hasText(tableName, "'tableName' must not be empty.");
		this.dynamoDB = dynamoDB;
		this.table =
				new DynamoDB(this.dynamoDB)
						.getTable(tableName);

	}

	public void setReadCapacity(long readCapacity) {
		this.readCapacity = readCapacity;
	}

	public void setWriteCapacity(long writeCapacity) {
		this.writeCapacity = writeCapacity;
	}

	public void setCreateTableRetries(int createTableRetries) {
		this.createTableRetries = createTableRetries;
	}

	public void setCreateTableDelay(int createTableDelay) {
		this.createTableDelay = createTableDelay;
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		try {
			this.table.describe();
			this.createTableLatch.countDown();
			return;
		}
		catch (ResourceNotFoundException e) {
			if (logger.isInfoEnabled()) {
				logger.info("No table '" + this.table.getTableName() + "'. Creating one...");
			}
		}

		CreateTableRequest createTableRequest =
				new CreateTableRequest()
						.withTableName(this.table.getTableName())
						.withKeySchema(new KeySchemaElement(KEY, KeyType.HASH))
						.withAttributeDefinitions(new AttributeDefinition(KEY, ScalarAttributeType.S))
						.withProvisionedThroughput(new ProvisionedThroughput(this.readCapacity, this.writeCapacity));


		this.dynamoDB.createTableAsync(createTableRequest,
				new AsyncHandler<CreateTableRequest, CreateTableResult>() {

					@Override
					public void onError(Exception e) {
						logger.error("Cannot create DynamoDb table: " +
								DynamoDbMetaDataStore.this.table.getTableName(), e);
						DynamoDbMetaDataStore.this.createTableLatch.countDown();
					}

					@Override
					public void onSuccess(CreateTableRequest request, CreateTableResult createTableResult) {
						Waiter<DescribeTableRequest> waiter =
								DynamoDbMetaDataStore.this.dynamoDB.waiters()
										.tableExists();

						WaiterParameters<DescribeTableRequest> waiterParameters =
								new WaiterParameters<>(
										new DescribeTableRequest(DynamoDbMetaDataStore.this.table.getTableName()))
										.withPollingStrategy(
												new PollingStrategy(
														new MaxAttemptsRetryStrategy(DynamoDbMetaDataStore.this.createTableRetries),
														new FixedDelayStrategy(DynamoDbMetaDataStore.this.createTableDelay)));

						waiter.runAsync(waiterParameters, new WaiterHandler<DescribeTableRequest>() {

							@Override
							public void onWaitSuccess(DescribeTableRequest request) {
								DynamoDbMetaDataStore.this.createTableLatch.countDown();
								DynamoDbMetaDataStore.this.table.describe();
							}

							@Override
							public void onWaitFailure(Exception e) {
								logger.error("Cannot describe DynamoDb table: " +
										DynamoDbMetaDataStore.this.table.getTableName(), e);
								DynamoDbMetaDataStore.this.createTableLatch.countDown();
							}

						});
					}

				});
	}

	private void awaitForActive() {
		try {
			this.createTableLatch.await(this.createTableRetries * this.createTableDelay, TimeUnit.SECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("The DynamoDb table " + this.table.getTableName() +
					" has not been created during " + this.createTableRetries * this.createTableDelay + " seconds");
		}
	}

	@Override
	public void put(String key, String value) {
		Assert.hasText(key, "'key' must not be empty.");
		Assert.hasText(value, "'value' must not be empty.");

		awaitForActive();

		this.table.putItem(
				new Item()
						.withPrimaryKey(KEY, key)
						.withString(VALUE, value));
	}

	@Override
	public String get(String key) {
		Assert.hasText(key, "'key' must not be empty.");

		awaitForActive();

		Item item = this.table.getItem(KEY, key);

		return getValueIfAny(item);
	}

	@Override
	public String putIfAbsent(String key, String value) {
		Assert.hasText(key, "'key' must not be empty.");
		Assert.hasText(value, "'value' must not be empty.");

		awaitForActive();

		try {
			this.table.updateItem(
					new UpdateItemSpec()
							.withPrimaryKey(KEY, key)
							.withAttributeUpdate(
									new AttributeUpdate(VALUE)
											.put(value))
							.withExpected(
									new Expected(KEY)
											.notExist()));

			return null;
		}
		catch (ConditionalCheckFailedException e) {
			return get(key);
		}
	}

	@Override
	public boolean replace(String key, String oldValue, String newValue) {
		Assert.hasText(key, "'key' must not be empty.");
		Assert.hasText(oldValue, "'value' must not be empty.");
		Assert.hasText(newValue, "'newValue' must not be empty.");

		awaitForActive();

		try {
			return this.table.updateItem(
					new UpdateItemSpec()
							.withPrimaryKey(KEY, key)
							.withAttributeUpdate(
									new AttributeUpdate(VALUE)
											.put(newValue))
							.withExpected(
									new Expected(VALUE)
											.eq(oldValue))
							.withReturnValues(ReturnValue.UPDATED_NEW))
					.getItem() != null;
		}
		catch (ConditionalCheckFailedException e) {
			return false;
		}
	}

	@Override
	public String remove(String key) {
		Assert.hasText(key, "'key' must not be empty.");

		awaitForActive();

		Item item = this.table.deleteItem(
				new DeleteItemSpec()
						.withPrimaryKey(KEY, key)
						.withReturnValues(ReturnValue.ALL_OLD))
				.getItem();

		return getValueIfAny(item);
	}

	private static String getValueIfAny(Item item) {
		if (item != null) {
			return item.getString(VALUE);
		}
		else {
			return null;
		}
	}

}
