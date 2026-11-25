package com.thenathe.litelights.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.thenathe.litelights.LiteLightsClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Every registered option, in registration order, plus the stored values that no registered
 * option has claimed yet.
 *
 * <p>Stored values are kept until something claims them and are written back on every save, so
 * an option from a module that isn't loaded, or from a newer LiteLights, never loses its value.
 * A stored value that can't be read as its option's type is kept too, under "unreadable", and
 * the option starts at its default.
 */
public final class OptionRegistry {

    private final Map<String, Option<?>> options = new LinkedHashMap<>();
    private final Map<String, JsonElement> unclaimed = new LinkedHashMap<>();
    private final Map<String, JsonElement> unreadable = new LinkedHashMap<>();
    private final List<Consumer<Option<?>>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicLong changes = new AtomicLong();
    private volatile boolean dirty;

    /** Adds an option and gives it its stored value, if there is one. Ids must be unique. */
    public synchronized <O extends Option<?>> O register(O option) {
        if (options.containsKey(option.id())) {
            throw new IllegalArgumentException("Option " + option.id() + " is registered twice");
        }
        option.attach(this);
        options.put(option.id(), option);
        JsonElement stored = unclaimed.remove(option.id());
        if (stored != null) {
            claim(option, stored);
        }
        return option;
    }

    private void claim(Option<?> option, JsonElement stored) {
        try {
            option.load(stored);
        } catch (IllegalArgumentException unreadableValue) {
            LiteLightsClient.LOG.warn("Couldn't read the saved value of {} ({}); using the default and keeping the old value in the file",
                    option.id(), unreadableValue.getMessage());
            unreadable.put(option.id(), stored);
        }
    }

    public synchronized Option<?> get(String id) {
        return options.get(id);
    }

    public synchronized List<Option<?>> all() {
        return List.copyOf(options.values());
    }

    public synchronized List<Option<?>> onPage(String page) {
        List<Option<?>> found = new ArrayList<>();
        for (Option<?> option : options.values()) {
            if (option.page().equals(page)) {
                found.add(option);
            }
        }
        return found;
    }

    /** Runs after any registered option changes, on the thread that changed it. */
    public void addListener(Consumer<Option<?>> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<Option<?>> listener) {
        listeners.remove(listener);
    }

    /**
     * A number that goes up on every change. A renderer can keep the number its snapshot was
     * built from and rebuild only when this differs, without registering a listener.
     */
    public long changeCount() {
        return changes.get();
    }

    public boolean isDirty() {
        return dirty;
    }

    void markSaved() {
        dirty = false;
    }

    void fireChanged(Option<?> option) {
        changes.incrementAndGet();
        dirty = true;
        for (Consumer<Option<?>> listener : listeners) {
            listener.accept(option);
        }
    }

    public synchronized int changedCount() {
        int count = 0;
        for (Option<?> option : options.values()) {
            if (option.isChanged()) {
                count++;
            }
        }
        return count;
    }

    /** Puts every registered option back to its default. */
    public void resetAll() {
        for (Option<?> option : all()) {
            option.reset();
        }
    }

    /** Takes the "options" and "unreadable" objects of a loaded file. */
    synchronized void acceptStored(JsonObject storedOptions, JsonObject storedUnreadable) {
        unclaimed.clear();
        unreadable.clear();
        if (storedUnreadable != null) {
            storedUnreadable.entrySet().forEach(entry -> unreadable.put(entry.getKey(), entry.getValue()));
        }
        if (storedOptions == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : storedOptions.entrySet()) {
            Option<?> option = options.get(entry.getKey());
            if (option == null) {
                unclaimed.put(entry.getKey(), entry.getValue());
            } else {
                // A value that reads now replaces an unreadable one kept from before.
                unreadable.remove(entry.getKey());
                claim(option, entry.getValue());
            }
        }
    }

    /** Every value to save: registered options first, then values kept for options not registered. */
    synchronized JsonObject storedOptions() {
        JsonObject out = new JsonObject();
        for (Option<?> option : options.values()) {
            out.add(option.id(), option.writeCurrent());
        }
        unclaimed.forEach((id, value) -> out.add(id, value.deepCopy()));
        return out;
    }

    /**
     * Unreadable values, minus those whose option the player has since changed: once they pick a
     * new value on purpose, the old unreadable one has nothing left to protect.
     */
    synchronized JsonObject storedUnreadable() {
        JsonObject out = new JsonObject();
        unreadable.forEach((id, value) -> {
            Option<?> option = options.get(id);
            if (option == null || !option.isChanged()) {
                out.add(id, value.deepCopy());
            }
        });
        return out;
    }
}
