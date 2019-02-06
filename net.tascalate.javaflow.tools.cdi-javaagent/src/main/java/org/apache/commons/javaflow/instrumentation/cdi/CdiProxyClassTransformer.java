/**
 * ﻿Copyright 2013-2019 Valery Silaev (http://vsilaev.com)
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
package org.apache.commons.javaflow.instrumentation.cdi;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;

import java.security.ProtectionDomain;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.tascalate.javaflow.asmx.ClassReader;
import net.tascalate.javaflow.asmx.ClassWriter;

import org.apache.commons.javaflow.providers.asmx.AsmxResourceTransformationFactory;
import org.apache.commons.javaflow.providers.asmx.ClassNameResolver;
import org.apache.commons.javaflow.spi.ContinuableClassInfoResolver;
import org.apache.commons.javaflow.spi.ExtendedClasspathResourceLoader;
import org.apache.commons.javaflow.spi.ResourceTransformationFactory;
import org.apache.commons.javaflow.spi.StopException;

public class CdiProxyClassTransformer implements ClassFileTransformer {
    private static final Logger log = LoggerFactory.getLogger(CdiProxyClassTransformer.class);

    private final ResourceTransformationFactory resourceTransformationFactory = new AsmxResourceTransformationFactory();

    // @Override
    public byte[] transform(
            ClassLoader classLoader, 
            final String className, 
            final Class<?> classBeingRedefined,
            final ProtectionDomain protectionDomain, 
            final byte[] classfileBuffer) throws IllegalClassFormatException {
        
        if (skipClassByName(className)) {
            if (log.isDebugEnabled()) {
                log.debug("Ignoring class by name (looks like Java std. class): " + className);
            }
            return null;
        }

        classLoader = getSafeClassLoader(classLoader);
        final ContinuableClassInfoResolver resolver = getCachedResolver(classLoader);
        synchronized (resolver) {
            final ClassNameResolver.Result currentTarget = ClassNameResolver.resolveClassName(className, classBeingRedefined, classfileBuffer);
            try {
                // Execute with current class as extra resource (in-memory)
                // Mandatory for Java8 lambdas and alike
                return ExtendedClasspathResourceLoader.runWithInMemoryResources(
                    new Callable<byte[]>() {
                        public byte[] call() {
                            ClassReader reader = new ClassReader(classfileBuffer);
                            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES + ClassWriter.COMPUTE_MAXS);
                            reader.accept(new CdiProxyClassAdapter(writer, resolver), ClassReader.EXPAND_FRAMES);
                            return writer.toByteArray();
                        }
                    }, 
                    currentTarget.asResource()
                );
            } catch (StopException ex) {
                return null;
            } catch (RuntimeException ex) {
                if (log.isErrorEnabled()) {
                    if (VERBOSE_ERROR_REPORTS) {
                        log.error("Error transforming " + currentTarget.className, ex);
                    } else {
                        log.error("Error transforming " + currentTarget.className);
                    }
                }
                return null;
            } catch (Error ex) {
                log.error("Internal error during transforming CDI continuable proxy", ex);
                throw ex;
            }
        }
    }

    protected ClassLoader getSafeClassLoader(ClassLoader classLoader) {
        return null != classLoader ? classLoader : ClassLoader.getSystemClassLoader();
    }

    protected ContinuableClassInfoResolver getCachedResolver(ClassLoader classLoader) {
        synchronized (classLoader2resolver) {
            ContinuableClassInfoResolver cachedResolver = classLoader2resolver.get(classLoader);
            if (null == cachedResolver) {
                ContinuableClassInfoResolver newResolver = resourceTransformationFactory
                    .createResolver(new ExtendedClasspathResourceLoader(classLoader));
                classLoader2resolver.put(classLoader, newResolver);
                return newResolver;
            } else {
                return cachedResolver;
            }
        }
    }
    
    static boolean skipClassByName(String className) {
        return null != className && (
               className.startsWith("org/apache/commons/logging/") || // Very special logging beast :)
                
               className.startsWith("java/")       || 
               className.startsWith("javax/")      ||
               className.startsWith("jdk/")        || // Azul Zulu
               className.startsWith("sun/")        ||
               className.startsWith("com/sun/")    ||
               className.startsWith("oracle/")     ||
               className.startsWith("com/oracle/") ||
               className.startsWith("ibm/")        || // IBM J9
               className.startsWith("com/ibm/")    || // IBM J9
               className.startsWith("openj9/")        // Eclipse OpenJ9
               );
    }

    private static final Map<ClassLoader, ContinuableClassInfoResolver> classLoader2resolver = new WeakHashMap<ClassLoader, ContinuableClassInfoResolver>();
    private static final boolean VERBOSE_ERROR_REPORTS = Boolean.getBoolean("org.apache.commons.javaflow.instrumentation.verbose");
}
