package cz.vsb.minibank.domain.lazy;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Lazy reference to a single entity.
 * Value is loaded on first access via the supplied loader.
 */
public final class LazyRef<T> {

    private final Supplier<T> loader;
    private T value;
    private boolean loaded = false;

    public LazyRef(Supplier<T> loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    public T get() {
        if (!loaded) {
            value = loader.get();
            loaded = true;
        }
        return value;
    }

    public boolean isLoaded() {
        return loaded;
    }
}
