/*
 * Copyright (c) 2023 Oracle and/or its affiliates.
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

import io.helidon.common.features.api.Feature;
import io.helidon.common.features.api.HelidonFlavor;

/**
 * Helidon WebClient Tracing.
 */
@Feature(value = "Tracing",
         description = "Web client support for tracing",
         in = HelidonFlavor.SE,
         path = {"WebClient", "Tracing"}
)
module io.helidon.nima.webclient.tracing {
    requires static io.helidon.common.features.api;

    requires io.helidon.nima.webclient;
    requires io.helidon.tracing;

    exports io.helidon.nima.webclient.tracing;

    provides io.helidon.nima.webclient.spi.WebClientServiceProvider
            with io.helidon.nima.webclient.tracing.WebClientTracingProvider;
}