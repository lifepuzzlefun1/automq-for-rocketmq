/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.automq.stream.s3.compact;

import com.automq.stream.s3.Config;
import com.automq.stream.s3.DataBlockIndex;
import com.automq.stream.s3.ObjectWriter;
import com.automq.stream.s3.StreamDataBlock;
import com.automq.stream.s3.TestUtils;
import com.automq.stream.s3.compact.operator.DataBlockReader;
import com.automq.stream.s3.compact.utils.CompactionUtils;
import com.automq.stream.s3.metadata.S3ObjectMetadata;
import com.automq.stream.s3.metadata.S3ObjectType;
import com.automq.stream.s3.metadata.S3StreamConstant;
import com.automq.stream.s3.metadata.StreamMetadata;
import com.automq.stream.s3.metadata.StreamOffsetRange;
import com.automq.stream.s3.metadata.StreamState;
import com.automq.stream.s3.model.StreamRecordBatch;
import com.automq.stream.s3.objects.CommitStreamSetObjectRequest;
import com.automq.stream.s3.objects.ObjectStreamRange;
import com.automq.stream.s3.objects.StreamObject;
import com.automq.stream.s3.operator.DefaultS3Operator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@Timeout(60)
@Tag("S3Unit")
public class CompactionManagerTest extends CompactionTestBase {
    private static final int BROKER0 = 0;
    private CompactionAnalyzer compactionAnalyzer;
    private CompactionManager compactionManager;
    private Config config;

    private static Map<Long, List<StreamDataBlock>> getStreamDataBlockMap() {
        StreamDataBlock block1 = new StreamDataBlock(OBJECT_0, new DataBlockIndex(0, 0, 15, 15, 0, 15));
        StreamDataBlock block2 = new StreamDataBlock(OBJECT_0, new DataBlockIndex(1, 0, 20, 20, 15, 50));

        StreamDataBlock block3 = new StreamDataBlock(OBJECT_1, new DataBlockIndex(0, 15, 12, 12, 0, 20));
        StreamDataBlock block4 = new StreamDataBlock(OBJECT_1, new DataBlockIndex(1, 20, 25, 25, 20, 60));

        StreamDataBlock block5 = new StreamDataBlock(OBJECT_2, new DataBlockIndex(0, 27, 13, 20, 0, 20));
        StreamDataBlock block6 = new StreamDataBlock(OBJECT_2, new DataBlockIndex(3, 0, 30, 30, 20, 30));
        return Map.of(
            OBJECT_0, List.of(
                block1,
                block2
            ),
            OBJECT_1, List.of(
                block3,
                block4
            ),
            OBJECT_2, List.of(
                block5,
                block6
            )
        );
    }

    @BeforeEach
    public void setUp() throws Exception {
        super.setUp();
        config = Mockito.mock(Config.class);
        when(config.nodeId()).thenReturn(BROKER0);
        when(config.streamSetObjectCompactionUploadConcurrency()).thenReturn(3);
        when(config.objectPartSize()).thenReturn(100);
        when(config.streamSetObjectCompactionCacheSize()).thenReturn(300L);
        when(config.streamSetObjectCompactionStreamSplitSize()).thenReturn(100L);
        when(config.streamSetObjectCompactionForceSplitPeriod()).thenReturn(120);
        when(config.streamSetObjectCompactionMaxObjectNum()).thenReturn(100);
        when(config.maxStreamNumPerStreamSetObject()).thenReturn(100);
        when(config.maxStreamObjectNumPerCommit()).thenReturn(100);
//        when(config.networkInboundBaselineBandwidth()).thenReturn(1000L);
        compactionAnalyzer = new CompactionAnalyzer(config.streamSetObjectCompactionCacheSize(), config.streamSetObjectCompactionStreamSplitSize(),
            config.maxStreamNumPerStreamSetObject(), config.maxStreamObjectNumPerCommit());
    }

    @AfterEach
    public void tearDown() {
        super.tearDown();
        if (compactionManager != null) {
            compactionManager.shutdown();
        }
    }

