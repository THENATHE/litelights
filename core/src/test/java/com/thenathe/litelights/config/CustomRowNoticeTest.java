package com.thenathe.litelights.config;

import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** A notice row shows a live status and is never a setting: nothing to set, reset or count as changed. */
class CustomRowNoticeTest {
    @Test
    void aNoticeShowsItsStatusAndIsNeverChanged() {
        AtomicReference<String> status = new AtomicReference<>("Active");
        CustomRow notice = CustomRow.notice(Component.literal("Some fix"), Component.literal("What it does"),
                () -> Component.literal(status.get()));

        assertEquals(CustomRow.Kind.NOTICE, notice.kind());
        assertFalse(notice.tracked(), "no reset button");
        assertEquals("Active", notice.format(notice.value()).getString());
        status.set("Not needed");
        assertEquals("Not needed", notice.format(notice.value()).getString(), "the status is read each time it is drawn");

        notice.set(1);
        notice.reset();
        assertEquals(0, notice.value());
        assertFalse(notice.isChanged());
    }
}
