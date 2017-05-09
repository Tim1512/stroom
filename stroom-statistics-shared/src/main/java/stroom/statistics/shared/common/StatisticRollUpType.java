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

package stroom.statistics.shared.common;

import stroom.entity.shared.HasPrimitiveValue;
import stroom.entity.shared.PrimitiveValueConverter;
import stroom.util.shared.HasDisplayValue;

import java.io.Serializable;

public enum StatisticRollUpType implements HasDisplayValue,HasPrimitiveValue,Serializable {
    NONE("None", 1),
    ALL("All", 2),
    CUSTOM("Custom", 3);

    private final String displayValue;
    private final byte primitiveValue;

    public static final PrimitiveValueConverter<StatisticRollUpType> PRIMITIVE_VALUE_CONVERTER = new PrimitiveValueConverter<>(
            StatisticRollUpType.values());

    StatisticRollUpType(final String displayValue, final int primitiveValue) {
        this.displayValue = displayValue;
        this.primitiveValue = (byte) primitiveValue;
    }

    @Override
    public String getDisplayValue() {
        return displayValue;
    }

    @Override
    public byte getPrimitiveValue() {
        return primitiveValue;
    }
}
