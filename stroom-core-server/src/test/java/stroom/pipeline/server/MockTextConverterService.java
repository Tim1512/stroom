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

package stroom.pipeline.server;

import stroom.entity.server.MockDocumentService;
import stroom.entity.shared.DocumentType;
import stroom.pipeline.shared.FindTextConverterCriteria;
import stroom.pipeline.shared.TextConverter;
import stroom.pipeline.shared.TextConverterService;
import stroom.util.spring.StroomSpringProfiles;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * <p>
 * Very simple mock that keeps everything in memory.
 * </p>
 * <p>
 * <p>
 * You can call clear at any point to clear everything down.
 * </p>
 */
@Profile(StroomSpringProfiles.TEST)
@Component
public class MockTextConverterService extends MockDocumentService<TextConverter, FindTextConverterCriteria>
        implements TextConverterService {
    @Override
    public DocumentType getDocumentType() {
        return getDocumentType(4, "TextConverter", "Text Converter");
    }

    @Override
    public Class<TextConverter> getEntityClass() {
        return TextConverter.class;
    }
}
