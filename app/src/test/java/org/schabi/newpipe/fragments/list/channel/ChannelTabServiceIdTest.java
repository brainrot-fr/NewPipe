package org.schabi.newpipe.fragments.list.channel;

import org.junit.Test;
import org.schabi.newpipe.util.Constants;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChannelTabServiceIdTest {
    @Test
    public void argumentServiceIdSurvivesMissingOrStaleRestoredState() {
        assertEquals(7, ChannelTabServiceId.resolve(true, 7, Constants.NO_SERVICE_ID));
        assertEquals(12, ChannelTabServiceId.resolve(true, 12, 7));
    }

    @Test
    public void restoredStateRemainsFallbackWhenNoArgumentExists() {
        assertEquals(7, ChannelTabServiceId.resolve(false, Constants.NO_SERVICE_ID, 7));
    }

    @Test
    public void missingServiceIdIsRejected() {
        assertFalse(ChannelTabServiceId.isValid(Constants.NO_SERVICE_ID));
        assertTrue(ChannelTabServiceId.isValid(7));
    }
}
