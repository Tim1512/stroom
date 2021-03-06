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

package stroom.index.server;

import org.apache.lucene.store.Directory;
import org.apache.lucene.store.NIOFSDirectory;
import org.apache.lucene.store.NoLockFactory;
import org.apache.lucene.store.SimpleFSLockFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import stroom.index.shared.IndexShard;
import stroom.index.shared.IndexShard.IndexShardStatus;
import stroom.index.shared.IndexShardService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Thread safe class used to govern access to a index shard. It is assumed that
 * the Pool returns these objects to writing threads.
 */
public abstract class AbstractIndexShard {
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractIndexShard.class);

    /**
     * The Data Model Object that records our location.
     */
    private final IndexShardService service;
    private final boolean readOnly;
    private IndexShard indexShard;
    /**
     * Lucene stuff
     */
    private Directory directory;

    /**
     * Status Info
     */
    private long openTime;

    public AbstractIndexShard(final IndexShardService service, final IndexShard indexShard, boolean readOnly) {
        this.readOnly = readOnly;
        this.service = service;
        this.indexShard = indexShard;
        this.openTime = System.currentTimeMillis();
    }

    protected void open() throws IOException {
        if (directory == null) {
            final Path dir = getIndexPath();

            try {
                if (readOnly) {
                    directory = new NIOFSDirectory(dir, NoLockFactory.INSTANCE);

                } else {
                    if (!Files.isDirectory(dir)) {
                        try {
                            Files.createDirectories(dir);
                        } catch (final IOException e) {
                            throw new IOException("getDirectory() - Failed to create directory " + dir);
                        }
                    }

                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Opening and locking index dir = " + dir.toAbsolutePath().toString() + " exists "
                                + Files.isDirectory(dir));
                    }

                    directory = new NIOFSDirectory(dir, SimpleFSLockFactory.INSTANCE);

                    // We have opened the index so update the DB object.
                    if (directory != null) {
                        indexShard.setStatus(IndexShardStatus.OPEN);
                        indexShard = service.save(indexShard);
                    }
                }
            } finally {
                if (directory == null) {
                    LOGGER.error("Failed to open: " + dir.toAbsolutePath().toString());
                }
            }
        }
    }

    protected void close() throws IOException {
        if (directory != null) {
            directory.close();
            directory = null;
        }
    }

    protected Path getIndexPath() {
        return IndexShardUtil.getIndexPath(indexShard);
    }

    protected Directory getDirectory() {
        return directory;
    }

    public long getOpenTime() {
        return openTime;
    }

    public IndexShard getIndexShard() {
        return indexShard;
    }

    /**
     * Allow the entity to be updated .... but you can't change the version
     *
     * @param indexShard
     */
    public void setIndexShard(final IndexShard indexShard) {
        if (!this.getIndexShard().getIndex().equals(indexShard.getIndex())) {
            throw new RuntimeException("Only able to update a index shard with the same index");
        }
        this.indexShard = indexShard;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("indexShard=");
        builder.append(indexShard);
        return super.toString();
    }
}
