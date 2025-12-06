package cz.vsb.minibank.domain.lazy;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Lazy reference to a single entity.
 * The value is loaded and cached on first access.
 */
public final class LazyRef<T> {

    private final Supplier<T> loader;
    private T value;
    private boolean loaded = false;

    public LazyRef(Supplier<T> loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    /**
     * Returns the referenced value, loading it from the supplier once.
     */
    public T get() {
        if (!loaded) {
            value = loader.get();
            loaded = true;
        }
        return value;
    }

    /**
     * Returns true if the value has already been loaded.
     */
    public boolean isLoaded() {
        return loaded;
    }
}
