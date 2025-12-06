package cz.vsb.minibank.domain.lazy;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Lazy-loaded list of entities.
 * The underlying list is loaded and cached on first access.
 */
public final class LazyList<T> implements Iterable<T> {

    private final Supplier<List<T>> loader;
    private List<T> value;
    private boolean loaded = false;

    public LazyList(Supplier<List<T>> loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    /**
     * Returns all items, loading them from the supplier on first call.
     */
    public List<T> getAll() {
        if (!loaded) {
            List<T> list = loader.get();
            if (list == null) {
                value = List.of();
            } else {
                value = Collections.unmodifiableList(list);
            }
            loaded = true;
        }
        return value;
    }

    /**
     * Returns true if the list has already been loaded.
     */
    public boolean isLoaded() {
        return loaded;
    }

    @Override
    public Iterator<T> iterator() {
        return getAll().iterator();
    }
}
