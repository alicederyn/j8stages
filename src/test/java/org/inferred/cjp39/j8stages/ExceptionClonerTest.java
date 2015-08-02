package org.inferred.cjp39.j8stages;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

public class ExceptionClonerTest {

    private static final class MyUnfriendlyException extends Exception {
        private MyUnfriendlyException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @Test
    public void clone_matches_original() {
        MyUnfriendlyException x =
                new MyUnfriendlyException("Clone this, baby!", new RuntimeException("cause"));
        MyUnfriendlyException clone = ExceptionCloner.clone(x);
        assertEquals(x.getMessage(), clone.getMessage());
        assertSame(x.getCause(), clone.getCause());
    }

    @Test
    public void modifying_clone_does_not_modify_original() {
        MyUnfriendlyException x =
                new MyUnfriendlyException("Clone this, baby!", new RuntimeException("cause"));
        MyUnfriendlyException clone = ExceptionCloner.clone(x);
        clone.addSuppressed(new RuntimeException("dormouse"));
        assertEquals(0, x.getSuppressed().length);
    }

    @Test
    public void suppressed_array_not_aliased() {
        MyUnfriendlyException x =
                new MyUnfriendlyException("Clone this, baby!", new RuntimeException("cause"));
        x.addSuppressed(new RuntimeException("Help, help, I'm being repressed!"));
        MyUnfriendlyException clone = ExceptionCloner.clone(x);
        clone.addSuppressed(new RuntimeException("dormouse"));
        MyUnfriendlyException clone2 = ExceptionCloner.clone(clone);
        clone2.addSuppressed(new RuntimeException("in a teapot"));
        assertEquals(3, clone2.getSuppressed().length);
        assertEquals(2, clone.getSuppressed().length);
        assertEquals(1, x.getSuppressed().length);
    }
}