    @Test
    public void testForceSplit() {
        List<StreamMetadata> streamMetadataList = this.streamManager.getStreams(Collections.emptyList()).join();
        List<S3ObjectMetadata> s3ObjectMetadata = this.objectManager.getServerObjects().join();
        when(config.streamSetObjectCompactionForceSplitPeriod()).thenReturn(0);
        compactionManager = new CompactionManager(config, objectManager, streamManager, s3Operator);

        CommitStreamSetObjectRequest request = compactionManager.buildSplitRequest(streamMetadataList, s3ObjectMetadata.get(0));
        Assertions.assertEquals(-1, request.getObjectId());
        Assertions.assertEquals(List.of(OBJECT_0), request.getCompactedObjectIds());
        Assertions.assertEquals(3, request.getStreamObjects().size());
        Assertions.assertTrue(checkDataIntegrity(streamMetadataList, Collections.singletonList(s3ObjectMetadata.get(0)), request));

        request = compactionManager.buildSplitRequest(streamMetadataList, s3ObjectMetadata.get(1));
        Assertions.assertEquals(-1, request.getObjectId());
        Assertions.assertEquals(List.of(OBJECT_1), request.getCompactedObjectIds());
        Assertions.assertEquals(2, request.getStreamObjects().size());
        Assertions.assertTrue(checkDataIntegrity(streamMetadataList, Collections.singletonList(s3ObjectMetadata.get(1)), request));

        request = compactionManager.buildSplitRequest(streamMetadataList, s3ObjectMetadata.get(2));
        Assertions.assertEquals(-1, request.getObjectId());
        Assertions.assertEquals(List.of(OBJECT_2), request.getCompactedObjectIds());
        Assertions.assertEquals(2, request.getStreamObjects().size());
        Assertions.assertTrue(checkDataIntegrity(streamMetadataList, Collections.singletonList(s3ObjectMetadata.get(2)), request));
    }

    @Test
    public void testForceSplitWithOutDatedObject() {
        when(streamManager.getStreams(Collections.emptyList())).thenReturn(CompletableFuture.completedFuture(
            List.of(new StreamMetadata(STREAM_0, 0, 999, 9999, StreamState.OPENED),
                new StreamMetadata(STREAM_1, 0, 999, 9999, StreamState.OPENED),
                new StreamMetadata(STREAM_2, 0, 999, 9999, StreamState.OPENED))));

        List<StreamMetadata> streamMetadataList = this.streamManager.getStreams(Collections.emptyList()).join();
        List<S3ObjectMetadata> s3ObjectMetadata = this.objectManager.getServerObjects().join();
        when(config.streamSetObjectCompactionForceSplitPeriod()).thenReturn(0);
        compactionManager = new CompactionManager(config, objectManager, streamManager, s3Operator);

        CommitStreamSetObjectRequest request = compactionManager.buildSplitRequest(streamMetadataList, s3ObjectMetadata.get(0));
        Assertions.assertEquals(-1, request.getObjectId());
        Assertions.assertEquals(List.of(OBJECT_0), request.getCompactedObjectIds());
        Assertions.assertTrue(request.getStreamObjects().isEmpty());
        Assertions.assertTrue(request.getStreamRanges().isEmpty());

        request = compactionManager.buildSplitRequest(streamMetadataList, s3ObjectMetadata.get(1));
        Assertions.assertEquals(-1, request.getObjectId());
        Assertions.assertEquals(List.of(OBJECT_1), request.getCompactedObjectIds());
        Assertions.assertTrue(request.getStreamObjects().isEmpty());
        Assertions.assertTrue(request.getStreamRanges().isEmpty());

        request = compactionManager.buildSplitRequest(streamMetadataList, s3ObjectMetadata.get(2));
        Assertions.assertEquals(-1, request.getObjectId());
        Assertions.assertEquals(List.of(OBJECT_2), request.getCompactedObjectIds());
        Assertions.assertTrue(request.getStreamObjects().isEmpty());
        Assertions.assertTrue(request.getStreamRanges().isEmpty());
    }

