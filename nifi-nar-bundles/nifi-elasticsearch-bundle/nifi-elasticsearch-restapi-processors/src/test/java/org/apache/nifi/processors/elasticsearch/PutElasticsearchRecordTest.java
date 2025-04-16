/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License") you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.elasticsearch;

import org.apache.avro.Schema;
import org.apache.nifi.avro.AvroTypeUtil;
import org.apache.nifi.elasticsearch.IndexOperationRequest;
import org.apache.nifi.elasticsearch.IndexOperationResponse;
import org.apache.nifi.json.JsonRecordSetWriter;
import org.apache.nifi.json.JsonTreeReader;
import org.apache.nifi.processors.elasticsearch.mock.MockBulkLoadClientService;
import org.apache.nifi.provenance.ProvenanceEventType;
import org.apache.nifi.schema.access.SchemaAccessUtils;
import org.apache.nifi.serialization.RecordReaderFactory;
import org.apache.nifi.serialization.record.MockRecordParser;
import org.apache.nifi.serialization.record.MockSchemaRegistry;
import org.apache.nifi.serialization.record.RecordFieldType;
import org.apache.nifi.serialization.record.RecordSchema;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.StringUtils;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PutElasticsearchRecordTest extends AbstractPutElasticsearchTest<PutElasticsearchRecord> {
    private static final int DATE_YEAR = 2020;
    private static final int DATE_MONTH = 11;
    private static final int DATE_DAY = 27;
    private static final int TIME_HOUR = 12;
    private static final int TIME_MINUTE = 55;
    private static final int TIME_SECOND = 23;
    private static final LocalDateTime LOCAL_DATE_TIME = LocalDateTime.of(DATE_YEAR, DATE_MONTH, DATE_DAY, TIME_HOUR, TIME_MINUTE, TIME_SECOND);
    private static final LocalDate LOCAL_DATE = LocalDate.of(DATE_YEAR, DATE_MONTH, DATE_DAY);
    private static final LocalTime LOCAL_TIME = LocalTime.of(TIME_HOUR, TIME_MINUTE, TIME_SECOND);
    private static final String TEST_DIR = "src/test/resources/PutElasticsearchRecordTest";
    private static final String TEST_COMMON_DIR = "src/test/resources/common";
    private static final String RECORD_PATH_TEST_SCHEMA = "recordPathTest";
    private static final String DATE_TIME_FORMATTING_TEST_SCHEMA = "dateTimeFormattingTest";
    private static final String SCHEMA_NAME_ATTRIBUTE = "schema.name";
    private static final Path VALUES = Paths.get(TEST_DIR, "values.json");
    private static String flowFileContentMaps;
    private static RecordSchema simpleSchema;
    private static RecordSchema recordPathTestSchema;
    private static RecordSchema dateTimeFormattingTestSchema;
    private static RecordSchema errorTestSchema;
    private MockBulkLoadClientService clientService;
    private MockSchemaRegistry registry;
    private TestRunner runner;

    @Override
    public Class<? extends AbstractPutElasticsearch> getTestProcessor() {
        return PutElasticsearchRecord.class;
    }
    @BeforeAll
    public static void setUpBeforeClass() throws Exception {
        flowFileContentMaps = JsonUtils.readString(Paths.get(TEST_DIR, "flowFileContentMaps.json"));
        simpleSchema = getRecordSchema(Paths.get(TEST_DIR, "simpleSchema.json"));
        recordPathTestSchema = getRecordSchema(Paths.get(TEST_DIR, "recordPathTestSchema.json"));
        dateTimeFormattingTestSchema = getRecordSchema(Paths.get(TEST_DIR, "dateTimeFormattingTestSchema.json"));
        errorTestSchema = getRecordSchema(Paths.get(TEST_DIR, "errorTestSchema.json"));
    }

    @BeforeEach
    public void setup() throws Exception {
        clientService = new MockBulkLoadClientService();
        clientService.setResponse(new IndexOperationResponse(1500));
        registry = new MockSchemaRegistry();
        registry.addSchema("simple", simpleSchema);
        final RecordReaderFactory reader = new JsonTreeReader();
        runner = TestRunners.newTestRunner(getTestProcessor());
        runner.addControllerService("registry", registry);
        runner.addControllerService("reader", reader);
        runner.addControllerService("clientService", clientService);
        runner.setProperty(reader, SchemaAccessUtils.SCHEMA_REGISTRY, "registry");
        runner.setProperty(reader, SchemaAccessUtils.SCHEMA_ACCESS_STRATEGY, SchemaAccessUtils.SCHEMA_NAME_PROPERTY);
        runner.setProperty(PutElasticsearchRecord.RECORD_READER, "reader");
        runner.setProperty(PutElasticsearchRecord.INDEX_OP, IndexOperationRequest.Operation.Index.getValue());
        runner.setProperty(PutElasticsearchRecord.INDEX, "test_index");
        runner.setProperty(PutElasticsearchRecord.TYPE, "test_type");
        runner.setProperty(PutElasticsearchRecord.AT_TIMESTAMP, "test_timestamp");
        runner.setProperty(PutElasticsearchRecord.CLIENT_SERVICE, "clientService");
        runner.setProperty(PutElasticsearchRecord.NOT_FOUND_IS_SUCCESSFUL, "true");
        runner.enableControllerService(registry);
        runner.enableControllerService(reader);
        runner.enableControllerService(clientService);

        runner.assertValid();
    }

    public void basicTest(final int failure, final int retry, final int success) {
        final Consumer<List<IndexOperationRequest>> consumer = (final List<IndexOperationRequest> items) -> {
            final long timestampDefaultCount = items.stream().filter(item -> "test_timestamp".equals(item.getFields().get("@timestamp"))).count();
            final long indexCount = items.stream().filter(item -> "test_index".equals(item.getIndex())).count();
            final long typeCount = items.stream().filter(item -> "test_type".equals(item.getType())).count();
            final long opCount = items.stream().filter(item ->  IndexOperationRequest.Operation.Index.equals(item.getOperation())).count();
            final long emptyScriptCount = items.stream().filter(item -> item.getScript().isEmpty()).count();
            final long falseScriptedUpsertCount = items.stream().filter(item -> !item.isScriptedUpsert()).count();
            final long emptyDynamicTemplatesCount = items.stream().filter(item -> item.getDynamicTemplates().isEmpty()).count();
            final long emptyHeaderFields = items.stream().filter(item -> item.getHeaderFields().isEmpty()).count();
            assertEquals(2, timestampDefaultCount);
            assertEquals(2, indexCount);
            assertEquals(2, typeCount);
            assertEquals(2, opCount);
            assertEquals(2, emptyScriptCount);
            assertEquals(2, falseScriptedUpsertCount);
            assertEquals(2, emptyDynamicTemplatesCount);
            assertEquals(2, emptyHeaderFields);
        };

        basicTest(failure, retry, success, consumer);
    }

    public void basicTest(final int failure, final int retry, final int success, final Consumer<List<IndexOperationRequest>> consumer) {
        basicTest(failure, retry, success, consumer, null);
    }

    public void basicTest(final int failure, final int retry, final int success, final Consumer<List<IndexOperationRequest>> consumer, final Map<String, String> attributes) {
        clientService.setEvalConsumer(consumer);
        runner.enqueue(flowFileContentMaps, attributes != null && !attributes.isEmpty() ? attributes : Collections.singletonMap(SCHEMA_NAME_ATTRIBUTE, "simple"));
        runner.run();

        runner.assertTransferCount(PutElasticsearchRecord.REL_FAILURE, failure);
        runner.assertTransferCount(PutElasticsearchRecord.REL_RETRY, retry);
        runner.assertTransferCount(PutElasticsearchRecord.REL_SUCCESS, success);
        runner.assertTransferCount(PutElasticsearchRecord.REL_FAILED_RECORDS, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_SUCCESSFUL_RECORDS, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_ERROR_RESPONSES, 0);

        if (success > 0) {
            runner.getFlowFilesForRelationship(PutElasticsearchRecord.REL_SUCCESS).forEach(ff -> {
                ff.assertAttributeEquals("elasticsearch.put.success.count", "0");
                ff.assertAttributeEquals("elasticsearch.put.error.count", "0");});

            assertEquals(success,
                    runner.getProvenanceEvents().stream().filter(
                                    e -> ProvenanceEventType.SEND.equals(e.getEventType()) && "1 Elasticsearch _bulk operation batch(es) [0 error(s), 0 success(es)]".equals(e.getDetails()))
                            .count());
        }
    }

    @Test
    public void simpleTest() {
        clientService.setEvalParametersConsumer((Map<String, String> params) -> assertTrue(params.isEmpty()));
        basicTest(0, 0, 1);
    }

    @Test
    public void simpleTestCoercedDefaultTimestamp() {
        final Consumer<List<IndexOperationRequest>> consumer = (List<IndexOperationRequest> items) ->
                assertEquals(2L, items.stream().filter(item -> Long.valueOf(100).equals(item.getFields().get("@timestamp"))).count());

        runner.setProperty(PutElasticsearchRecord.AT_TIMESTAMP, "100");
        basicTest(0, 0, 1, consumer);
    }

    @Test
    public void simpleTestWithRequestParametersAndBulkHeaders() {
        runner.setProperty("another", "${blank}");
        runner.setVariable("slices", "auto");
        runner.setVariable("version", "/version");
        testWithRequestParametersAndBulkHeaders(null);
    }

    @Test
    public void simpleTestWithRequestParametersAndBulkHeadersFlowFileEL() {
        final Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(SCHEMA_NAME_ATTRIBUTE, "simple");
        attributes.put("version", "/version");
        attributes.put("slices", "auto");
        attributes.put("blank", " ");
        testWithRequestParametersAndBulkHeaders(attributes);
    }

    @Test
    public void simpleTestWithMockReader() throws Exception{
        final MockRecordParser mockReader = new MockRecordParser();
        mockReader.addSchemaField("msg", RecordFieldType.STRING);
        mockReader.addSchemaField("from", RecordFieldType.STRING);
        mockReader.addRecord("foo", "bar");
        mockReader.addRecord("biz", "baz");

        runner.addControllerService("mockReader", mockReader);
        runner.setProperty(PutElasticsearchRecord.RECORD_READER, "mockReader");
        runner.enableControllerService(mockReader);

        basicTest(0, 0, 1);
    }

    @Test
    public void testFatalError() {
        clientService.setThrowFatalError(true);
        basicTest(1, 0, 0);
    }

    @Test
    public void testRetriable() {
        clientService.setThrowRetriableError(true);
        basicTest(0, 1, 0);
    }

    @Test
    public void testRecordPathFeatures() throws Exception {
        final Map<String, Object> script =
                JsonUtils.readMap(JsonUtils.readString(Paths.get(TEST_DIR, "script.json")));
        final Map<String, Object> dynamicTemplates =
                JsonUtils.readMap(JsonUtils.readString(Paths.get(TEST_COMMON_DIR, "dynamicTemplates.json")));
        clientService.setEvalConsumer((List<IndexOperationRequest> items) -> {
            final long a = items.stream().filter(item ->  "bulk_a".equals(item.getIndex())).count();
            final long b = items.stream().filter(item ->  "bulk_b".equals(item.getIndex())).count();
            final long index = items.stream().filter(item ->  IndexOperationRequest.Operation.Index.equals(item.getOperation())).count();
            final long create = items.stream().filter(item ->  IndexOperationRequest.Operation.Create.equals(item.getOperation())).count();
            final long msg = items.stream().filter(item -> "Hello".equals(item.getFields().get("msg"))).count();
            final long empties = items.stream().filter(item -> ("".equals(item.getFields().get("msg")))).count();
            final long nulls = items.stream().filter(item -> null == item.getFields().get("msg")).count();
            final long timestamp = items.stream().filter(item ->
                    LOCAL_DATE_TIME.format(DateTimeFormatter.ofPattern(RecordFieldType.TIMESTAMP.getDefaultFormat())).equals(item.getFields().get("@timestamp"))).count();
            final long timestampDefault = items.stream().filter(item ->  "test_timestamp".equals(item.getFields().get("@timestamp"))).count();
            final long ts = items.stream().filter(item ->  item.getFields().get("ts") != null).count();
            final long id = items.stream().filter(item ->  item.getFields().get("id") != null).count();
            final long emptyScript = items.stream().filter(item ->  item.getScript().isEmpty()).count();
            final long falseScriptedUpsertCount = items.stream().filter(item -> !item.isScriptedUpsert()).count();
            final long trueScriptedUpsertCount = items.stream().filter(IndexOperationRequest::isScriptedUpsert).count();
            final long s = items.stream().filter(item ->  script.equals(item.getScript())).count();
            final long emptyDynamicTemplates = items.stream().filter(item ->  item.getDynamicTemplates().isEmpty()).count();
            final long dt = items.stream().filter(item ->  dynamicTemplates.equals(item.getDynamicTemplates())).count();
            items.forEach(item -> {
                assertNotNull(item.getId());
                assertTrue(item.getId().startsWith("rec-"));
                assertEquals("message", item.getType()); });

            assertEquals(3, a, getUnexpectedCountMsg("bulk a"));
            assertEquals(3, b, getUnexpectedCountMsg("bulk b"));
            assertEquals(5, index, getUnexpectedCountMsg("index"));
            assertEquals(1, create, getUnexpectedCountMsg("create"));
            assertEquals(4, msg, getUnexpectedCountMsg("msg"));
            assertEquals(1, empties, getUnexpectedCountMsg("empties"));
            assertEquals(1, nulls, getUnexpectedCountMsg("nulls"));
            assertEquals(1, timestamp, getUnexpectedCountMsg("timestamp"));
            assertEquals(5, timestampDefault, getUnexpectedCountMsg("timestampDefault"));
            assertEquals(0, ts, getUnexpectedCountMsg("ts"));
            assertEquals(0, id, getUnexpectedCountMsg("id"));
            assertEquals(5, emptyScript, getUnexpectedCountMsg("emptyScript"));
            assertEquals(5, falseScriptedUpsertCount, getUnexpectedCountMsg("falseScriptedUpsertCount"));
            assertEquals(1, trueScriptedUpsertCount, getUnexpectedCountMsg("trueScriptedUpsertCount"));
            assertEquals(1, s, getUnexpectedCountMsg("s"));
            assertEquals(5, emptyDynamicTemplates, getUnexpectedCountMsg("emptyDynamicTemplates"));
            assertEquals(1, dt, getUnexpectedCountMsg("dt"));
        });

        registry.addSchema(RECORD_PATH_TEST_SCHEMA, recordPathTestSchema);

        runner.removeProperty(PutElasticsearchRecord.INDEX_OP);
        runner.setProperty(PutElasticsearchRecord.INDEX_OP_RECORD_PATH, "/op");
        runner.setProperty(PutElasticsearchRecord.ID_RECORD_PATH, "/id");
        runner.setProperty(PutElasticsearchRecord.INDEX_RECORD_PATH, "/index");
        runner.setProperty(PutElasticsearchRecord.TYPE_RECORD_PATH, "/type");
        runner.setProperty(PutElasticsearchRecord.AT_TIMESTAMP_RECORD_PATH, "/ts");
        runner.setProperty(PutElasticsearchRecord.SCRIPT_RECORD_PATH, "/script");
        runner.setProperty(PutElasticsearchRecord.SCRIPTED_UPSERT_RECORD_PATH, "/scripted_upsert");
        runner.setProperty(PutElasticsearchRecord.DYNAMIC_TEMPLATES_RECORD_PATH, "/dynamic_templates");
        String flowFileContents = JsonUtils.readString(Paths.get(TEST_DIR, "1_flowFileContents.json"));
        flowFileContents = flowFileContents.replaceFirst("\\d{13}", String.valueOf(Timestamp.valueOf(LOCAL_DATE_TIME).toInstant().toEpochMilli()));
        runner.enqueue(flowFileContents, Collections.singletonMap(SCHEMA_NAME_ATTRIBUTE, RECORD_PATH_TEST_SCHEMA));

        runner.run();
        runner.assertTransferCount(PutElasticsearchRecord.REL_SUCCESS, 1);
        runner.assertTransferCount(PutElasticsearchRecord.REL_FAILURE, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_RETRY, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_FAILED_RECORDS, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_SUCCESSFUL_RECORDS, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_ERROR_RESPONSES, 0);
    }

    @Test
    public void testTimestampDateFormatAndScriptRecordPath() throws Exception {
        final Map<String, Object> script =
                JsonUtils.readMap(JsonUtils.readString(Paths.get(TEST_DIR, "script.json")));
        clientService.setEvalConsumer((List<IndexOperationRequest> items) -> {
            final long testTypeCount = items.stream().filter(item ->  "test_type".equals(item.getType())).count();
            final long messageTypeCount = items.stream().filter(item ->  "message".equals(item.getType())).count();
            final long testIndexCount = items.stream().filter(item ->  "test_index".equals(item.getIndex())).count();
            final long bulkIndexCount = items.stream().filter(item ->  item.getIndex().startsWith("bulk_")).count();
            final long indexOperationCount = items.stream().filter(item ->  IndexOperationRequest.Operation.Index.equals(item.getOperation())).count();
            final long updateOperationCount = items.stream().filter(item ->  IndexOperationRequest.Operation.Update.equals(item.getOperation())).count();
            final long timestampCount = items.stream().filter(item ->
                    LOCAL_DATE.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")).equals(item.getFields().get("@timestamp"))).count();
            final long dateCount = items.stream().filter(item ->  item.getFields().get("date") != null).count();
            final long idCount = items.stream().filter(item ->  item.getFields().get("id") != null).count();
            final long defaultCoercedTimestampCount = items.stream().filter(item -> Long.valueOf(100).equals(item.getFields().get("@timestamp"))).count();
            final long emptyScriptCount = items.stream().filter(item ->  item.getScript().isEmpty()).count();
            final long scriptCount = items.stream().filter(item ->  script.equals(item.getScript())).count();
            assertEquals(5, testTypeCount, getUnexpectedCountMsg("test type"));
            assertEquals(1, messageTypeCount, getUnexpectedCountMsg("message type"));
            assertEquals(5, testIndexCount, getUnexpectedCountMsg("test index"));
            assertEquals(1, bulkIndexCount, getUnexpectedCountMsg("bulk index"));
            assertEquals(5, indexOperationCount, getUnexpectedCountMsg("index operation"));
            assertEquals(1, updateOperationCount, getUnexpectedCountMsg("update operation"));
            assertEquals(1, timestampCount, getUnexpectedCountMsg("timestamp"));
            assertEquals(5, defaultCoercedTimestampCount, getUnexpectedCountMsg("default coerced timestamp"));
            assertEquals(1, dateCount, getUnexpectedCountMsg("date"));
            assertEquals(6, idCount, getUnexpectedCountMsg("id"));
            assertEquals(5, emptyScriptCount, getUnexpectedCountMsg("empty script"));
            assertEquals(1, scriptCount, getUnexpectedCountMsg("script"));
        });

        registry.addSchema(RECORD_PATH_TEST_SCHEMA, recordPathTestSchema);
        runner.setProperty(PutElasticsearchRecord.INDEX_OP, "${operation}");
        runner.setProperty(PutElasticsearchRecord.RETAIN_ID_FIELD, "true");
        runner.setProperty(PutElasticsearchRecord.AT_TIMESTAMP, "100");
        runner.setProperty(PutElasticsearchRecord.AT_TIMESTAMP_RECORD_PATH, "/date");
        runner.setProperty(PutElasticsearchRecord.DATE_FORMAT, "dd/MM/yyyy");
        runner.setProperty(PutElasticsearchRecord.RETAIN_AT_TIMESTAMP_FIELD, "true");
        runner.setProperty(PutElasticsearchRecord.INDEX_OP_RECORD_PATH, "/op");
        runner.setProperty(PutElasticsearchRecord.TYPE_RECORD_PATH, "/type");
        runner.setProperty(PutElasticsearchRecord.INDEX_RECORD_PATH, "/index");
        runner.setProperty(PutElasticsearchRecord.SCRIPT_RECORD_PATH, "/script_record");
        final Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(SCHEMA_NAME_ATTRIBUTE, RECORD_PATH_TEST_SCHEMA);
        attributes.put("operation", "index");
        String flowFileContents = JsonUtils.readString(Paths.get(TEST_DIR, "2_flowFileContents.json"));
        flowFileContents = flowFileContents.replaceFirst("\\d{13}", String.valueOf(Date.valueOf(LOCAL_DATE).getTime()));
        runner.enqueue(flowFileContents, attributes);
        runner.run();
        runner.assertTransferCount(PutElasticsearchRecord.REL_SUCCESS, 1);
        runner.assertTransferCount(PutElasticsearchRecord.REL_FAILURE, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_RETRY, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_FAILED_RECORDS, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_SUCCESSFUL_RECORDS, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_ERROR_RESPONSES, 0);
    }

    @Test
    public void testNullRecordPaths() throws Exception {
        clientService.setEvalConsumer((final List<IndexOperationRequest> items) -> {
            final long nullTypeCount = items.stream().filter(item -> item.getType() == null).count();
            final long messageTypeCount = items.stream().filter(item -> "message".equals(item.getType())).count();
            final long nullIdCount = items.stream().filter(item -> item.getId() == null).count();
            final long recIdCount = items.stream().filter(item -> StringUtils.startsWith(item.getId(), "rec-")).count();
            final long timestampCount = items.stream().filter(item ->
                    LOCAL_TIME.format(DateTimeFormatter.ofPattern(RecordFieldType.TIME.getDefaultFormat())).equals(item.getFields().get("@timestamp"))).count();
            assertEquals(5, nullTypeCount, getUnexpectedCountMsg("null type"));
            assertEquals(1, messageTypeCount, getUnexpectedCountMsg("message type"));
            assertEquals(2, nullIdCount, getUnexpectedCountMsg("null id"));
            assertEquals(4, recIdCount, getUnexpectedCountMsg("rec- id"));
            assertEquals(1, timestampCount, getUnexpectedCountMsg("timestamp"));
        });

        registry.addSchema(RECORD_PATH_TEST_SCHEMA, recordPathTestSchema);
        runner.setProperty(PutElasticsearchRecord.INDEX_OP, "index");
        runner.removeProperty(PutElasticsearchRecord.AT_TIMESTAMP);
        runner.setProperty(PutElasticsearchRecord.AT_TIMESTAMP_RECORD_PATH, "/time");
        runner.removeProperty(PutElasticsearchRecord.TYPE);
        runner.setProperty(PutElasticsearchRecord.ID_RECORD_PATH, "/id");
        runner.setProperty(PutElasticsearchRecord.TYPE_RECORD_PATH, "/type");
        String flowFileContents = JsonUtils.readString(Paths.get(TEST_DIR, "3_flowFileContents.json"));
        flowFileContents = flowFileContents.replaceFirst("\\d{8}", String.valueOf(Time.valueOf(LOCAL_TIME).getTime()));
        runner.enqueue(flowFileContents, Collections.singletonMap(SCHEMA_NAME_ATTRIBUTE, RECORD_PATH_TEST_SCHEMA));
        runner.run();
        runner.assertTransferCount(PutElasticsearchRecord.REL_SUCCESS, 1);
        runner.assertTransferCount(PutElasticsearchRecord.REL_FAILURE, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_RETRY, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_FAILED_RECORDS, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_SUCCESSFUL_RECORDS, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_ERROR_RESPONSES, 0);
    }

    @Test
    public void testIndexOperationRecordPath() throws Exception {
        clientService.setEvalConsumer((final List<IndexOperationRequest> items) -> {
            final long index = items.stream().filter(item ->  IndexOperationRequest.Operation.Index.equals(item.getOperation())).count();
            final long create = items.stream().filter(item ->  IndexOperationRequest.Operation.Create.equals(item.getOperation())).count();
            final long update = items.stream().filter(item ->  IndexOperationRequest.Operation.Update.equals(item.getOperation())).count();
            final long upsert = items.stream().filter(item ->  IndexOperationRequest.Operation.Upsert.equals(item.getOperation())).count();
            final long delete = items.stream().filter(item ->  IndexOperationRequest.Operation.Delete.equals(item.getOperation())).count();
            final long timestampCount = items.stream().filter(item -> Long.valueOf(101).equals(item.getFields().get("@timestamp"))).count();
            final long noTimestampCount = items.stream().filter(item -> !item.getFields().containsKey("@timestamp") ).count();
            assertEquals(1, index, getUnexpectedCountMsg("index"));
            assertEquals(2, create, getUnexpectedCountMsg("create"));
            assertEquals(1, update, getUnexpectedCountMsg("update"));
            assertEquals(1, upsert, getUnexpectedCountMsg("upsert"));
            assertEquals(1, delete, getUnexpectedCountMsg("delete"));
            assertEquals(1, timestampCount, getUnexpectedCountMsg("timestamp"));
            assertEquals(5, noTimestampCount, getUnexpectedCountMsg("noTimestamp"));
        });

        registry.addSchema(RECORD_PATH_TEST_SCHEMA, recordPathTestSchema);
        runner.setProperty(PutElasticsearchRecord.AT_TIMESTAMP_RECORD_PATH, "/code");
        runner.setProperty(PutElasticsearchRecord.INDEX_OP_RECORD_PATH, "/op");
        runner.removeProperty(PutElasticsearchRecord.AT_TIMESTAMP);
        runner.enqueue(Paths.get(TEST_DIR, "4_flowFileContents.json"), Collections.singletonMap(SCHEMA_NAME_ATTRIBUTE, RECORD_PATH_TEST_SCHEMA));
        runner.run();
        runner.assertTransferCount(PutElasticsearchRecord.REL_SUCCESS, 1);
        runner.assertTransferCount(PutElasticsearchRecord.REL_FAILURE, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_RETRY, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_FAILED_RECORDS, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_SUCCESSFUL_RECORDS, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_ERROR_RESPONSES, 0);
    }

    @Test
    public void testIncompatibleTimestampRecordPath() throws Exception {
        clientService.setEvalConsumer((final List<IndexOperationRequest> items) -> {
            final long timestampCount = items.stream().filter(item ->  "Hello".equals(item.getFields().get("@timestamp"))).count();
            assertEquals(1, timestampCount);
        });

        registry.addSchema(RECORD_PATH_TEST_SCHEMA, recordPathTestSchema);
        runner.setProperty(PutElasticsearchRecord.AT_TIMESTAMP_RECORD_PATH, "/msg");
        runner.enqueue(Paths.get(TEST_DIR, "5_flowFileContents.json"), Collections.singletonMap(SCHEMA_NAME_ATTRIBUTE, RECORD_PATH_TEST_SCHEMA));
        runner.run();
        runner.assertTransferCount(PutElasticsearchRecord.REL_SUCCESS, 1);
        runner.assertTransferCount(PutElasticsearchRecord.REL_FAILURE, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_RETRY, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_FAILED_RECORDS, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_SUCCESSFUL_RECORDS, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_ERROR_RESPONSES, 0);
    }

    @Test
    public void testNotFoundELRecordPaths() throws Exception {
        testInvalidELRecordPaths("${id_not_exist}", "${not_exist}",
                Paths.get(TEST_DIR, "6_flowFileContents.json"), Collections.singletonMap(SCHEMA_NAME_ATTRIBUTE, RECORD_PATH_TEST_SCHEMA));
    }

    @Test
    public void testEmptyELRecordPaths() throws Exception {
        final Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(SCHEMA_NAME_ATTRIBUTE, RECORD_PATH_TEST_SCHEMA);
        attributes.put("will_be_empty", "/empty");
        testInvalidELRecordPaths("${will_be_empty}", "${will_be_empty}",
                Paths.get(TEST_DIR, "7_flowFileContents.json"), attributes);
    }

    @Test
    public void testInvalidDynamicTemplatesRecordPath() throws Exception {
        registry.addSchema(RECORD_PATH_TEST_SCHEMA, recordPathTestSchema);
        runner.setProperty(PutElasticsearchRecord.DYNAMIC_TEMPLATES_RECORD_PATH, "/dynamic_templates");
        runner.enqueue(Paths.get(TEST_DIR, "8_flowFileContents.json"), Collections.singletonMap(SCHEMA_NAME_ATTRIBUTE, RECORD_PATH_TEST_SCHEMA));
        runner.run();

        runner.assertTransferCount(PutElasticsearchRecord.REL_SUCCESS, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_FAILURE, 1);
        runner.assertTransferCount(PutElasticsearchRecord.REL_RETRY, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_FAILED_RECORDS, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_SUCCESSFUL_RECORDS, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_ERROR_RESPONSES, 0);
        final MockFlowFile failure = runner.getFlowFilesForRelationship(PutElasticsearchRecord.REL_FAILURE).get(0);
        failure.assertAttributeEquals("elasticsearch.put.error", String.format("Field referenced by %s must be Map-type compatible or a String parsable into a JSON Object", "/dynamic_templates"));
    }

    @Test
    public void testRecordPathFieldDefaults() throws Exception {
        clientService.setEvalConsumer((final List<IndexOperationRequest> items) -> {
            final long idNotNull = items.stream().filter(item ->  item.getId() != null).count();
            final long opIndex = items.stream().filter(item ->  IndexOperationRequest.Operation.Index.equals(item.getOperation())).count();
            final long opCreate = items.stream().filter(item ->  IndexOperationRequest.Operation.Create.equals(item.getOperation())).count();
            final long indexA = items.stream().filter(item ->  "bulk_a".equals(item.getIndex())).count();
            final long indexC = items.stream().filter(item ->  "bulk_c".equals(item.getIndex())).count();
            final long typeMessage = items.stream().filter(item ->  "message".equals(item.getType())).count();
            final long typeBlah = items.stream().filter(item -> "blah".equals(item.getType())).count();
            assertEquals(4, idNotNull, getUnexpectedCountMsg("id not null"));
            assertEquals(3, opIndex, getUnexpectedCountMsg("op index"));
            assertEquals(3, opCreate, getUnexpectedCountMsg("op create"));
            assertEquals(4, indexA, getUnexpectedCountMsg("index A"));
            assertEquals(2, indexC, getUnexpectedCountMsg("index C"));
            assertEquals(4, typeMessage, getUnexpectedCountMsg("type message"));
            assertEquals(2, typeBlah, getUnexpectedCountMsg("type blah"));
        });

        registry.addSchema(RECORD_PATH_TEST_SCHEMA, recordPathTestSchema);
        runner.setProperty(PutElasticsearchRecord.INDEX_OP, "index");
        runner.setProperty(PutElasticsearchRecord.INDEX_OP_RECORD_PATH, "/op");
        runner.setProperty(PutElasticsearchRecord.TYPE, "blah");
        runner.setProperty(PutElasticsearchRecord.TYPE_RECORD_PATH, "/type");
        runner.setProperty(PutElasticsearchRecord.INDEX, "bulk_c");
        runner.setProperty(PutElasticsearchRecord.INDEX_RECORD_PATH, "/index");
        runner.setProperty(PutElasticsearchRecord.ID_RECORD_PATH, "/id");
        runner.setProperty(PutElasticsearchRecord.RETAIN_ID_FIELD, "false");
        runner.removeProperty(PutElasticsearchRecord.AT_TIMESTAMP_RECORD_PATH);
        runner.enqueue(Paths.get(TEST_DIR, "9_flowFileContents.json"), Collections.singletonMap(SCHEMA_NAME_ATTRIBUTE, RECORD_PATH_TEST_SCHEMA));
        runner.run();

        runner.assertTransferCount(PutElasticsearchRecord.REL_SUCCESS, 1);
        runner.assertTransferCount(PutElasticsearchRecord.REL_FAILURE, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_RETRY, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_FAILED_RECORDS, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_SUCCESSFUL_RECORDS, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_ERROR_RESPONSES, 0);
    }

    @Test
    public void testDefaultDateTimeFormatting() throws Exception{
        clientService.setEvalConsumer((final List<IndexOperationRequest> items) -> {
            final long msg = items.stream().filter(item ->  (item.getFields().get("msg") != null)).count();
            final long timestamp = items.stream().filter(item ->
                    LOCAL_DATE_TIME.format(DateTimeFormatter.ofPattern(RecordFieldType.TIMESTAMP.getDefaultFormat())).equals(item.getFields().get("ts"))).count(); // "yyyy-MM-dd HH:mm:ss"
            final long date = items.stream().filter(item ->
                    LOCAL_DATE.format(DateTimeFormatter.ofPattern(RecordFieldType.DATE.getDefaultFormat())).equals(item.getFields().get("date"))).count(); // "yyyy-MM-dd"
            final long time = items.stream().filter(item ->
                    LOCAL_TIME.format(DateTimeFormatter.ofPattern(RecordFieldType.TIME.getDefaultFormat())).equals(item.getFields().get("time"))).count(); // "HH:mm:ss"
            final long choiceTs = items.stream().filter(item ->
                    LOCAL_DATE_TIME.format(DateTimeFormatter.ofPattern(RecordFieldType.TIMESTAMP.getDefaultFormat())).equals(item.getFields().get("choice_ts"))).count();
            final long choiceNotTs = items.stream().filter(item ->  "not-timestamp".equals(item.getFields().get("choice_ts"))).count();
            final long atTimestampDefault = items.stream().filter(item ->  "test_timestamp".equals(item.getFields().get("@timestamp"))).count();
            final long tsNull = items.stream().filter(item -> item.getFields().get("ts") == null).count();
            final long dateNull = items.stream().filter(item ->  item.getFields().get("date") == null).count();
            final long timeNull = items.stream().filter(item ->  item.getFields().get("time") == null).count();
            final long choiceTsNull = items.stream().filter(item ->  item.getFields().get("choice_ts") == null).count();
            assertEquals(5, msg, getUnexpectedCountMsg("msg"));
            assertEquals(2, timestamp, getUnexpectedCountMsg("timestamp"));
            assertEquals(2, date, getUnexpectedCountMsg("date"));
            assertEquals(2, time, getUnexpectedCountMsg("time"));
            assertEquals(1, choiceTs, getUnexpectedCountMsg("choiceTs"));
            assertEquals(1, choiceNotTs, getUnexpectedCountMsg("choiceNotTs"));
            assertEquals(3, tsNull, getUnexpectedCountMsg("tsNull"));
            assertEquals(3, dateNull, getUnexpectedCountMsg("dateNull"));
            assertEquals(3, timeNull, getUnexpectedCountMsg("timeNull"));
            assertEquals(3, choiceTsNull, getUnexpectedCountMsg("choiceTsNull"));
            assertEquals(5, atTimestampDefault, getUnexpectedCountMsg("atTimestampDefault"));
        });

        registry.addSchema(DATE_TIME_FORMATTING_TEST_SCHEMA, dateTimeFormattingTestSchema);
        runner.enqueue(getDateTimeFormattingJson(), Collections.singletonMap(SCHEMA_NAME_ATTRIBUTE, DATE_TIME_FORMATTING_TEST_SCHEMA));
        runner.run();

        runner.assertTransferCount(PutElasticsearchRecord.REL_SUCCESS, 1);
        runner.assertTransferCount(PutElasticsearchRecord.REL_FAILURE, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_RETRY, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_FAILED_RECORDS, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_SUCCESSFUL_RECORDS, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_ERROR_RESPONSES, 0);
    }

    @Test
    public void testCustomDateTimeFormatting() throws Exception {
        final String timestampFormat = "yy MMM d H";
        final String dateFormat = "dd/MM/yyyy";
        final String timeFormat = "HHmmss";
        clientService.setEvalConsumer((final List<IndexOperationRequest> items) -> {
            final String timestampOutput = LOCAL_DATE_TIME.format(DateTimeFormatter.ofPattern(timestampFormat));
            final long msg = items.stream().filter(item -> (item.getFields().get("msg") != null)).count();
            final long timestamp = items.stream().filter(item -> timestampOutput.equals(item.getFields().get("ts"))).count();
            final long date = items.stream().filter(item ->
                    LOCAL_DATE.format(DateTimeFormatter.ofPattern(dateFormat)).equals(item.getFields().get("date"))).count();
            final long time = items.stream().filter(item ->
                    // converted to a Long because the output is completely numerical
                    Long.valueOf(LOCAL_TIME.format(DateTimeFormatter.ofPattern(timeFormat))).equals(item.getFields().get("time"))).count();
            final long choiceTs = items.stream().filter(item -> timestampOutput.equals(item.getFields().get("choice_ts"))).count();
            final long choiceNotTs = items.stream().filter(item -> "not-timestamp".equals(item.getFields().get("choice_ts"))).count();
            final long atTimestampDefault = items.stream().filter(item -> "test_timestamp".equals(item.getFields().get("@timestamp"))).count();
            final long atTimestamp = items.stream().filter(item -> timestampOutput.equals(item.getFields().get("@timestamp"))).count();
            final long tsNull = items.stream().filter(item -> item.getFields().get("ts") == null).count();
            final long dateNull = items.stream().filter(item -> item.getFields().get("date") == null).count();
            final long timeNull = items.stream().filter(item -> item.getFields().get("time") == null).count();
            final long choiceTsNull = items.stream().filter(item -> item.getFields().get("choice_ts") == null).count();
            assertEquals(5, msg, getUnexpectedCountMsg("msg"));
            assertEquals(2, timestamp, getUnexpectedCountMsg("timestamp"));
            assertEquals(2, date, getUnexpectedCountMsg("date"));
            assertEquals(2, time, getUnexpectedCountMsg("time"));
            assertEquals(1, choiceTs, getUnexpectedCountMsg("choiceTs"));
            assertEquals(1, choiceNotTs, getUnexpectedCountMsg("choiceNotTs"));
            assertEquals(3, tsNull, getUnexpectedCountMsg("tsNull"));
            assertEquals(3, dateNull, getUnexpectedCountMsg("dateNull"));
            assertEquals(3, timeNull, getUnexpectedCountMsg("timeNull"));
            assertEquals(3, choiceTsNull, getUnexpectedCountMsg("choiceTsNull"));
            assertEquals(2, atTimestamp, getUnexpectedCountMsg("atTimestamp"));
            assertEquals(3, atTimestampDefault, getUnexpectedCountMsg("atTimestampDefault"));
        });

        registry.addSchema(DATE_TIME_FORMATTING_TEST_SCHEMA, dateTimeFormattingTestSchema);
        runner.setProperty(PutElasticsearchRecord.TIMESTAMP_FORMAT, timestampFormat);
        runner.setProperty(PutElasticsearchRecord.DATE_FORMAT, dateFormat);
        runner.setProperty(PutElasticsearchRecord.TIME_FORMAT, timeFormat);
        runner.setProperty(PutElasticsearchRecord.AT_TIMESTAMP_RECORD_PATH, "/ts");
        runner.setProperty(PutElasticsearchRecord.RETAIN_AT_TIMESTAMP_FIELD, "true");
        runner.enqueue(getDateTimeFormattingJson(), Collections.singletonMap(SCHEMA_NAME_ATTRIBUTE, DATE_TIME_FORMATTING_TEST_SCHEMA));
        runner.run();

        runner.assertTransferCount(PutElasticsearchRecord.REL_SUCCESS, 1);
        runner.assertTransferCount(PutElasticsearchRecord.REL_FAILURE, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_RETRY, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_FAILED_RECORDS, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_SUCCESSFUL_RECORDS, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_ERROR_RESPONSES, 0);
    }

    @Test
    public void testInvalidIndexOperation() {
        runner.setProperty(PutElasticsearchRecord.INDEX_OP, "not-valid");
        runner.assertNotValid();
        final AssertionError ae = assertThrows(AssertionError.class, runner::run);
        assertEquals(String.format("Processor has 1 validation failures:\n'%s' validated against 'not-valid' is invalid because %s must be Expression Language or one of %s\n",
                PutElasticsearchRecord.INDEX_OP.getName(), PutElasticsearchRecord.INDEX_OP.getDisplayName(), PutElasticsearchRecord.ALLOWED_INDEX_OPERATIONS), ae.getMessage());

        runner.setProperty(PutElasticsearchRecord.INDEX_OP, "${operation}");
        runner.assertValid();
        runner.enqueue(flowFileContentMaps, Collections.singletonMap("operation", "not-valid2"));
        runner.run();

        runner.assertTransferCount(PutElasticsearchRecord.REL_SUCCESS, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_FAILURE, 1);
        runner.assertTransferCount(PutElasticsearchRecord.REL_RETRY, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_FAILED_RECORDS, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_SUCCESSFUL_RECORDS, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_ERROR_RESPONSES, 0);
    }

    @Test
    public void testInputRequired() {
        runner.run();
        runner.assertTransferCount(PutElasticsearchRecord.REL_SUCCESS, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_FAILURE, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_RETRY, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_FAILED_RECORDS, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_SUCCESSFUL_RECORDS, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_ERROR_RESPONSES, 0);
    }

    @Test
    public void testFailedRecordsOutput() throws Exception {
        runner.setProperty(PutElasticsearchRecord.NOT_FOUND_IS_SUCCESSFUL, "true");
        runner.setProperty(PutElasticsearchRecord.LOG_ERROR_RESPONSES, "true");
        final int errorCount = 3;
        final int successCount = 4;
        testErrorRelationship(errorCount, successCount, true);

        runner.assertTransferCount(PutElasticsearchRecord.REL_FAILED_RECORDS, 1);
        runner.assertTransferCount(PutElasticsearchRecord.REL_SUCCESSFUL_RECORDS, 1);
        runner.assertTransferCount(PutElasticsearchRecord.REL_ERROR_RESPONSES, 0);
        runner.getFlowFilesForRelationship(PutElasticsearchRecord.REL_FAILED_RECORDS).get(0).assertAttributeEquals(PutElasticsearchRecord.ATTR_RECORD_COUNT, String.valueOf(errorCount));
        runner.getFlowFilesForRelationship(PutElasticsearchRecord.REL_SUCCESSFUL_RECORDS).get(0).assertAttributeEquals(PutElasticsearchRecord.ATTR_RECORD_COUNT,
                String.valueOf(successCount));
    }

    @Test
    public void testFailedRecordsOutputGroupedByErrorType() throws Exception {
        runner.setProperty(PutElasticsearchRecord.NOT_FOUND_IS_SUCCESSFUL, "true");
        runner.setProperty(PutElasticsearchRecord.LOG_ERROR_RESPONSES, "true");
        runner.setProperty(PutElasticsearchRecord.GROUP_BULK_ERRORS_BY_TYPE, "true");
        final int successCount = 4;
        testErrorRelationship(3, successCount, true);

        runner.assertTransferCount(PutElasticsearchRecord.REL_FAILED_RECORDS, 2);
        runner.assertTransferCount(PutElasticsearchRecord.REL_SUCCESSFUL_RECORDS, 1);
        runner.assertTransferCount(PutElasticsearchRecord.REL_ERROR_RESPONSES, 0);

        runner.getFlowFilesForRelationship(PutElasticsearchRecord.REL_FAILED_RECORDS).get(0)
                .assertAttributeEquals(PutElasticsearchRecord.ATTR_RECORD_COUNT, "2");
        assertTrue(runner.getFlowFilesForRelationship(PutElasticsearchRecord.REL_FAILED_RECORDS).get(0)
                .getAttribute("elasticsearch.bulk.error").contains("mapper_parsing_exception"));
        runner.getFlowFilesForRelationship(PutElasticsearchRecord.REL_FAILED_RECORDS).get(1)
                .assertAttributeEquals(PutElasticsearchRecord.ATTR_RECORD_COUNT, "1");
        assertTrue(runner.getFlowFilesForRelationship(PutElasticsearchRecord.REL_FAILED_RECORDS).get(1)
                .getAttribute("elasticsearch.bulk.error").contains("some_other_exception"));
        runner.getFlowFilesForRelationship(PutElasticsearchRecord.REL_SUCCESSFUL_RECORDS).get(0)
                .assertAttributeEquals(PutElasticsearchRecord.ATTR_RECORD_COUNT, String.valueOf(successCount));
    }

    @Test
    public void testNotFoundResponsesTreatedAsFailedRecords() throws Exception {
        runner.setProperty(PutElasticsearchRecord.NOT_FOUND_IS_SUCCESSFUL, "false");
        runner.setProperty(PutElasticsearchRecord.GROUP_BULK_ERRORS_BY_TYPE, "false");
        final int errorCount = 4;
        final int successCount = 3;
        testErrorRelationship(errorCount, successCount, true);

        runner.assertTransferCount(PutElasticsearchRecord.REL_FAILED_RECORDS, 1);
        runner.assertTransferCount(PutElasticsearchRecord.REL_SUCCESSFUL_RECORDS, 1);
        runner.assertTransferCount(PutElasticsearchRecord.REL_ERROR_RESPONSES, 0);

        runner.getFlowFilesForRelationship(PutElasticsearchRecord.REL_FAILED_RECORDS).get(0)
                .assertAttributeEquals(PutElasticsearchRecord.ATTR_RECORD_COUNT, String.valueOf(errorCount));
        runner.getFlowFilesForRelationship(PutElasticsearchRecord.REL_SUCCESSFUL_RECORDS).get(0)
                .assertAttributeEquals(PutElasticsearchRecord.ATTR_RECORD_COUNT, String.valueOf(successCount));
    }

    @Test
    public void testNotFoundFailedRecordsGroupedAsErrorType() throws Exception {
        runner.setProperty(PutElasticsearchRecord.NOT_FOUND_IS_SUCCESSFUL, "false");
        runner.setProperty(PutElasticsearchRecord.GROUP_BULK_ERRORS_BY_TYPE, "true");
        final int errorCount = 4;
        final int successCount = 3;
        testErrorRelationship(errorCount, successCount, true);

        runner.assertTransferCount(PutElasticsearchRecord.REL_FAILED_RECORDS, 3);
        runner.assertTransferCount(PutElasticsearchRecord.REL_SUCCESSFUL_RECORDS, 1);
        runner.assertTransferCount(PutElasticsearchRecord.REL_ERROR_RESPONSES, 0);

        runner.getFlowFilesForRelationship(PutElasticsearchRecord.REL_FAILED_RECORDS).get(0)
                .assertAttributeEquals(PutElasticsearchRecord.ATTR_RECORD_COUNT, "2");
        assertTrue(runner.getFlowFilesForRelationship(PutElasticsearchRecord.REL_FAILED_RECORDS).get(0)
                .getAttribute("elasticsearch.bulk.error").contains("mapper_parsing_exception"));
        runner.getFlowFilesForRelationship(PutElasticsearchRecord.REL_FAILED_RECORDS).get(1)
                .assertAttributeEquals(PutElasticsearchRecord.ATTR_RECORD_COUNT, "1");
        assertTrue(runner.getFlowFilesForRelationship(PutElasticsearchRecord.REL_FAILED_RECORDS).get(1)
                .getAttribute("elasticsearch.bulk.error").contains("some_other_exception"));
        runner.getFlowFilesForRelationship(PutElasticsearchRecord.REL_FAILED_RECORDS).get(2)
                .assertAttributeEquals(PutElasticsearchRecord.ATTR_RECORD_COUNT, "1");
        assertTrue(runner.getFlowFilesForRelationship(PutElasticsearchRecord.REL_FAILED_RECORDS).get(2)
                .getAttribute("elasticsearch.bulk.error").contains("not_found"));
        runner.getFlowFilesForRelationship(PutElasticsearchRecord.REL_SUCCESSFUL_RECORDS).get(0)
                .assertAttributeEquals(PutElasticsearchRecord.ATTR_RECORD_COUNT, String.valueOf(successCount));
    }

    @Test
    public void testErrorsLoggedWithoutErrorRelationship() throws Exception {
        runner.setProperty(PutElasticsearchRecord.NOT_FOUND_IS_SUCCESSFUL, "false");
        runner.setProperty(PutElasticsearchRecord.OUTPUT_ERROR_RESPONSES, "true");
        runner.setProperty(PutElasticsearchRecord.GROUP_BULK_ERRORS_BY_TYPE, "false");
        final int errorCount = 4;
        final int successCount = 3;
        testErrorRelationship(errorCount, successCount, false);
        runner.assertTransferCount(PutElasticsearchRecord.REL_FAILED_RECORDS, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_SUCCESSFUL_RECORDS, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_ERROR_RESPONSES, 1);
        final String errorResponses = runner.getFlowFilesForRelationship(PutElasticsearchJson.REL_ERROR_RESPONSES).get(0).getContent();
        assertTrue(errorResponses.contains("not_found"));
        assertTrue(errorResponses.contains("For input string: 20abc"));
        assertTrue(errorResponses.contains("For input string: 213,456.9"));
        assertTrue(errorResponses.contains("For input string: unit test"));
    }

    @Test
    public void testInvalidBulkHeaderProperty() {
        runner.assertValid();
        runner.setProperty(AbstractPutElasticsearch.BULK_HEADER_PREFIX + "routing", "not-record-path");
        runner.assertNotValid();
    }

    private static RecordSchema getRecordSchema(final Path schema) throws IOException {
        return AvroTypeUtil.createSchema(new Schema.Parser().parse(JsonUtils.readString(schema)));
    }

    private void testErrorRelationship(final int errorCount, final int successCount, final boolean recordWriter) throws Exception {
        final String schemaName = "errorTest";
        final JsonRecordSetWriter writer = new JsonRecordSetWriter();
        runner.addControllerService("writer", writer);
        runner.setProperty(writer, SchemaAccessUtils.SCHEMA_ACCESS_STRATEGY, SchemaAccessUtils.SCHEMA_NAME_PROPERTY);
        runner.setProperty(writer, SchemaAccessUtils.SCHEMA_REGISTRY, "registry");
        runner.enableControllerService(writer);
        clientService.setResponse(IndexOperationResponse.fromJsonResponse(JsonUtils.readString(Paths.get(TEST_COMMON_DIR, "sampleErrorResponse.json"))));
        registry.addSchema(schemaName, errorTestSchema);

        if (recordWriter) {
            runner.setProperty(PutElasticsearchRecord.RESULT_RECORD_WRITER, "writer");
        }

        runner.assertValid();

        runner.enqueue(VALUES, Collections.singletonMap(SCHEMA_NAME_ATTRIBUTE, schemaName));
        runner.run();

        runner.assertTransferCount(PutElasticsearchRecord.REL_SUCCESS, 1);
        runner.assertTransferCount(PutElasticsearchRecord.REL_FAILURE, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_RETRY, 0);

        assertEquals(1,
                runner.getProvenanceEvents().stream().filter(e -> ProvenanceEventType.SEND.equals(e.getEventType())
                        && String.format("1 Elasticsearch _bulk operation batch(es) [%s error(s), %s success(es)]", errorCount, successCount).equals(e.getDetails())).count());
    }

    private void testInvalidELRecordPaths(final String idRecordPath, final String atTimestampRecordPath, final Path path, final Map<String, String> attributes) throws IOException {
        clientService.setEvalConsumer((final List<IndexOperationRequest> items) -> {
            final long nullIdCount = items.stream().filter(item ->  item.getId() == null).count();
            final long noTimestampCount = items.stream().filter(item -> !item.getFields().containsKey("@timestamp")).count();
            assertEquals(1, nullIdCount, getUnexpectedCountMsg("null id"));
            assertEquals(1, noTimestampCount, getUnexpectedCountMsg("noTimestamp"));
        });

        registry.addSchema(RECORD_PATH_TEST_SCHEMA, recordPathTestSchema);
        runner.removeProperty(PutElasticsearchRecord.AT_TIMESTAMP);
        runner.setProperty(PutElasticsearchRecord.ID_RECORD_PATH, idRecordPath);
        runner.setProperty(PutElasticsearchRecord.AT_TIMESTAMP_RECORD_PATH, atTimestampRecordPath);
        runner.enqueue(path, attributes);
        runner.run();
        runner.assertTransferCount(PutElasticsearchRecord.REL_SUCCESS, 1);
        runner.assertTransferCount(PutElasticsearchRecord.REL_FAILURE, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_RETRY, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_FAILED_RECORDS, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_SUCCESSFUL_RECORDS, 0);
        runner.assertTransferCount(PutElasticsearchRecord.REL_ERROR_RESPONSES, 0);
    }

    private void testWithRequestParametersAndBulkHeaders(final Map<String, String> attributes) {
        runner.setProperty("refresh", "true");
        runner.setProperty("slices", "${slices}");
        runner.setProperty(AbstractPutElasticsearch.BULK_HEADER_PREFIX + "routing", "/routing");
        runner.setProperty(AbstractPutElasticsearch.BULK_HEADER_PREFIX + "version", "${version}");
        runner.setProperty(AbstractPutElasticsearch.BULK_HEADER_PREFIX + "empty", "${empty}");
        runner.setVariable("blank", " ");
        runner.assertValid();

        clientService.setEvalParametersConsumer( (final Map<String, String> params) -> {
            assertEquals(2, params.size());
            assertEquals("true", params.get("refresh"));
            assertEquals("auto", params.get("slices"));
        });

        final Consumer<List<IndexOperationRequest>> consumer = (final List<IndexOperationRequest> items) -> {
            final long headerFieldsCount = items.stream().filter(item -> !item.getHeaderFields().isEmpty()).count();
            final long routingCount = items.stream().filter(item -> "1".equals(item.getHeaderFields().get("routing"))).count();
            final long versionCount = items.stream().filter(item -> "external".equals(item.getHeaderFields().get("version"))).count();
            assertEquals(2, headerFieldsCount);
            assertEquals(1, routingCount);
            assertEquals(1, versionCount);
        };

        basicTest(0, 0, 1, consumer, attributes);
    }

    private String getDateTimeFormattingJson() throws Exception {
        final String json = JsonUtils.readString(Paths.get(TEST_DIR, "10_flowFileContents.json"));
        final List<Map<String, Object>> parsedJson = JsonUtils.readListOfMaps(json);
        parsedJson.forEach(msg -> {
            msg.computeIfPresent("ts", (key, val) -> Timestamp.valueOf(LOCAL_DATE_TIME).toInstant().toEpochMilli());
            msg.computeIfPresent("date", (key, val) -> Date.valueOf(LOCAL_DATE).getTime());
            msg.computeIfPresent("time", (key, val) -> Time.valueOf(LOCAL_TIME).getTime());
            msg.computeIfPresent("choice_ts", (key, val) -> Timestamp.valueOf(LOCAL_DATE_TIME).toInstant().toEpochMilli());
        });
        parsedJson.get(parsedJson.size() - 1).computeIfPresent("choice_ts", (key, val) -> "not-timestamp");
        return JsonUtils.prettyPrint(parsedJson);
    }
}
