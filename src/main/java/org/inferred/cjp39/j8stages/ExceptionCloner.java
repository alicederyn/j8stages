package org.inferred.cjp39.j8stages;

import java.io.IOException;
import java.util.EnumSet;
import java.util.Set;

public class ExceptionCloner {

    /**
     * Returns a clone of {@code x}. This clone may be shallow or deep,
     * depending on Java's security settings.
     */
    public static <X extends Throwable> X clone(X x) {
        return clone(x, EnumSet.noneOf(ExceptionOutputStream.ExceptionFields.class));
    }

    /**
     * Returns a partial or full clone of {@code x}. Full clones may be shallow
     * or deep, depending on Java's security settings.
     *
     * @param x
     *            the exception to clone
     * @param the
     *            fields to discard on {@code x}
     * @throws SecurityException
     *             if {@code discard} is not empty but Java's security settings
     *             prevent us replacing fields
     */
    public static <X extends Throwable> X clone(X x, Set<ExceptionOutputStream.ExceptionFields> discard) {
        try {
            ExceptionOutputStream out = new ExceptionOutputStream(x, discard);
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

    private ExceptionCloner() {}
}
