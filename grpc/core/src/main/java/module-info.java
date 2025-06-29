/*
 * Copyright (c) 2022, 2025 Oracle and/or its affiliates.
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
 * Helidon GRPC core package.
 */
module io.helidon.grpc.core {

    requires io.helidon.common;
    requires io.helidon.http;
    requires io.helidon.http.http2;
    requires io.helidon.common.context;
    requires io.helidon.tracing;

    requires transitive io.grpc;
    requires transitive io.grpc.stub;
    requires transitive com.google.protobuf;
    requires transitive io.grpc.protobuf;

    exports io.helidon.grpc.core;
}
