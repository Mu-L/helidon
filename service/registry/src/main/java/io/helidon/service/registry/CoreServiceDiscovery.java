/*
 * Copyright (c) 2024, 2025 Oracle and/or its affiliates.
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

package io.helidon.service.registry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.System.Logger.Level;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Stream;

import io.helidon.common.LazyValue;
import io.helidon.common.Weighted;
import io.helidon.common.Weights;
import io.helidon.common.types.ResolvedType;
import io.helidon.common.types.TypeName;
import io.helidon.metadata.hson.Hson;
import io.helidon.service.metadata.DescriptorMetadata;
import io.helidon.service.metadata.Descriptors;

import static io.helidon.service.metadata.Descriptors.SERVICE_REGISTRY_LOCATION;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.function.Predicate.not;

class CoreServiceDiscovery implements ServiceDiscovery {
    private static final System.Logger LOGGER = System.getLogger(CoreServiceDiscovery.class.getName());

    private final List<DescriptorHandler> allDescriptors;

    private CoreServiceDiscovery(ServiceRegistryConfig config) {
        Map<TypeName, DescriptorHandler> allDescriptors = new LinkedHashMap<>();

        ClassLoader classLoader = classLoader();

        // each line is a type:service-descriptor:weight:contract,contract
        if (config.discoverServices()) {
            classLoader.resources(SERVICE_REGISTRY_LOCATION)
                    .forEach(url -> {
                        loadServices(url)
                                .map(DescriptorHandlerImpl::new)
                                .forEach(it -> allDescriptors.putIfAbsent(it.descriptorType(),
                                                                          it));
                    });
        }

        List<DescriptorHandler> result = new ArrayList<>(allDescriptors.values());

        if (config.discoverServicesFromServiceLoader()) {
            // each line is a provider type name (and may have zero or more implementations)
            classLoader.resources(SERVICES_LOADER_RESOURCE)
                    .flatMap(CoreServiceDiscovery::loadLines)
                    .filter(not(Line::isEmpty))
                    .filter(not(Line::isComment))
                    .flatMap(DescriptorHandlerImpl::parseServiceProvider)
                    .forEach(result::add);
        }

        this.allDescriptors = List.copyOf(result);
    }

    static ServiceDiscovery create(ServiceRegistryConfig config) {
        return new CoreServiceDiscovery(config);
    }

    static ServiceDiscovery noop() {
        return NoopServiceDiscovery.INSTANCE;
    }

    @Override
    public List<DescriptorHandler> allMetadata() {
        return allDescriptors;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> Class<T> toClass(TypeName className) {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            cl = (cl == null) ? CoreServiceDiscovery.class.getClassLoader() : cl;

            return (Class) cl.loadClass(className.declaredName());
        } catch (ClassNotFoundException e) {
            try {
                // fall back to classloader of our class
                return (Class) CoreServiceDiscovery.class.getClassLoader().loadClass(className.declaredName());
            } catch (ClassNotFoundException ex) {
                var toThrow = new ServiceRegistryException("Resolution of type \"" + className.declaredName()
                                                           + "\" to class failed.",
                                                   ex);
                toThrow.addSuppressed(e);
                throw toThrow;
            }
        }
    }

    private static ServiceDescriptor<?> getDescriptorInstance(TypeName descriptorType) {
        Class<?> clazz = toClass(descriptorType);

        try {
            Field field = clazz.getField("INSTANCE");
            Object descriptorInstance = field.get(null);
            if (descriptorInstance instanceof ServiceDescriptor<?> sd) {
                return sd;
            }
            return (ServiceDescriptor<?>) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new ServiceRegistryException("Could not obtain the instance of service descriptor "
                                                       + descriptorType.fqName(),
                                               e);
        }
    }

    private static ClassLoader classLoader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            return CoreServiceDiscovery.class.getClassLoader();
        }
        return cl;
    }

    private static Stream<Line> loadLines(URL url) {

        try (BufferedReader br = new BufferedReader(new InputStreamReader(url.openStream(), UTF_8))) {
            List<Line> lines = new ArrayList<>();

            String line;
            int lineNumber = 0;

            while ((line = br.readLine()) != null) {
                lineNumber++; // we want to start with 1
                lines.add(new Line(url.toString(), line, lineNumber));
            }

            return lines.stream();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to read services from " + url, e);
            return Stream.of();
        }
    }

    private static DescriptorHandlerImpl createServiceProviderDescriptor(TypeName providerType,
                                                                         ServiceLoader.Provider<Object> provider) {
        Class<?> serviceClass = provider.type();
        double weight = Weights.find(serviceClass, Weighted.DEFAULT_WEIGHT);

        if (LOGGER.isLoggable(Level.TRACE)) {
            LOGGER.log(Level.TRACE,
                       "Discovered service provider for type %s: %s, weight: %s".formatted(providerType.fqName(),
                                                                                           serviceClass.getName(),
                                                                                           weight));
        }

        ServiceDescriptor<Object> descriptor = ServiceLoader__ServiceDescriptor.create(providerType, provider, weight);
        return new DescriptorHandlerImpl(DescriptorMetadata.create(descriptor.descriptorType(),
                                                                   weight,
                                                                   descriptor.contracts(),
                                                                   descriptor.factoryContracts()),
                                         LazyValue.create(descriptor));
    }

    private Stream<DescriptorMetadata> loadServices(URL url) {

        Hson.Array array;

        try (var stream = url.openStream()) {
            array = Hson.parse(stream)
                    .asArray();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to read services from " + url, e);
            return Stream.of();
        }

        return Descriptors.descriptors("classpath descriptor " + url,
                                       array)
                .stream();

    }

    private record Line(String source, String line, int lineNumber) {
        boolean isEmpty() {
            return line.isEmpty();
        }

        boolean isComment() {
            return line.startsWith("#");
        }
    }

    private record DescriptorHandlerImpl(DescriptorMetadata metadata,
                                         LazyValue<ServiceDescriptor<?>> descriptorSupplier) implements DescriptorHandler {

        DescriptorHandlerImpl(DescriptorMetadata metadata) {
            this(metadata, LazyValue.create(() -> getDescriptorInstance(metadata.descriptorType())));
        }

        @Override
        public ServiceDescriptor<?> descriptor() {
            return descriptorSupplier.get();
        }

        @Override
        public TypeName descriptorType() {
            return metadata.descriptorType();
        }

        @Override
        public Set<ResolvedType> contracts() {
            return metadata.contracts();
        }

        @Override
        public Set<ResolvedType> factoryContracts() {
            return metadata.factoryContracts();
        }

        @Override
        public double weight() {
            return metadata.weight();
        }

        @Override
        public Hson.Struct toHson() {
            return metadata.toHson();
        }

        private static Stream<DescriptorHandlerImpl> parseServiceProvider(Line line) {
            // io.helidon.config.ConfigSource
            TypeName providerType = TypeName.create(line.line.trim());

            Class<Object> providerClass = toClass(providerType);
            CoreServiceDiscovery.class.getModule()
                    .addUses(providerClass);

            ServiceLoader<Object> serviceLoader = ServiceLoader.load(providerClass);

            return serviceLoader.stream()
                    .map(it -> CoreServiceDiscovery.createServiceProviderDescriptor(providerType, it));
        }
    }

    static class NoopServiceDiscovery implements ServiceDiscovery {
        private static final ServiceDiscovery INSTANCE = new NoopServiceDiscovery();

        @Override
        public List<DescriptorHandler> allMetadata() {
            return List.of();
        }
    }
}

