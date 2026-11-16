package com.thenathe.litelights.render.feature.lightpost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.item.ItemDisplayContext;
import org.junit.jupiter.api.Test;

class OutlineMarkersTest {
    @Test
    void vanillasFirstPersonItemsAreHeldWhetherOrNotTheHandIsBeingSubmitted() {
        for (boolean submittingHand : new boolean[] {false, true}) {
            assertTrue(OutlineMarkers.heldInFirstPerson(ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, submittingHand));
            assertTrue(OutlineMarkers.heldInFirstPerson(ItemDisplayContext.FIRST_PERSON_LEFT_HAND, submittingHand));
        }
    }

    @Test
    void anAnimationModsArmModelHoldsItsItemsInThirdPersonContextsInTheHand() {
        assertTrue(OutlineMarkers.heldInFirstPerson(ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, true));
        assertTrue(OutlineMarkers.heldInFirstPerson(ItemDisplayContext.THIRD_PERSON_LEFT_HAND, true));
    }

    @Test
    void theInventorysPlayerPreviewIsNotTheFirstPersonHand() {
        assertFalse(OutlineMarkers.heldInFirstPerson(ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, false));
        assertFalse(OutlineMarkers.heldInFirstPerson(ItemDisplayContext.THIRD_PERSON_LEFT_HAND, false));
    }

    @Test
    void onlyHandContextsCountEvenWhileTheHandIsBeingSubmitted() {
        for (ItemDisplayContext context : ItemDisplayContext.values()) {
            boolean hand = context.firstPerson() || context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                || context == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
            assertEquals(hand, OutlineMarkers.heldInFirstPerson(context, true), context.name());
            assertEquals(context.firstPerson(), OutlineMarkers.heldInFirstPerson(context, false), context.name());
        }
    }
}
