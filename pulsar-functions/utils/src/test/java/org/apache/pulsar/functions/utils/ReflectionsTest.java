/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.functions.utils;

import static org.apache.pulsar.functions.utils.Reflections.createInstance;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.AssertJUnit.fail;

import java.lang.reflect.InvocationTargetException;
import org.testng.annotations.Test;

/**
 * Unit test of {@link Reflections}.
 */
public class ReflectionsTest {

    private interface bInterface {
    }

    private interface aInterface {
    }

    private static class aImplementation implements aInterface {
    }

    private static class OneArgClass implements aInterface {

        OneArgClass(int oneArg) {}

    }

    private static class ThrowExceptionClass implements aInterface {
        ThrowExceptionClass() {
            throw new RuntimeException("throw exception");
        }
    }

    private static abstract class AbstractClass implements aInterface {
    }

    private final ClassLoader classLoader;

    public ReflectionsTest() {
        this.classLoader = ReflectionsTest.class.getClassLoader();
    }

    @Test
    public void testCreateInstanceClassNotFound() {
        try {
            createInstance("notfound-class", classLoader);
            fail("Should fail to load notfound class");
        } catch (RuntimeException re) {
            assertTrue(re.getCause() instanceof ClassNotFoundException);
        }
    }

    @Test
    public void testCreateInstanceNoNoArgConstructor() {
        try {
            createInstance(OneArgClass.class.getName(), classLoader);
            fail("Should fail to load class doesn't have no-arg constructor");
        } catch (RuntimeException re) {
            assertTrue(re.getCause() instanceof NoSuchMethodException);
        }
    }

    @Test
    public void testCreateInstanceConstructorThrowsException() {
        try {
            createInstance(ThrowExceptionClass.class.getName(), classLoader);
            fail("Should fail to load class whose constructor throws exceptions");
        } catch (RuntimeException re) {
            assertTrue(re.getCause() instanceof InvocationTargetException);
        }
    }

    @Test
    public void testCreateInstanceAbstractClass() {
        try {
            createInstance(AbstractClass.class.getName(), classLoader);
            fail("Should fail to load abstract class");
        } catch (RuntimeException re) {
            assertTrue(re.getCause() instanceof InstantiationException);
        }
    }

    @Test
    public void testCreateTypedInstanceClassNotFound() {
        try {
            createInstance("notfound-class", aInterface.class, classLoader);
            fail("Should fail to load notfound class");
        } catch (RuntimeException re) {
            assertTrue(re.getCause() instanceof ClassNotFoundException);
        }
    }

    @Test
    public void testCreateTypedInstanceNoNoArgConstructor() {
        try {
            createInstance(OneArgClass.class.getName(), aInterface.class, classLoader);
            fail("Should fail to load class doesn't have no-arg constructor");
        } catch (RuntimeException re) {
            assertTrue(re.getCause() instanceof NoSuchMethodException);
        }
    }

    @Test
    public void testCreateTypedInstanceConstructorThrowsException() {
        try {
            createInstance(ThrowExceptionClass.class.getName(), aInterface.class, classLoader);
            fail("Should fail to load class whose constructor throws exceptions");
        } catch (RuntimeException re) {
            assertTrue(re.getCause() instanceof InvocationTargetException);
        }
    }

    @Test
    public void testCreateTypedInstanceAbstractClass() {
        try {
            createInstance(AbstractClass.class.getName(), aInterface.class, classLoader);
            fail("Should fail to load abstract class");
        } catch (RuntimeException re) {
            assertTrue(re.getCause() instanceof InstantiationException);
        }
    }

    @Test
    public void testCreateTypedInstanceUnassignableClass() {
        try {
            createInstance(aImplementation.class.getName(), bInterface.class, classLoader);
            fail("Should fail to load a class that isn't assignable");
        } catch (RuntimeException re) {
            assertEquals(
                aImplementation.class.getName() + " not " + bInterface.class.getName(),
                re.getMessage());
        }
    }

    @Test
    public void testclassInJarImplementsIface() {
        assertTrue(Reflections.classImplementsIface(aImplementation.class.getName(), aInterface.class));
        assertTrue(!Reflections.classImplementsIface(aImplementation.class.getName(), bInterface.class));
    }

    @Test
    public void testClassExists() {
        assertTrue(Reflections.classExists(String.class.getName()));
        assertTrue(!Reflections.classExists("com.fake.class"));
    }
}
