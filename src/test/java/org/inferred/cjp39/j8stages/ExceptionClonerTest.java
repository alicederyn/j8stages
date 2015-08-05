package org.inferred.cjp39.j8stages;

import static com.google.common.truth.Truth.assertThat;
import static org.inferred.cjp39.j8stages.ExceptionOutputStream.ExceptionFields.CAUSE;
import static org.inferred.cjp39.j8stages.ExceptionOutputStream.ExceptionFields.STACK_TRACE;
import static org.inferred.cjp39.j8stages.ExceptionOutputStream.ExceptionFields.SUPPRESSED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.EnumSet;

import org.junit.Test;

public class ExceptionClonerTest {

    private static final class MyUnfriendlyException extends Exception {
        private static final long serialVersionUID = 3316555264743803094L;

        private MyUnfriendlyException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @Test
    public void clone_matches_original() {
        MyUnfriendlyException x = new MyUnfriendlyException("Clone this, baby!", new RuntimeException("cause"));
        MyUnfriendlyException clone = ExceptionCloner.clone(x);
        assertEquals(x.getMessage(), clone.getMessage());
        assertSame(x.getCause(), clone.getCause());
    }

    @Test
    public void modifying_clone_does_not_modify_original() {
        MyUnfriendlyException x = new MyUnfriendlyException("Clone this, baby!", new RuntimeException("cause"));
        MyUnfriendlyException clone = ExceptionCloner.clone(x);
        clone.addSuppressed(new RuntimeException("dormouse"));
        assertEquals(0, x.getSuppressed().length);
    }

    @Test
    public void suppressed_array_not_aliased() {
        MyUnfriendlyException x = new MyUnfriendlyException("Clone this, baby!", new RuntimeException("cause"));
        x.addSuppressed(new RuntimeException("Help, help, I'm being repressed!"));
        MyUnfriendlyException clone = ExceptionCloner.clone(x);
        clone.addSuppressed(new RuntimeException("dormouse"));
        MyUnfriendlyException clone2 = ExceptionCloner.clone(clone);
        clone2.addSuppressed(new RuntimeException("in a teapot"));
        assertEquals(3, clone2.getSuppressed().length);
        assertEquals(2, clone.getSuppressed().length);
        assertEquals(1, x.getSuppressed().length);
    }

    @Test
    public void can_replace_stack_trace() {
        MyUnfriendlyException x = new MyUnfriendlyException("Clone this, baby!", new RuntimeException("cause"));
        MyUnfriendlyException clone = ExceptionCloner.clone(x, EnumSet.of(STACK_TRACE));
        assertThat(clone.getStackTrace()).isEmpty();
        clone.setStackTrace(new RuntimeException().getStackTrace());
        assertThat(clone.getStackTrace()).isNotEmpty();
    }

    @Test
    public void can_replace_cause() {
        MyUnfriendlyException x = new MyUnfriendlyException("Clone this, baby!", new RuntimeException("cause"));
        MyUnfriendlyException clone = ExceptionCloner.clone(x, EnumSet.of(CAUSE));
        RuntimeException cause = new RuntimeException("new cause");
        clone.initCause(cause);
        assertThat(clone.getCause()).isEqualTo(cause);
    }

    @Test
    public void can_remove_suppressed_exceptions() {
        MyUnfriendlyException x = new MyUnfriendlyException("Clone this, baby!", new RuntimeException("cause"));
        x.addSuppressed(new RuntimeException("Help, help, I'm being repressed!"));
        MyUnfriendlyException clone = ExceptionCloner.clone(x, EnumSet.of(SUPPRESSED));
        clone.addSuppressed(new RuntimeException("dormouse"));
        MyUnfriendlyException clone2 = ExceptionCloner.clone(clone, EnumSet.of(SUPPRESSED));
        clone2.addSuppressed(new RuntimeException("in a teapot"));
        assertEquals(1, clone2.getSuppressed().length);
        assertEquals(1, clone.getSuppressed().length);
        assertEquals(1, x.getSuppressed().length);
    }
}
