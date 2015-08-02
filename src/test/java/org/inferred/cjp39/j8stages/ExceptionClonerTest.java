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
}