    @Test
    public void testForceSplitWithException() {
        S3AsyncClient s3AsyncClient = Mockito.mock(S3AsyncClient.class);
        doAnswer(invocation -> CompletableFuture.completedFuture(null)).when(s3AsyncClient).putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class));

        Map<Long, List<StreamDataBlock>> streamDataBlockMap = getStreamDataBlockMap();
        S3ObjectMetadata objectMetadata = new S3ObjectMetadata(OBJECT_0, 0, S3ObjectType.STREAM_SET);
        DefaultS3Operator s3Operator = Mockito.spy(new DefaultS3Operator(s3AsyncClient, ""));
        doReturn(CompletableFuture.failedFuture(new IllegalArgumentException("exception"))).when(s3Operator).rangeRead(eq(objectMetadata.key()), anyLong(), anyLong(), any());

        CompactionManager compactionManager = new CompactionManager(config, objectManager, streamManager, s3Operator);
        Assertions.assertThrowsExactly(CompletionException.class, () -> compactionManager.groupAndSplitStreamDataBlocks(objectMetadata, streamDataBlockMap.get(OBJECT_0)));
    }

    @Test
    public void testForceSplitWithLimit() {
        when(config.streamSetObjectCompactionCacheSize()).thenReturn(5L);
        List<S3ObjectMetadata> s3ObjectMetadata = this.objectManager.getServerObjects().join();
        when(config.streamSetObjectCompactionForceSplitPeriod()).thenReturn(0);
        compactionManager = new CompactionManager(config, objectManager, streamManager, s3Operator);

        List<StreamMetadata> streamMetadataList = this.streamManager.getStreams(Collections.emptyList()).join();
        CommitStreamSetObjectRequest request = compactionManager.buildSplitRequest(streamMetadataList, s3ObjectMetadata.get(0));
        Assertions.assertNull(request);
    }

    @Test
    public void testForceSplitWithLimit2() {
        when(config.streamSetObjectCompactionCacheSize()).thenReturn(150L);
        List<S3ObjectMetadata> s3ObjectMetadata = this.objectManager.getServerObjects().join();
        when(config.streamSetObjectCompactionForceSplitPeriod()).thenReturn(0);
        compactionManager = new CompactionManager(config, objectManager, streamManager, s3Operator);

        List<StreamMetadata> streamMetadataList = this.streamManager.getStreams(Collections.emptyList()).join();
        CommitStreamSetObjectRequest request = compactionManager.buildSplitRequest(streamMetadataList, s3ObjectMetadata.get(0));
        Assertions.assertEquals(-1, request.getObjectId());
        Assertions.assertEquals(List.of(OBJECT_0), request.getCompactedObjectIds());
        Assertions.assertEquals(3, request.getStreamObjects().size());
        Assertions.assertTrue(checkDataIntegrity(streamMetadataList, Collections.singletonList(s3ObjectMetadata.get(0)), request));
    }

    @Test
    public void testCompact() {
        List<S3ObjectMetadata> s3ObjectMetadata = this.objectManager.getServerObjects().join();
        compactionManager = new CompactionManager(config, objectManager, streamManager, s3Operator);
        List<StreamMetadata> streamMetadataList = this.streamManager.getStreams(Collections.emptyList()).join();
        CommitStreamSetObjectRequest request = compactionManager.buildCompactRequest(streamMetadataList, s3ObjectMetadata);

        assertEquals(List.of(OBJECT_0, OBJECT_1, OBJECT_2), request.getCompactedObjectIds());
        assertEquals(OBJECT_0, request.getOrderId());
        assertTrue(request.getObjectId() > OBJECT_2);
        request.getStreamObjects().forEach(s -> assertTrue(s.getObjectId() > OBJECT_2));
        assertEquals(3, request.getStreamObjects().size());
        assertEquals(2, request.getStreamRanges().size());

        Assertions.assertTrue(checkDataIntegrity(streamMetadataList, s3ObjectMetadata, request));
    }

    @Test
    public void testCompactWithDataTrimmed() {
        when(streamManager.getStreams(Collections.emptyList())).thenReturn(CompletableFuture.completedFuture(
            List.of(new StreamMetadata(STREAM_0, 0, 5, 20, StreamState.OPENED),
                new StreamMetadata(STREAM_1, 0, 25, 500, StreamState.OPENED),
                new StreamMetadata(STREAM_2, 0, 30, 270, StreamState.OPENED))));
        compactionManager = new CompactionManager(config, objectManager, streamManager, s3Operator);
        List<StreamMetadata> streamMetadataList = this.streamManager.getStreams(Collections.emptyList()).join();
        CommitStreamSetObjectRequest request = compactionManager.buildCompactRequest(streamMetadataList, S3_WAL_OBJECT_METADATA_LIST);

        assertEquals(List.of(OBJECT_0, OBJECT_1, OBJECT_2), request.getCompactedObjectIds());
        assertEquals(OBJECT_0, request.getOrderId());
        assertTrue(request.getObjectId() > OBJECT_2);
        request.getStreamObjects().forEach(s -> assertTrue(s.getObjectId() > OBJECT_2));
        assertEquals(3, request.getStreamObjects().size());
        assertEquals(2, request.getStreamRanges().size());

        Assertions.assertTrue(checkDataIntegrity(streamMetadataList, S3_WAL_OBJECT_METADATA_LIST, request));
    }

    @Test
    public void testCompactWithDataTrimmed2() {
        when(streamManager.getStreams(Collections.emptyList())).thenReturn(CompletableFuture.completedFuture(
            List.of(new StreamMetadata(STREAM_0, 0, 15, 20, StreamState.OPENED),
                new StreamMetadata(STREAM_1, 0, 25, 500, StreamState.OPENED),
                new StreamMetadata(STREAM_2, 0, 30, 270, StreamState.OPENED))));
        compactionManager = new CompactionManager(config, objectManager, streamManager, s3Operator);
        List<StreamMetadata> streamMetadataList = this.streamManager.getStreams(Collections.emptyList()).join();
        CommitStreamSetObjectRequest request = compactionManager.buildCompactRequest(streamMetadataList, S3_WAL_OBJECT_METADATA_LIST);

        assertEquals(List.of(OBJECT_0, OBJECT_1, OBJECT_2), request.getCompactedObjectIds());
        assertEquals(OBJECT_0, request.getOrderId());
        assertTrue(request.getObjectId() > OBJECT_2);
        request.getStreamObjects().forEach(s -> assertTrue(s.getObjectId() > OBJECT_2));
        assertEquals(3, request.getStreamObjects().size());
        assertEquals(2, request.getStreamRanges().size());

        Assertions.assertTrue(checkDataIntegrity(streamMetadataList, S3_WAL_OBJECT_METADATA_LIST, request));
    }

    @Test
    public void testCompactionWithDataTrimmed3() {
        objectManager.prepareObject(1, TimeUnit.MINUTES.toMillis(30)).thenAccept(objectId -> {
            assertEquals(OBJECT_3, objectId);
            ObjectWriter objectWriter = ObjectWriter.writer(OBJECT_3, s3Operator, 1024, 1024);
            StreamRecordBatch r1 = new StreamRecordBatch(STREAM_1, 0, 500, 20, TestUtils.random(20));
            StreamRecordBatch r2 = new StreamRecordBatch(STREAM_3, 0, 0, 10, TestUtils.random(1024));
            StreamRecordBatch r3 = new StreamRecordBatch(STREAM_3, 0, 10, 10, TestUtils.random(1024));
            objectWriter.write(STREAM_1, List.of(r1));
            objectWriter.write(STREAM_3, List.of(r2, r3));
            objectWriter.close().join();
            List<StreamOffsetRange> streamsIndices = List.of(
                new StreamOffsetRange(STREAM_1, 500, 520),
                new StreamOffsetRange(STREAM_3, 0, 20)
            );
            S3ObjectMetadata objectMetadata = new S3ObjectMetadata(OBJECT_3, S3ObjectType.STREAM_SET, streamsIndices, System.currentTimeMillis(),
                System.currentTimeMillis(), objectWriter.size(), OBJECT_3);
            S3_WAL_OBJECT_METADATA_LIST.add(objectMetadata);
            List.of(r1, r2, r3).forEach(StreamRecordBatch::release);
        }).join();
        when(streamManager.getStreams(Collections.emptyList())).thenReturn(CompletableFuture.completedFuture(
            List.of(new StreamMetadata(STREAM_0, 0, 0, 20, StreamState.OPENED),
                new StreamMetadata(STREAM_1, 0, 25, 500, StreamState.OPENED),
                new StreamMetadata(STREAM_2, 0, 30, 270, StreamState.OPENED),
                new StreamMetadata(STREAM_3, 0, 10, 20, StreamState.OPENED))));
        compactionManager = new CompactionManager(config, objectManager, streamManager, s3Operator);
        List<StreamMetadata> streamMetadataList = this.streamManager.getStreams(Collections.emptyList()).join();
        CommitStreamSetObjectRequest request = compactionManager.buildCompactRequest(streamMetadataList, S3_WAL_OBJECT_METADATA_LIST);
        assertNull(request);
    }

    @Test
    public void testCompactionWithDataTrimmed4() {
        objectManager.prepareObject(1, TimeUnit.MINUTES.toMillis(30)).thenAccept(objectId -> {
            assertEquals(OBJECT_3, objectId);
            ObjectWriter objectWriter = ObjectWriter.writer(OBJECT_3, s3Operator, 200, 1024);
            StreamRecordBatch r1 = new StreamRecordBatch(STREAM_1, 0, 500, 20, TestUtils.random(20));
            StreamRecordBatch r2 = new StreamRecordBatch(STREAM_3, 0, 0, 10, TestUtils.random(200));
            StreamRecordBatch r3 = new StreamRecordBatch(STREAM_3, 0, 10, 10, TestUtils.random(200));
            objectWriter.write(STREAM_1, List.of(r1));
            objectWriter.write(STREAM_3, List.of(r2, r3));
            objectWriter.close().join();
            List<StreamOffsetRange> streamsIndices = List.of(
                new StreamOffsetRange(STREAM_1, 500, 520),
                new StreamOffsetRange(STREAM_3, 0, 20)
            );
            S3ObjectMetadata objectMetadata = new S3ObjectMetadata(OBJECT_3, S3ObjectType.STREAM_SET, streamsIndices, System.currentTimeMillis(),
                System.currentTimeMillis(), objectWriter.size(), OBJECT_3);
            S3_WAL_OBJECT_METADATA_LIST.add(objectMetadata);
            List.of(r1, r2, r3).forEach(StreamRecordBatch::release);
        }).join();
        when(streamManager.getStreams(Collections.emptyList())).thenReturn(CompletableFuture.completedFuture(
            List.of(new StreamMetadata(STREAM_0, 0, 0, 20, StreamState.OPENED),
                new StreamMetadata(STREAM_1, 0, 25, 500, StreamState.OPENED),
                new StreamMetadata(STREAM_2, 0, 30, 270, StreamState.OPENED),
                new StreamMetadata(STREAM_3, 0, 10, 20, StreamState.OPENED))));
        compactionManager = new CompactionManager(config, objectManager, streamManager, s3Operator);
        List<StreamMetadata> streamMetadataList = this.streamManager.getStreams(Collections.emptyList()).join();
        CommitStreamSetObjectRequest request = compactionManager.buildCompactRequest(streamMetadataList, S3_WAL_OBJECT_METADATA_LIST);

        assertEquals(List.of(OBJECT_0, OBJECT_1, OBJECT_2, OBJECT_3), request.getCompactedObjectIds());
        assertEquals(OBJECT_0, request.getOrderId());
        assertTrue(request.getObjectId() > OBJECT_3);
        request.getStreamObjects().forEach(s -> assertTrue(s.getObjectId() > OBJECT_3));
        assertEquals(4, request.getStreamObjects().size());
        assertEquals(2, request.getStreamRanges().size());

        Assertions.assertTrue(checkDataIntegrity(streamMetadataList, S3_WAL_OBJECT_METADATA_LIST, request));
    }

    @Test
    public void testCompactWithOutdatedObject() {
        when(streamManager.getStreams(Collections.emptyList())).thenReturn(CompletableFuture.completedFuture(
            List.of(new StreamMetadata(STREAM_0, 0, 15, 20, StreamState.OPENED),
                new StreamMetadata(STREAM_1, 0, 60, 500, StreamState.OPENED),
                new StreamMetadata(STREAM_2, 0, 60, 270, StreamState.OPENED))));
        compactionManager = new CompactionManager(config, objectManager, streamManager, s3Operator);
        List<StreamMetadata> streamMetadataList = this.streamManager.getStreams(Collections.emptyList()).join();
        CommitStreamSetObjectRequest request = compactionManager.buildCompactRequest(streamMetadataList, S3_WAL_OBJECT_METADATA_LIST);

        assertEquals(List.of(OBJECT_0, OBJECT_1, OBJECT_2), request.getCompactedObjectIds());
        assertEquals(OBJECT_0, request.getOrderId());
        assertTrue(request.getObjectId() > OBJECT_2);
        request.getStreamObjects().forEach(s -> assertTrue(s.getObjectId() > OBJECT_2));
        assertEquals(2, request.getStreamObjects().size());
        assertEquals(2, request.getStreamRanges().size());

        Assertions.assertTrue(checkDataIntegrity(streamMetadataList, S3_WAL_OBJECT_METADATA_LIST, request));
    }

    @Test
    public void testCompactWithNonExistStream() {
        when(streamManager.getStreams(Collections.emptyList())).thenReturn(CompletableFuture.completedFuture(
            List.of(new StreamMetadata(STREAM_1, 0, 25, 500, StreamState.OPENED),
                new StreamMetadata(STREAM_2, 0, 30, 270, StreamState.OPENED))));
        compactionManager = new CompactionManager(config, objectManager, streamManager, s3Operator);
        List<StreamMetadata> streamMetadataList = this.streamManager.getStreams(Collections.emptyList()).join();
        CommitStreamSetObjectRequest request = compactionManager.buildCompactRequest(streamMetadataList, S3_WAL_OBJECT_METADATA_LIST);

        Set<Long> streamIds = request.getStreamObjects().stream().map(StreamObject::getStreamId).collect(Collectors.toSet());
        streamIds.addAll(request.getStreamRanges().stream().map(ObjectStreamRange::getStreamId).collect(Collectors.toSet()));
        assertEquals(Set.of(STREAM_1, STREAM_2), streamIds);
        assertEquals(List.of(OBJECT_0, OBJECT_1, OBJECT_2), request.getCompactedObjectIds());
        assertEquals(OBJECT_0, request.getOrderId());
        assertTrue(request.getObjectId() > OBJECT_2);
        request.getStreamObjects().forEach(s -> assertTrue(s.getObjectId() > OBJECT_2));
        assertEquals(3, request.getStreamObjects().size());
        assertEquals(1, request.getStreamRanges().size());

        Assertions.assertTrue(checkDataIntegrity(streamMetadataList, S3_WAL_OBJECT_METADATA_LIST, request));
    }

    @Test
    public void testCompactNoneExistObjects() {
        when(config.streamSetObjectCompactionStreamSplitSize()).thenReturn(100L);
        when(config.streamSetObjectCompactionCacheSize()).thenReturn(9999L);
        Map<Long, List<StreamDataBlock>> streamDataBlockMap = getStreamDataBlockMap();
        S3ObjectMetadata objectMetadata0 = new S3ObjectMetadata(OBJECT_0, 0, S3ObjectType.STREAM_SET);
        S3ObjectMetadata objectMetadata1 = new S3ObjectMetadata(OBJECT_1, 0, S3ObjectType.STREAM_SET);
        S3ObjectMetadata objectMetadata2 = new S3ObjectMetadata(OBJECT_2, 0, S3ObjectType.STREAM_SET);
        List<S3ObjectMetadata> s3ObjectMetadata = List.of(objectMetadata0, objectMetadata1, objectMetadata2);
        this.compactionAnalyzer = new CompactionAnalyzer(config.streamSetObjectCompactionCacheSize(), config.streamSetObjectCompactionStreamSplitSize(),
            config.maxStreamNumPerStreamSetObject(), config.maxStreamObjectNumPerCommit());
        List<CompactionPlan> compactionPlans = this.compactionAnalyzer.analyze(streamDataBlockMap, new HashSet<>());
        CommitStreamSetObjectRequest request = new CommitStreamSetObjectRequest();

        S3AsyncClient s3AsyncClient = Mockito.mock(S3AsyncClient.class);
        doAnswer(invocation -> CompletableFuture.completedFuture(null)).when(s3AsyncClient).putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class));

        DefaultS3Operator s3Operator = Mockito.spy(new DefaultS3Operator(s3AsyncClient, ""));
        doAnswer(invocation -> CompletableFuture.completedFuture(TestUtils.randomPooled(65))).when(s3Operator).rangeRead(eq(objectMetadata0.key()), anyLong(), anyLong(), any());
        doAnswer(invocation -> CompletableFuture.completedFuture(TestUtils.randomPooled(80))).when(s3Operator).rangeRead(eq(objectMetadata1.key()), anyLong(), anyLong(), any());
        doAnswer(invocation -> CompletableFuture.failedFuture(new IllegalArgumentException("exception"))).when(s3Operator).rangeRead(eq(objectMetadata2.key()), anyLong(), anyLong(), any());

        CompactionManager compactionManager = new CompactionManager(config, objectManager, streamManager, s3Operator);
        Assertions.assertThrowsExactly(CompletionException.class,
            () -> compactionManager.executeCompactionPlans(request, compactionPlans, s3ObjectMetadata));
        for (CompactionPlan plan : compactionPlans) {
            plan.streamDataBlocksMap().forEach((streamId, blocks) -> blocks.forEach(block -> {
                if (block.getObjectId() != OBJECT_2) {
                    block.getDataCf().thenAccept(data -> {
                        Assertions.assertEquals(0, data.refCnt());
                    }).join();
                }
            }));
        }
    }

    @Test
    public void testCompactNoneExistObjects2() {
        when(config.streamSetObjectCompactionStreamSplitSize()).thenReturn(100L);
        when(config.streamSetObjectCompactionCacheSize()).thenReturn(9999L);
        Map<Long, List<StreamDataBlock>> streamDataBlockMap = getStreamDataBlockMap();
        S3ObjectMetadata objectMetadata0 = new S3ObjectMetadata(OBJECT_0, 0, S3ObjectType.STREAM_SET);
        S3ObjectMetadata objectMetadata1 = new S3ObjectMetadata(OBJECT_1, 0, S3ObjectType.STREAM_SET);
        S3ObjectMetadata objectMetadata2 = new S3ObjectMetadata(OBJECT_2, 0, S3ObjectType.STREAM_SET);
        List<S3ObjectMetadata> s3ObjectMetadata = List.of(objectMetadata0, objectMetadata1, objectMetadata2);
        this.compactionAnalyzer = new CompactionAnalyzer(config.streamSetObjectCompactionCacheSize(), config.streamSetObjectCompactionStreamSplitSize(),
            config.maxStreamNumPerStreamSetObject(), config.maxStreamObjectNumPerCommit());
        List<CompactionPlan> compactionPlans = this.compactionAnalyzer.analyze(streamDataBlockMap, new HashSet<>());
        CommitStreamSetObjectRequest request = new CommitStreamSetObjectRequest();

        S3AsyncClient s3AsyncClient = Mockito.mock(S3AsyncClient.class);
        doAnswer(invocation -> CompletableFuture.completedFuture(null)).when(s3AsyncClient).putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class));

        DefaultS3Operator s3Operator = Mockito.spy(new DefaultS3Operator(s3AsyncClient, ""));
        doAnswer(invocation -> CompletableFuture.completedFuture(TestUtils.randomPooled(65))).when(s3Operator).rangeRead(eq(objectMetadata0.key()), anyLong(), anyLong(), any());
        doAnswer(invocation -> CompletableFuture.failedFuture(new IllegalArgumentException("exception"))).when(s3Operator).rangeRead(eq(objectMetadata1.key()), anyLong(), anyLong(), any());
        doAnswer(invocation -> CompletableFuture.completedFuture(TestUtils.randomPooled(50))).when(s3Operator).rangeRead(eq(objectMetadata2.key()), anyLong(), anyLong(), any());

        CompactionManager compactionManager = new CompactionManager(config, objectManager, streamManager, s3Operator);
        Assertions.assertThrowsExactly(CompletionException.class,
            () -> compactionManager.executeCompactionPlans(request, compactionPlans, s3ObjectMetadata));
        for (CompactionPlan plan : compactionPlans) {
            plan.streamDataBlocksMap().forEach((streamId, blocks) -> blocks.forEach(block -> {
                if (block.getObjectId() != OBJECT_1) {
                    block.getDataCf().thenAccept(data -> {
                        Assertions.assertEquals(0, data.refCnt());
                    }).join();
                }
            }));
        }
    }

    @Test
    public void testCompactWithLimit() {
        when(config.streamSetObjectCompactionStreamSplitSize()).thenReturn(70L);
        when(config.maxStreamNumPerStreamSetObject()).thenReturn(MAX_STREAM_NUM_IN_WAL);
        when(config.maxStreamObjectNumPerCommit()).thenReturn(4);
        List<S3ObjectMetadata> s3ObjectMetadata = this.objectManager.getServerObjects().join();
        compactionManager = new CompactionManager(config, objectManager, streamManager, s3Operator);
        List<StreamMetadata> streamMetadataList = this.streamManager.getStreams(Collections.emptyList()).join();
        CommitStreamSetObjectRequest request = compactionManager.buildCompactRequest(streamMetadataList, s3ObjectMetadata);

        assertEquals(List.of(OBJECT_0, OBJECT_1), request.getCompactedObjectIds());
        assertEquals(OBJECT_0, request.getOrderId());
        assertTrue(request.getObjectId() > OBJECT_2);
        request.getStreamObjects().forEach(s -> assertTrue(s.getObjectId() > OBJECT_2));
        assertEquals(2, request.getStreamObjects().size());
        assertEquals(1, request.getStreamRanges().size());

        Set<Long> compactedObjectIds = new HashSet<>(request.getCompactedObjectIds());
        s3ObjectMetadata = s3ObjectMetadata.stream().filter(s -> compactedObjectIds.contains(s.objectId())).toList();
        Assertions.assertTrue(checkDataIntegrity(streamMetadataList, s3ObjectMetadata, request));
    }

    private boolean checkDataIntegrity(List<StreamMetadata> streamMetadataList, List<S3ObjectMetadata> s3ObjectMetadata,
        CommitStreamSetObjectRequest request) {
        Map<Long, S3ObjectMetadata> s3WALObjectMetadataMap = s3ObjectMetadata.stream()
            .collect(Collectors.toMap(S3ObjectMetadata::objectId, e -> e));
        Map<Long, List<StreamDataBlock>> streamDataBlocks = CompactionUtils.blockWaitObjectIndices(streamMetadataList, s3ObjectMetadata, s3Operator);
        for (Map.Entry<Long, List<StreamDataBlock>> entry : streamDataBlocks.entrySet()) {
            long objectId = entry.getKey();
            DataBlockReader reader = new DataBlockReader(new S3ObjectMetadata(objectId,
                s3WALObjectMetadataMap.get(objectId).objectSize(), S3ObjectType.STREAM_SET), s3Operator);
            reader.readBlocks(entry.getValue());
        }

        Map<Long, S3ObjectMetadata> compactedObjectMap = new HashMap<>();
        for (StreamObject streamObject : request.getStreamObjects()) {
            S3ObjectMetadata objectMetadata = new S3ObjectMetadata(streamObject.getObjectId(), S3ObjectType.STREAM,
                List.of(new StreamOffsetRange(streamObject.getStreamId(), streamObject.getStartOffset(), streamObject.getEndOffset())),
                System.currentTimeMillis(), System.currentTimeMillis(), streamObject.getObjectSize(), S3StreamConstant.INVALID_ORDER_ID);
            compactedObjectMap.put(streamObject.getObjectId(), objectMetadata);
        }
        List<StreamOffsetRange> streamOffsetRanges = new ArrayList<>();
        for (ObjectStreamRange objectStreamRange : request.getStreamRanges()) {
            streamOffsetRanges.add(new StreamOffsetRange(objectStreamRange.getStreamId(),
                objectStreamRange.getStartOffset(), objectStreamRange.getEndOffset()));
        }
        if (request.getObjectId() != -1) {
            S3ObjectMetadata metadata = new S3ObjectMetadata(request.getObjectId(), S3ObjectType.STREAM_SET,
                streamOffsetRanges, System.currentTimeMillis(), System.currentTimeMillis(), request.getObjectSize(), request.getOrderId());
            compactedObjectMap.put(request.getObjectId(), metadata);
        }

        Map<Long, List<StreamDataBlock>> compactedStreamDataBlocksMap = CompactionUtils.blockWaitObjectIndices(streamMetadataList, new ArrayList<>(compactedObjectMap.values()), s3Operator);
        for (Map.Entry<Long, List<StreamDataBlock>> entry : compactedStreamDataBlocksMap.entrySet()) {
            long objectId = entry.getKey();
            DataBlockReader reader = new DataBlockReader(new S3ObjectMetadata(objectId,
                compactedObjectMap.get(objectId).objectSize(), S3ObjectType.STREAM_SET), s3Operator);
            reader.readBlocks(entry.getValue());
        }
        List<StreamDataBlock> expectedStreamDataBlocks = CompactionUtils.sortStreamRangePositions(streamDataBlocks);
        List<StreamDataBlock> compactedStreamDataBlocks = CompactionUtils.sortStreamRangePositions(compactedStreamDataBlocksMap);

        int i = 0;
        for (StreamDataBlock compactedStreamDataBlock : compactedStreamDataBlocks) {
            long currStreamId = compactedStreamDataBlock.getStreamId();
            long startOffset = compactedStreamDataBlock.getStartOffset();
            if (i == expectedStreamDataBlocks.size()) {
                return false;
            }
            List<StreamDataBlock> groupedStreamDataBlocks = new ArrayList<>();
            for (; i < expectedStreamDataBlocks.size(); i++) {
                StreamDataBlock expectedBlock = expectedStreamDataBlocks.get(i);

                if (startOffset == compactedStreamDataBlock.getEndOffset()) {
                    break;
                }
                if (currStreamId != expectedBlock.getStreamId()) {
                    return false;
                }
                if (startOffset != expectedBlock.getStartOffset()) {
                    return false;
                }
                if (expectedBlock.getEndOffset() > compactedStreamDataBlock.getEndOffset()) {
                    return false;
                }
                startOffset = expectedBlock.getEndOffset();
                groupedStreamDataBlocks.add(expectedBlock);
            }
            List<StreamDataBlock> compactedGroupedStreamDataBlocks = mergeStreamDataBlocksForGroup(List.of(groupedStreamDataBlocks));
            if (!compare(compactedStreamDataBlock, compactedGroupedStreamDataBlocks.get(0))) {
                return false;
            }
        }
        return true;
    }
}
