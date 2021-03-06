/*
 * Copyright 2017 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.index.server;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MarkerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import stroom.index.server.CachedIndexService.CachedIndex;
import stroom.index.shared.Index;
import stroom.index.shared.IndexField;
import stroom.index.shared.IndexFieldType;
import stroom.index.shared.IndexShardKey;
import stroom.pipeline.server.LocationFactoryProxy;
import stroom.pipeline.server.errorhandler.ErrorReceiverProxy;
import stroom.pipeline.server.errorhandler.ErrorStatistics;
import stroom.pipeline.server.errorhandler.LoggedException;
import stroom.pipeline.server.factory.ConfigurableElement;
import stroom.pipeline.server.factory.PipelineProperty;
import stroom.pipeline.server.filter.AbstractXMLFilter;
import stroom.pipeline.shared.ElementIcons;
import stroom.pipeline.shared.data.PipelineElementType;
import stroom.pipeline.shared.data.PipelineElementType.Category;
import stroom.pipeline.state.StreamHolder;
import stroom.search.server.IndexFieldsMap;
import stroom.util.CharBuffer;
import stroom.util.date.DateUtil;
import stroom.util.shared.Severity;
import stroom.util.spring.StroomScope;

import javax.annotation.Resource;
import java.util.concurrent.locks.Lock;

/**
 * The index filter... takes the index XML and builds the LUCENE documents
 */
@Component
@Scope(StroomScope.PROTOTYPE)
@ConfigurableElement(type = "IndexingFilter", category = Category.FILTER, roles = {PipelineElementType.ROLE_TARGET,
        PipelineElementType.ROLE_HAS_TARGETS, PipelineElementType.VISABILITY_SIMPLE}, icon = ElementIcons.INDEX)
public class IndexingFilter extends AbstractXMLFilter {
    private static final Logger LOGGER = LoggerFactory.getLogger(IndexingFilter.class);

    private static final StripedLock WRITER_KEY_LOCKS = new StripedLock();

    private static final String RECORD = "record";
    private static final String DATA = "data";
    private static final String NAME = "name";
    private static final String VALUE = "value";
    private final CharBuffer debugBuffer = new CharBuffer(10);
    @Resource
    private StreamHolder streamHolder;
    @Resource
    private LocationFactoryProxy locationFactory;
    @Resource
    private IndexShardWriterCache indexShardCache;
    @Resource
    private ErrorReceiverProxy errorReceiverProxy;
    @Resource
    private CachedIndexService cachedIndexService;
    private IndexFieldsMap indexFieldsMap;
    private Index index;
    private IndexShardKey indexShardKey;
    private IndexShardWriter indexShardWriter;
    private Document document;

    private int fieldsIndexed = 0;

    private Locator locator;

    /**
     * Initialise
     */
    @Override
    public void startProcessing() {
        try {
            if (index == null) {
                log(Severity.FATAL_ERROR, "Index has not been set", null);
                throw new LoggedException("Index has not been set");
            }

            // Get the index and index fields from the cache.
            final CachedIndex cachedIndex = cachedIndexService.get(index);
            if (cachedIndex == null) {
                log(Severity.FATAL_ERROR, "Unable to load index", null);
                throw new LoggedException("Unable to load index");
            }

            index = cachedIndex.getIndex();
            indexFieldsMap = cachedIndex.getIndexFieldsMap();

            // Create a key to create shards with.
            if (streamHolder == null || streamHolder.getStream() == null) {
                // Many tests don't use streams so where this is the case just
                // create a basic key.
                indexShardKey = IndexShardKeyUtil.createTestKey(index);
            } else {
                final long timeMs = streamHolder.getStream().getCreateMs();
                indexShardKey = IndexShardKeyUtil.createTimeBasedPartition(index, timeMs);
            }
        } finally {
            super.startProcessing();
        }
    }

    /**
     * Sets the locator to use when reporting errors.
     *
     * @param locator The locator to use.
     */
    @Override
    public void setDocumentLocator(final Locator locator) {
        this.locator = locator;
        super.setDocumentLocator(locator);
    }

    @Override
    public void startElement(final String uri, final String localName, final String qName, final Attributes atts)
            throws SAXException {
        if (DATA.equals(localName) && document != null) {
            String name = atts.getValue(NAME);
            String value = atts.getValue(VALUE);
            if (name != null && value != null) {
                name = name.trim();
                value = value.trim();

                if (name.length() > 0 && value.length() > 0) {
                    // See if we can get this field.
                    final IndexField indexField = indexFieldsMap.get(name);
                    if (indexField != null) {
                        // Index the current content if we are to store or index
                        // this field.
                        if (indexField.isIndexed() || indexField.isStored()) {
                            processIndexContent(indexField, value);
                        }
                    } else {
                        log(Severity.WARNING, "Attempt to index unknown field: " + name, null);
                    }
                }
            }
        } else if (RECORD.equals(localName)) {
            // Create a document to store fields in.
            document = new Document();
        }

        super.startElement(uri, localName, qName, atts);
    }

