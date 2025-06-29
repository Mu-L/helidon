/*
 * Copyright (c) 2025 Oracle and/or its affiliates.
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

/**
 * Code generation support for Helidon Declarative APIs.
 */
module io.helidon.declarative.codegen {
    requires io.helidon.codegen;
    requires io.helidon.codegen.classmodel;
    requires io.helidon.service.codegen;
    requires io.helidon.declarative.codegen.model;

    exports io.helidon.declarative.codegen;

    exports io.helidon.declarative.codegen.http;
    // webserver related code generation (HTTP endpoints)
    exports io.helidon.declarative.codegen.http.webserver;
    exports io.helidon.declarative.codegen.http.webserver.spi;
    // typed web client
    exports io.helidon.declarative.codegen.http.restclient;

    // fault tolerance (fallback, retry, circuit breaker)
    exports io.helidon.declarative.codegen.faulttolerance;
    // scheduling
    exports io.helidon.declarative.codegen.scheduling;

    uses io.helidon.declarative.codegen.http.webserver.spi.HttpParameterCodegenProvider;

    provides io.helidon.service.codegen.spi.RegistryCodegenExtensionProvider
            with io.helidon.declarative.codegen.faulttolerance.FtExtensionProvider,
                    io.helidon.declarative.codegen.scheduling.SchedulingExtensionProvider,
                    io.helidon.declarative.codegen.http.restclient.RestClientExtensionProvider,
                    io.helidon.declarative.codegen.http.webserver.RestServerExtensionProvider;

    provides io.helidon.codegen.spi.AnnotationMapperProvider
            with io.helidon.declarative.codegen.http.restclient.RestClientAnnotationMapperProvider;
}