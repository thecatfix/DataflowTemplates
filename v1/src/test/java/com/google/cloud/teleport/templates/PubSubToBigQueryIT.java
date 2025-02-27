/*
 * Copyright (C) 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.teleport.templates;

import static com.google.cloud.teleport.it.gcp.bigquery.matchers.BigQueryAsserts.assertThatBigQueryRecords;
import static com.google.cloud.teleport.it.truthmatchers.PipelineAsserts.assertThatPipeline;
import static com.google.cloud.teleport.it.truthmatchers.PipelineAsserts.assertThatResult;
import static com.google.common.truth.Truth.assertThat;

import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.LegacySQLTypeName;
import com.google.cloud.bigquery.Schema;
import com.google.cloud.bigquery.StandardSQLTypeName;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.bigquery.TableResult;
import com.google.cloud.teleport.it.common.PipelineLauncher.LaunchConfig;
import com.google.cloud.teleport.it.common.PipelineLauncher.LaunchInfo;
import com.google.cloud.teleport.it.common.PipelineOperator.Result;
import com.google.cloud.teleport.it.common.utils.ResourceManagerUtils;
import com.google.cloud.teleport.it.gcp.TemplateTestBase;
import com.google.cloud.teleport.it.gcp.bigquery.BigQueryResourceManager;
import com.google.cloud.teleport.it.gcp.bigquery.conditions.BigQueryRowsCheck;
import com.google.cloud.teleport.it.gcp.pubsub.PubsubResourceManager;
import com.google.cloud.teleport.metadata.TemplateIntegrationTest;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.SubscriptionName;
import com.google.pubsub.v1.TopicName;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.apache.commons.lang3.RandomStringUtils;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Integration test for {@link PubSubToBigQuery} classic template. */
@Category(TemplateIntegrationTest.class)
@RunWith(JUnit4.class)
public final class PubSubToBigQueryIT extends TemplateTestBase {

  private static final int MESSAGES_COUNT = 10;
  private static final int BAD_MESSAGES_COUNT = 3;

  private static final Schema BIG_QUERY_DLQ_SCHEMA = getDlqSchema();

  private PubsubResourceManager pubsubResourceManager;
  private BigQueryResourceManager bigQueryResourceManager;

  @Before
  public void setUp() throws IOException {
    pubsubResourceManager =
        PubsubResourceManager.builder(testName, PROJECT)
            .credentialsProvider(credentialsProvider)
            .build();
    bigQueryResourceManager =
        BigQueryResourceManager.builder(testName, PROJECT).setCredentials(credentials).build();

    gcsClient.createArtifact(
        "udf.js",
        "function uppercaseName(value) {\n"
            + "  const data = JSON.parse(value);\n"
            + "  data.name = data.name.toUpperCase();\n"
            + "  return JSON.stringify(data);\n"
            + "}");
  }

  @After
  public void cleanUp() {
    ResourceManagerUtils.cleanResources(pubsubResourceManager, bigQueryResourceManager);
  }

