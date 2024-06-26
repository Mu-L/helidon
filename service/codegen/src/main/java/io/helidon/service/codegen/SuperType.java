/*
 * Copyright (c) 2024 Oracle and/or its affiliates.
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

package io.helidon.service.codegen;

import io.helidon.common.types.TypeInfo;
import io.helidon.common.types.TypeName;

/**
 * Definition of a super type (if any).
 *
 * @param hasSupertype        whether there is a super type (declared through {@code extends SuperType})
 * @param superDescriptorType type name of the service descriptor of the super type
 * @param superType           type info of the super type
 */
record SuperType(boolean hasSupertype,
                 TypeName superDescriptorType,
                 TypeInfo superType,
                 boolean superTypeIsCore) {
    static SuperType noSuperType() {
        return new SuperType(false, null, null, false);
    }
}
