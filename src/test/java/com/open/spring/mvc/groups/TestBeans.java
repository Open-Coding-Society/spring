package com.open.spring.mvc.groups;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Comparator;

import org.mockito.Mockito;

/**
 * Builds a class through its constructor without naming the constructor's parameters. Each
 * parameter gets the first object the test passes that fits it, and any other parameter gets a
 * Mockito mock. So a test keeps compiling, and keeps running, when another change gives the
 * class a dependency the test doesn't care about.
 */
final class TestBeans {

    private TestBeans() {
    }

    static <T> T build(Class<T> type, Object... given) {
        Constructor<?> constructor = Arrays.stream(type.getDeclaredConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount))
                .orElseThrow();
        Object[] args = Arrays.stream(constructor.getParameterTypes())
                .map(parameter -> Arrays.stream(given)
                        .filter(parameter::isInstance)
                        .findFirst()
                        .orElseGet(() -> Mockito.mock(parameter)))
                .toArray();
        try {
            constructor.setAccessible(true);
            return type.cast(constructor.newInstance(args));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not build " + type.getSimpleName(), e);
        }
    }
}
