/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
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

package com.automq.rocketmq.metadata;

import apache.rocketmq.controller.v1.ConsumerGroup;
import apache.rocketmq.controller.v1.S3StreamObject;
import apache.rocketmq.controller.v1.S3WALObject;
import apache.rocketmq.controller.v1.StreamMetadata;
import apache.rocketmq.controller.v1.StreamRole;
import com.automq.rocketmq.common.config.ControllerConfig;
import com.automq.rocketmq.controller.exception.ControllerException;
import com.automq.rocketmq.controller.metadata.MetadataStore;
import com.automq.rocketmq.controller.metadata.database.DefaultMetadataStore;
import com.automq.rocketmq.metadata.api.StoreMetadataService;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultStoreMetadataService implements StoreMetadataService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultMetadataStore.class);

    private final MetadataStore metadataStore;

    public DefaultStoreMetadataService(MetadataStore metadataStore) {
        this.metadataStore = metadataStore;
    }

    @Override
    public CompletableFuture<StreamMetadata> dataStreamOf(long topicId, int queueId) {
        return metadataStore.getStream(topicId, queueId, null, StreamRole.STREAM_ROLE_DATA);
    }

    @Override
    public CompletableFuture<StreamMetadata> operationStreamOf(long topicId, int queueId) {
        return metadataStore.getStream(topicId, queueId, null, StreamRole.STREAM_ROLE_OPS);
    }

    @Override
    public CompletableFuture<StreamMetadata> snapshotStreamOf(long topicId, int queueId) {
        return metadataStore.getStream(topicId, queueId, null, StreamRole.STREAM_ROLE_SNAPSHOT);
    }

    @Override
    public CompletableFuture<StreamMetadata> retryStreamOf(long consumerGroupId, long topicId, int queueId) {
        return metadataStore.getStream(topicId, queueId, consumerGroupId, StreamRole.STREAM_ROLE_RETRY);
    }

    @Override
    public CompletableFuture<Integer> maxDeliveryAttemptsOf(long consumerGroupId) {
        return metadataStore.describeGroup(consumerGroupId, null).thenApply((ConsumerGroup::getMaxDeliveryAttempt));
    }

    @Override
    public CompletableFuture<Void> trimStream(long streamId, long streamEpoch,
        long newStartOffset) {
        try {
            return metadataStore.trimStream(streamId, streamEpoch, newStartOffset);
        } catch (ControllerException e) {
            LOGGER.error("Exception raised while trim stream for {}, {}, {}", streamId, streamEpoch, newStartOffset, e);
            return null;
        }
    }

    @Override
    public CompletableFuture<StreamMetadata> openStream(long streamId, long streamEpoch) {
        return metadataStore.openStream(streamId, streamEpoch, metadataStore.config().nodeId());
    }

    @Override
    public CompletableFuture<Void> closeStream(long streamId, long streamEpoch) {
        return metadataStore.closeStream(streamId, streamEpoch, metadataStore.config().nodeId());
    }

    @Override
    public CompletableFuture<List<StreamMetadata>> listOpenStreams() {
        return metadataStore.listOpenStreams(metadataStore.config().nodeId());
    }

    @Override
    public CompletableFuture<Long> prepareS3Objects(int count, int ttlInMinutes) {
        return metadataStore.prepareS3Objects(count, ttlInMinutes);
    }

    @Override
    public CompletableFuture<Void> commitWalObject(S3WALObject walObject, List<S3StreamObject> streamObjects,
        List<Long> compactedObjects) {
        // The underlying storage layer does not know the current node id when constructing the WAL object.
        // So we should fill it here.
        S3WALObject newWal = S3WALObject.newBuilder(walObject).setBrokerId(metadataStore.config().nodeId()).build();
        return metadataStore.commitWalObject(newWal, streamObjects, compactedObjects);
    }

    @Override
    public CompletableFuture<Void> commitStreamObject(S3StreamObject streamObject, List<Long> compactedObjects) {
        try {
            return metadataStore.commitStreamObject(streamObject, compactedObjects);
        } catch (ControllerException e) {
            LOGGER.error("Exception raised while commit Stream Object for {}, {}", streamObject, compactedObjects, e);
            return null;
        }
    }

    @Override
    public CompletableFuture<List<S3WALObject>> listWALObjects() {
        return metadataStore.listWALObjects();
    }

    @Override
    public CompletableFuture<List<S3WALObject>> listWALObjects(long streamId, long startOffset, long endOffset,
        int limit) {
        return metadataStore.listWALObjects(streamId, startOffset, endOffset, limit);
    }

    @Override
    public CompletableFuture<List<S3StreamObject>> listStreamObjects(long streamId, long startOffset, long endOffset,
        int limit) {
        return metadataStore.listStreamObjects(streamId, startOffset, endOffset, limit);
    }

    @Override
    public CompletableFuture<Pair<List<S3StreamObject>, List<S3WALObject>>> listObjects(long streamId, long startOffset,
        long endOffset, int limit) {
        return metadataStore.listObjects(streamId, startOffset, endOffset, limit);
    }

    @Override
    public int ownerNode(long topicId, int queueId) {
        return metadataStore.ownerNode(topicId, queueId);
    }

    @Override
    public ControllerConfig nodeConfig() {
        return metadataStore.config();
    }

    @Override
    public CompletableFuture<List<StreamMetadata>> getStreams(List<Long> streamIds) {
        return metadataStore.getStreams(streamIds);
    }
}