    @Override
    public void endElement(final String uri, final String localName, final String qName) throws SAXException {
        if (RECORD.equals(localName)) {
            processDocument();
            document = null;

            // Reset the count of how many fields we have indexed for the
            // current event.
            fieldsIndexed = 0;

            if (errorReceiverProxy.getErrorReceiver() != null
                    && errorReceiverProxy.getErrorReceiver() instanceof ErrorStatistics) {
                ((ErrorStatistics) errorReceiverProxy.getErrorReceiver()).checkRecord(-1);
            }
        }

        super.endElement(uri, localName, qName);
    }

    private void processDocument() throws SAXException {
        // Write the document if we have dropped out of the record element and
        // have indexed some fields.
        if (fieldsIndexed > 0) {
            try {
                boolean success = false;
                while (!success) {
                    success = indexShardWriter != null && indexShardWriter.addDocument(document);

                    if (!success) {
                        // If we failed then try under lock to make sure we get a new writer.
                        final Lock lock = WRITER_KEY_LOCKS.getLockForKey(indexShardKey);
                        lock.lock();
                        try {
                            // Ask the cache for the current one (it might have been changed by another thread) and try again.
                            indexShardWriter = getIndexShardWriter();
                            success = indexShardWriter.addDocument(document);

                            if (!success) {
                                // Failed to add it so remove this object from the cache and try to get another one.
                                indexShardCache.remove(indexShardKey);

                                // Ask the pool for another one and try again
                                final IndexShardWriter newWriter = getIndexShardWriter();
                                if (newWriter == indexShardWriter) {
                                    LOGGER.error(MarkerFactory.getMarker("FATAL"), "Expected a new writer but got the same one back!!!");
                                    throw new IndexException("Expected a new writer but got the same one back!!!");
                                }

                                indexShardWriter = newWriter;
                            }

                        } finally {
                            lock.unlock();
                        }
                    }
                }
            } catch (final RuntimeException e) {
                log(Severity.FATAL_ERROR, e.getMessage(), e);
                // Terminate processing as this is a fatal error.
                throw new LoggedException(e.getMessage(), e);
            }
        }
    }

    private void processIndexContent(final IndexField indexField, final String value) {
        try {
            Field field = null;

            if (indexField.getFieldType().isNumeric()) {
                final long val = Long.parseLong(value);
                field = FieldFactory.create(indexField, val);

            } else if (IndexFieldType.DATE_FIELD.equals(indexField.getFieldType())) {
                try {
                    final long val = DateUtil.parseUnknownString(value);
                    field = FieldFactory.create(indexField, val);
                } catch (final Exception e) {
                    LOGGER.trace(e.getMessage(), e);
                }
            } else {
                field = FieldFactory.create(indexField, value);
            }

            // Add the current field to the document if it is not null.
            if (field != null) {
                // Output some debug.
                if (LOGGER.isDebugEnabled()) {
                    debugBuffer.append("endElement() - Adding index indexName=");
                    debugBuffer.append(index);
                    debugBuffer.append(" name=");
                    debugBuffer.append(indexField.getFieldName());
                    debugBuffer.append(" value=");
                    debugBuffer.append(value);

                    final String debug = debugBuffer.toString();
                    debugBuffer.clear();

                    LOGGER.debug(debug);
                }

                fieldsIndexed++;
                document.add(field);
            }
        } catch (final RuntimeException e) {
            log(Severity.ERROR, e.getMessage(), e);
        }
    }

    private IndexShardWriter getIndexShardWriter() throws IndexException {
        indexShardWriter = indexShardCache.get(indexShardKey);
        if (indexShardWriter == null) {
            throw new IndexException("Unable to get writer for index '" + indexShardKey.getIndex().getName()
                    + "'. Please check the index has active volumes.");
        }

        return indexShardWriter;
    }

    @PipelineProperty(description = "The index to send records to.")
    public void setIndex(final Index index) {
        this.index = index;
    }

    private void log(final Severity severity, final String message, final Exception e) {
        errorReceiverProxy.log(severity, locationFactory.create(locator), getElementId(), message, e);
    }
}
