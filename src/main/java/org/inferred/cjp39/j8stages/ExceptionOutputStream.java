package org.inferred.cjp39.j8stages;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

class ExceptionOutputStream extends ObjectOutputStream {

    public enum ExceptionFields {
        CAUSE, STACK_TRACE, SUPPRESSED;
    }

    private final ByteArrayOutputStream bytes;
    private final Throwable x;
    private final Set<ExceptionOutputStream.ExceptionFields> discard;
    private final Map<ExceptionOutputStream.Alias, Object> members = new LinkedHashMap<>();
    private int nextId = 0;

    ExceptionOutputStream(Throwable x, Set<ExceptionOutputStream.ExceptionFields> discard) throws IOException {
        this(x, discard, new ByteArrayOutputStream());
    }

    private ExceptionOutputStream(
            Throwable x,
            Set<ExceptionOutputStream.ExceptionFields> discard,
            ByteArrayOutputStream bytes) throws IOException {
        super(bytes);
        this.bytes = bytes;
        this.x = x;
        this.discard = discard;
        try {
            /*
             * As an optimization, we do not serialize the exception's members.
             * If the security manager prevents us, however, we can fall back to
             * deep cloning, provided the discard set is empty.
             */
            super.enableReplaceObject(true);
        } catch (SecurityException e) {
            if (!discard.isEmpty()) {
                throw e;
            }
        }
    }

    private static final class Alias implements Serializable {
        private static final long serialVersionUID = 2220201326580353849L;
        private final int id;

        public Alias(int id) {
            this.id = id;
        }

        @Override
        public int hashCode() {
            return id;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof ExceptionOutputStream.Alias)) {
                return false;
            }
            ExceptionOutputStream.Alias other = (ExceptionOutputStream.Alias) obj;
            return (id == other.id);
        }
    }

    @Override
    protected Object replaceObject(Object obj) {
        if (obj == x) {
            return obj;
        }
        if (obj instanceof Throwable) {
            if (obj == x.getCause() && discard.contains(ExceptionOutputStream.ExceptionFields.CAUSE)) {
                return x;
            }
        } else if (obj instanceof StackTraceElement[]) {
            if (discard.contains(ExceptionOutputStream.ExceptionFields.STACK_TRACE)) {
                return null;
            }
        } else if (obj instanceof List) {
            if (discard.contains(ExceptionOutputStream.ExceptionFields.SUPPRESSED)) {
                return new ArrayList<Throwable>();
            }
        }
        ExceptionOutputStream.Alias alias = new Alias(nextId++);
        members.put(alias, obj);
        return alias;
    }

    class ExceptionInputStream extends ObjectInputStream {
        ExceptionInputStream() throws IOException {
            super(new ByteArrayInputStream(bytes.toByteArray()));
            super.enableResolveObject(true);
        }

        @Override
        protected Object resolveObject(Object obj) {
            return members.getOrDefault(obj, obj);
        }
    }
}