  @Test
  @TemplateIntegrationTest(value = PubSubToBigQuery.class, template = "PubSub_to_BigQuery")
  public void testTopicToBigQueryClassic() throws IOException {
    // Arrange
    List<Field> bqSchemaFields =
        Arrays.asList(
            Field.of("id", StandardSQLTypeName.INT64),
            Field.of("job", StandardSQLTypeName.STRING),
            Field.of("name", StandardSQLTypeName.STRING));
    Schema bqSchema = Schema.of(bqSchemaFields);

    TopicName topic = pubsubResourceManager.createTopic("input");
    bigQueryResourceManager.createDataset(REGION);
    TableId table = bigQueryResourceManager.createTable(testName, bqSchema);

    TableId dlqTable =
        bigQueryResourceManager.createTable(
            table.getTable() + PubSubToBigQuery.DEFAULT_DEADLETTER_TABLE_SUFFIX,
            BIG_QUERY_DLQ_SCHEMA);

    // Act
    LaunchInfo info =
        launchTemplate(
            LaunchConfig.builder(testName, specPath)
                .addParameter("inputTopic", topic.toString())
                .addParameter("outputTableSpec", toTableSpecLegacy(table))
                .addParameter("javascriptTextTransformGcsPath", getGcsPath("udf.js"))
                .addParameter("javascriptTextTransformFunctionName", "uppercaseName")
                .addParameter("outputDeadletterTable", toTableSpecLegacy(dlqTable)));
    assertThatPipeline(info).isRunning();

    List<Map<String, Object>> expectedMessages = new ArrayList<>();
    List<ByteString> goodData = new ArrayList<>();
    for (int i = 1; i <= MESSAGES_COUNT; i++) {
      Map<String, Object> message =
          new HashMap<>(
              Map.of("id", i, "job", testName, "name", RandomStringUtils.randomAlphabetic(1, 20)));
      ByteString messageData = ByteString.copyFromUtf8(new JSONObject(message).toString());
      goodData.add(messageData);
      pubsubResourceManager.publish(topic, ImmutableMap.of(), messageData);
      message.put("name", message.get("name").toString().toUpperCase());
      expectedMessages.add(message);
    }

    List<ByteString> badData = new ArrayList<>();
    for (int i = 1; i <= BAD_MESSAGES_COUNT; i++) {
      ByteString messageData = ByteString.copyFromUtf8("bad id " + i);
      pubsubResourceManager.publish(topic, ImmutableMap.of(), messageData);
      badData.add(messageData);
    }

    // For tests that run against topics, sending repeatedly will make it work for
    // cases in which the on-demand subscription is created after sending messages.
    Supplier<Boolean> pubSubMessageSender =
        () -> {
          goodData.forEach(
              goodMessage -> pubsubResourceManager.publish(topic, ImmutableMap.of(), goodMessage));
          badData.forEach(
              badMessage -> pubsubResourceManager.publish(topic, ImmutableMap.of(), badMessage));
          return true;
        };

    Result result =
        pipelineOperator()
            .waitForConditionsAndFinish(
                createConfig(info),
                pubSubMessageSender,
                BigQueryRowsCheck.builder(bigQueryResourceManager, table)
                    .setMinRows(MESSAGES_COUNT)
                    .build(),
                BigQueryRowsCheck.builder(bigQueryResourceManager, dlqTable)
                    .setMinRows(BAD_MESSAGES_COUNT)
                    .build());

    // Assert
    assertThatResult(result).meetsConditions();
    TableResult records = bigQueryResourceManager.readTable(table);

    // Make sure record can be read and UDF changed name to uppercase
    assertThatBigQueryRecords(records).hasRecordsUnordered(expectedMessages);

    TableResult dlqRecords = bigQueryResourceManager.readTable(dlqTable);
    assertThat(dlqRecords.getValues().iterator().next().toString())
        .contains("Expected json literal but found");
    assertThat(dlqRecords.getTotalRows()).isAtLeast(BAD_MESSAGES_COUNT);
  }

