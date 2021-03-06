/*
 * Copyright 2016 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.streamstore.server;

import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import stroom.entity.server.util.SQLBuilder;
import stroom.entity.shared.SQLNameConstants;
import stroom.jobsystem.server.ClusterLockService;
import stroom.jobsystem.server.JobTrackedSchedule;
import stroom.node.server.StroomPropertyService;
import stroom.streamstore.shared.Stream;
import stroom.streamstore.shared.StreamAttributeValue;
import stroom.streamstore.shared.StreamStatus;
import stroom.streamstore.shared.StreamVolume;
import stroom.streamtask.server.AbstractBatchDeleteExecutor;
import stroom.streamtask.server.BatchIdTransactionHelper;
import stroom.streamtask.shared.StreamTask;
import stroom.util.spring.StroomFrequencySchedule;
import stroom.util.spring.StroomScope;
import stroom.util.task.TaskMonitor;

import javax.inject.Inject;

@Component
@Scope(value = StroomScope.TASK)
public class StreamDeleteExecutor extends AbstractBatchDeleteExecutor {
    private static final String TASK_NAME = "Stream Delete Executor";
    private static final String LOCK_NAME = "StreamDeleteExecutor";
    private static final String STREAM_DELETE_PURGE_AGE_PROPERTY = "stroom.stream.deletePurgeAge";
    private static final String STREAM_DELETE_BATCH_SIZE_PROPERTY = "stroom.stream.deleteBatchSize";
    private static final int DEFAULT_STREAM_DELETE_BATCH_SIZE = 1000;
    private static final String TEMP_STRM_ID_TABLE = "TEMP_STRM_ID";

    @Inject
    public StreamDeleteExecutor(final BatchIdTransactionHelper batchIdTransactionHelper,
            final ClusterLockService clusterLockService, final StroomPropertyService propertyService,
            final TaskMonitor taskMonitor) {
        super(batchIdTransactionHelper, clusterLockService, propertyService, taskMonitor, TASK_NAME, LOCK_NAME,
                STREAM_DELETE_PURGE_AGE_PROPERTY, STREAM_DELETE_BATCH_SIZE_PROPERTY, DEFAULT_STREAM_DELETE_BATCH_SIZE,
                TEMP_STRM_ID_TABLE);
    }

    @StroomFrequencySchedule("1h")
    @JobTrackedSchedule(jobName = "Stream Delete", description = "Physically delete streams that have been logically deleted based on age of delete ("
            + STREAM_DELETE_PURGE_AGE_PROPERTY + ")")
    @Transactional(propagation = Propagation.NEVER)
    public void exec() {
        lockAndDelete();
    }

    @Override
    protected void deleteCurrentBatch(final long total) {
        // Delete stream tasks.
        deleteWithJoin(StreamTask.TABLE_NAME, Stream.FOREIGN_KEY, "stream tasks", total);

        // Delete stream volumes.
        deleteWithJoin(StreamVolume.TABLE_NAME, Stream.FOREIGN_KEY, "stream volumes", total);

        // Delete stream attribute values.
        deleteWithJoin(StreamAttributeValue.TABLE_NAME, StreamAttributeValue.STREAM_ID, "stream attribute values",
                total);

        // Delete streams.
        deleteWithJoin(Stream.TABLE_NAME, Stream.ID, "streams", total);
    }

    @Override
    protected String getTempIdSelectSql(final long age, final int batchSize) {
        final SQLBuilder sql = new SQLBuilder();
        sql.append("SELECT ");
        sql.append(Stream.ID);
        sql.append(" FROM ");
        sql.append(Stream.TABLE_NAME);
        sql.append(" WHERE ");
        sql.append(SQLNameConstants.STATUS);
        sql.append(" = ");
        sql.append(StreamStatus.DELETED.getPrimitiveValue());
        sql.append(" AND ");
        sql.append(Stream.STATUS_MS);
        sql.append(" < ");
        sql.append(age);
        sql.append(" ORDER BY ");
        sql.append(Stream.ID);
        sql.append(" LIMIT ");
        sql.append(batchSize);
        return sql.toString();
    }
}
