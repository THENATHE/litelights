package com.thenathe.litelights.render.modules;

import com.thenathe.litelights.config.CustomRow;
import com.thenathe.litelights.api.ModuleSettings;
import com.thenathe.litelights.config.BoolOption;
import com.thenathe.litelights.config.Cost;
import com.thenathe.litelights.config.EnumOption;
import com.thenathe.litelights.config.IntOption;
import com.thenathe.litelights.config.Option;
import com.thenathe.litelights.config.PageContent;
import com.thenathe.litelights.config.Pages;
import com.thenathe.litelights.config.ValueFormat;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The modules' settings rows, made into core's own options (config.BoolOption and the rest), so they
 * save, show and report their state exactly like core's.
 */
final class CoreModuleSettings implements ModuleSettings {
    /** The core switch behind each toggle a module was handed, for {@link SettingRow#needs}. */
    private final Map<ToggleSetting, BoolOption> toggles = new IdentityHashMap<>();

    @Override
    public SettingRow row(String id) {
        return new Row(id);
    }

    @Override
    public void contribute(String page, Consumer<PageRows> contributor) {
        Pages.contribute(page, content -> contributor.accept(new Rows(content)));
    }

    private final class Row implements SettingRow {
        private final String id;
        private @Nullable String page;
        private @Nullable String section;
        private Cost cost = Cost.FREE;
        private @Nullable String feature;
        private @Nullable BoolOption needs;
        private @Nullable String tag;
        private ValueFormat format = ValueFormat.PLAIN;

        private Row(String id) {
            this.id = id;
        }

        @Override
        public SettingRow at(String page, String section) {
            this.page = page;
            this.section = section;
            return this;
        }

        @Override
        public SettingRow cost(RowCost cost) {
            this.cost = Cost.valueOf(cost.name());
            return this;
        }

        @Override
        public SettingRow feature(String featureId) {
            this.feature = featureId;
            return this;
        }

        @Override
        public SettingRow needs(ToggleSetting parent) {
            BoolOption option = toggles.get(parent);
            if (option == null) {
                throw new IllegalArgumentException("Not a toggle made through these settings: " + parent.id());
            }
            this.needs = option;
            return this;
        }

        @Override
        public SettingRow tag(String translationKey) {
            this.tag = translationKey;
            return this;
        }

        @Override
        public SettingRow format(NumberFormat format) {
            this.format = ValueFormat.valueOf(format.name());
            return this;
        }

        private <T, O extends Option<T>, B extends Option.Builder<T, O, B>> O register(B builder) {
            if (page == null || section == null) {
                throw new IllegalStateException("Row " + id + " has no page: call at(page, section) first");
            }
            builder.at(page, section).cost(cost);
            if (feature != null) {
                builder.feature(feature);
            }
            if (needs != null) {
                builder.needs(needs);
            }
            if (tag != null) {
                builder.tag(tag);
            }
            return builder.register();
        }

        @Override
        public ToggleSetting toggle(boolean defaultValue) {
            BoolOption option = register(BoolOption.builder(id, defaultValue));
            ToggleSetting setting = new Toggle(option);
            toggles.put(setting, option);
            return setting;
        }

        @Override
        public NumberSetting number(int defaultValue, int min, int max, int step) {
            return new Number(register(IntOption.builder(id, defaultValue, min, max, step).format(format)));
        }

        @Override
        public <E extends Enum<E>> ChoiceSetting<E> choice(Class<E> type, E defaultValue) {
            return new Choice<>(register(EnumOption.builder(id, type, defaultValue)));
        }
    }

    /** One of core's options seen as a module's setting. */
    private abstract static class Wrapped<T> implements Setting<T> {
        final Option<T> option;

        Wrapped(Option<T> option) {
            this.option = option;
        }

        @Override
        public String id() {
            return option.id();
        }

        @Override
        public T get() {
            return option.get();
        }

        @Override
        public void set(T value) {
            option.set(value);
        }

        @Override
        public T defaultValue() {
            return option.defaultValue();
        }

        @Override
        public void onChange(Runnable listener) {
            option.addListener((oldValue, newValue) -> listener.run());
        }
    }

    private static final class Toggle extends Wrapped<Boolean> implements ToggleSetting {
        private final BoolOption bool;

        Toggle(BoolOption option) {
            super(option);
            this.bool = option;
        }

        @Override
        public boolean on() {
            return bool.on();
        }
    }

    private static final class Number extends Wrapped<Integer> implements NumberSetting {
        private final IntOption number;

        Number(IntOption option) {
            super(option);
            this.number = option;
        }

        @Override
        public int value() {
            return number.value();
        }
    }

    private static final class Choice<E extends Enum<E>> extends Wrapped<E> implements ChoiceSetting<E> {
        Choice(EnumOption<E> option) {
            super(option);
        }
    }

    private static final class Rows implements PageRows {
        private final PageContent content;

        Rows(PageContent content) {
            this.content = content;
        }

        @Override
        public PageRows section(String section) {
            content.section(section);
            return this;
        }

        @Override
        public PageRows note(Component text) {
            content.note(text);
            return this;
        }

        @Override
        public PageRows action(Component label, Component description, Component button, Runnable action) {
            content.action(label, description, button, action);
            return this;
        }

        @Override
        public PageRows confirmedAction(Component label, Component description, Component button, Runnable action) {
            content.confirmedAction(label, description, button, action);
            return this;
        }

        @Override
        public PageRows status(Component label, Component description, Supplier<Component> status) {
            content.row(CustomRow.notice(label, description, status));
            return this;
        }

        @Override
        public PageRows status(Component label, Component description, Supplier<Component> status, Supplier<Component> detail,
                               BooleanSupplier good) {
            content.row(CustomRow.notice(label, description, status).detail(detail).goodWhen(good));
            return this;
        }
    }
}