  @Test
  @TemplateIntegrationTest(
      value = PubSubToBigQuery.class,
      template = "PubSub_Subscription_to_BigQuery")
  public void testSubscriptionToBigQueryClassic() throws IOException {
    // Arrange
    List<Field> bqSchemaFields =
        Arrays.asList(
            Field.of("id", StandardSQLTypeName.INT64),
            Field.of("job", StandardSQLTypeName.STRING),
            Field.of("name", StandardSQLTypeName.STRING));
    Schema bqSchema = Schema.of(bqSchemaFields);

    TopicName topic = pubsubResourceManager.createTopic("input");
    SubscriptionName subscription = pubsubResourceManager.createSubscription(topic, "input-sub-1");
    bigQueryResourceManager.createDataset(REGION);
    TableId table = bigQueryResourceManager.createTable(testName, bqSchema);

    TableId dlqTable =
        bigQueryResourceManager.createTable(
            table.getTable() + PubSubToBigQuery.DEFAULT_DEADLETTER_TABLE_SUFFIX,
            BIG_QUERY_DLQ_SCHEMA);

    // Act
    LaunchInfo info =
        launchTemplate(
            LaunchConfig.builder(testName, specPath)
                .addParameter("inputSubscription", subscription.toString())
                .addParameter("outputTableSpec", toTableSpecLegacy(table))
                .addParameter("javascriptTextTransformGcsPath", getGcsPath("udf.js"))
                .addParameter("javascriptTextTransformFunctionName", "uppercaseName")
                .addParameter("outputDeadletterTable", toTableSpecLegacy(dlqTable)));
    assertThatPipeline(info).isRunning();

    List<Map<String, Object>> expectedMessages = new ArrayList<>();
    for (int i = 1; i <= MESSAGES_COUNT; i++) {
      Map<String, Object> message =
          new HashMap<>(
              Map.of("id", i, "job", testName, "name", RandomStringUtils.randomAlphabetic(1, 20)));
      ByteString messageData = ByteString.copyFromUtf8(new JSONObject(message).toString());
      pubsubResourceManager.publish(topic, ImmutableMap.of(), messageData);
      message.put("name", message.get("name").toString().toUpperCase());
      expectedMessages.add(message);
    }

    for (int i = 1; i <= BAD_MESSAGES_COUNT; i++) {
      ByteString messageData = ByteString.copyFromUtf8("bad id " + i);
      pubsubResourceManager.publish(topic, ImmutableMap.of(), messageData);
    }

    Result result =
        pipelineOperator()
            .waitForConditionsAndFinish(
                createConfig(info),
                BigQueryRowsCheck.builder(bigQueryResourceManager, table)
                    .setMinRows(MESSAGES_COUNT)
                    .build(),
                BigQueryRowsCheck.builder(bigQueryResourceManager, dlqTable)
                    .setMinRows(BAD_MESSAGES_COUNT)
                    .build());

    // Assert
    assertThatResult(result).meetsConditions();
    TableResult records = bigQueryResourceManager.readTable(table);

    // Make sure record can be read and UDF changed name to uppercase
    assertThatBigQueryRecords(records).hasRecordsUnordered(expectedMessages);

    TableResult dlqRecords = bigQueryResourceManager.readTable(dlqTable);
    assertThat(dlqRecords.getValues().iterator().next().toString())
        .contains("Expected json literal but found");
    assertThat(dlqRecords.getTotalRows()).isAtLeast(BAD_MESSAGES_COUNT);
  }

  private static Schema getDlqSchema() {
    return Schema.of(
        Arrays.asList(
            Field.newBuilder("timestamp", StandardSQLTypeName.TIMESTAMP)
                .setMode(Field.Mode.REQUIRED)
                .build(),
            Field.newBuilder("payloadString", StandardSQLTypeName.STRING)
                .setMode(Field.Mode.REQUIRED)
                .build(),
            Field.newBuilder("payloadBytes", StandardSQLTypeName.BYTES)
                .setMode(Field.Mode.REQUIRED)
                .build(),
            Field.newBuilder(
                    "attributes",
                    LegacySQLTypeName.RECORD,
                    Field.newBuilder("key", StandardSQLTypeName.STRING)
                        .setMode(Field.Mode.NULLABLE)
                        .build(),
                    Field.newBuilder("value", StandardSQLTypeName.STRING)
                        .setMode(Field.Mode.NULLABLE)
                        .build())
                .setMode(Field.Mode.REPEATED)
                .build(),
            Field.newBuilder("errorMessage", StandardSQLTypeName.STRING)
                .setMode(Field.Mode.NULLABLE)
                .build(),
            Field.newBuilder("stacktrace", StandardSQLTypeName.STRING)
                .setMode(Field.Mode.NULLABLE)
                .build()));
  }
}
