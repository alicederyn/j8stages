package org.inferred.cjp39.j8stages;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;

public class ExceptionCloner {

    /**
     * Returns a clone of x. This clone may be shallow or deep, depending on
     * Java's security settings.
     */
    public static <X extends Throwable> X clone(X x) {
        try {
            ExceptionOutputStream out = new ExceptionOutputStream(x);
            out.writeObject(x);
            out.close();
            ExceptionOutputStream.ExceptionInputStream in = out.new ExceptionInputStream();
            @SuppressWarnings("unchecked")
            X clone = (X) in.readObject();
            in.close();
            return clone;
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private static class ExceptionOutputStream extends ObjectOutputStream {

        final ByteArrayOutputStream bytes;
        final Throwable x;
        final List<Object> members = new ArrayList<>();

        ExceptionOutputStream(Throwable x) throws IOException {
            this(x, new ByteArrayOutputStream());
        }

        private ExceptionOutputStream(Throwable x, ByteArrayOutputStream bytes) throws IOException {
            super(bytes);
            this.bytes = bytes;
            this.x = x;
            try {
                /*
                 * As an optimization, we do not serialize the exception's
                 * members. If the security manager prevents us, however, we can
                 * fall back to deep cloning.
                 */
                super.enableReplaceObject(true);
            } catch (SecurityException e) {}
        }

        @Override
        protected Object replaceObject(Object obj) {
            if (obj == x) {
                return obj;
            }
            members.add(obj);
            return (members.size() - 1);
        }

        class ExceptionInputStream extends ObjectInputStream {
            ExceptionInputStream() throws IOException {
                super(new ByteArrayInputStream(bytes.toByteArray()));
                super.enableResolveObject(true);
            }

            @Override
            protected Object resolveObject(Object obj) {
                if (obj instanceof Integer && !members.isEmpty()) {
                    return members.get((Integer) obj);
                }
                return obj;
            }
        }
    }

    private ExceptionCloner() {}
}
