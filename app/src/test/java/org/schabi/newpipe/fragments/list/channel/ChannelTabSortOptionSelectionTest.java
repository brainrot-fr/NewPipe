package org.schabi.newpipe.fragments.list.channel;

import org.junit.Test;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.SortOption;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ChannelTabSortOptionSelectionTest {
    private final SortOption latest = option("Latest", "latest-token", true);
    private final SortOption popular = option("Popular", "popular-token", false);
    private final SortOption oldest = option("Oldest", "oldest-token", false);
    private final List<SortOption> options = List.of(latest, popular, oldest);

    @Test
    public void resolvesServerSelectedOptionOnlyWithoutCommittedSelection() {
        final SortOption resolved = ChannelTabSortOptionSelection.resolve(options, null);

        assertSame(latest, resolved);
        assertTrue(ChannelTabSortOptionSelection.isChecked(latest, resolved));
        assertFalse(ChannelTabSortOptionSelection.isChecked(popular, resolved));
        assertFalse(ChannelTabSortOptionSelection.isChecked(oldest, resolved));
    }

    @Test
    public void committedSelectionOverridesStaleServerSelectedFlag() {
        final SortOption resolved = ChannelTabSortOptionSelection.resolve(options, popular);

        assertSame(popular, resolved);
        assertFalse(ChannelTabSortOptionSelection.isChecked(latest, resolved));
        assertTrue(ChannelTabSortOptionSelection.isChecked(popular, resolved));
        assertFalse(ChannelTabSortOptionSelection.isChecked(oldest, resolved));
    }

    @Test
    public void committedOldestSelectionIsCheckedAfterMenuIsReopened() {
        final SortOption resolved = ChannelTabSortOptionSelection.resolve(options, oldest);
        final SortOption reopened = ChannelTabSortOptionSelection.resolve(options, resolved);

        assertSame(oldest, reopened);
        assertTrue(ChannelTabSortOptionSelection.isChecked(oldest, reopened));
        assertFalse(ChannelTabSortOptionSelection.isChecked(latest, reopened));
    }

    @Test
    public void restoresCommittedSelectionByContinuationRatherThanObjectIdentity() {
        final SortOption restoredPopular = option("Translated name", "popular-token", false);

        final SortOption resolved = ChannelTabSortOptionSelection.resolve(options, restoredPopular);

        assertSame(popular, resolved);
        assertTrue(ChannelTabSortOptionSelection.isChecked(popular, resolved));
    }

    @Test
    public void pendingOptionIsNotCheckedAsCommitted() {
        final SortOption pendingOldest = option("Oldest", "oldest-token", true);
        final SortOption committed = ChannelTabSortOptionSelection.resolve(options, popular);

        assertSame(popular, committed);
        assertTrue(ChannelTabSortOptionSelection.isChecked(popular, committed));
        assertFalse(ChannelTabSortOptionSelection.isChecked(pendingOldest, committed));
    }

    @Test
    public void unavailableCommittedOptionDoesNotFallBackToServerSelectedOption() {
        final SortOption unavailable = option("Popular", "unavailable-token", false);

        assertTrue(ChannelTabSortOptionSelection.resolve(options, unavailable) == null);
    }

    @Test
    public void menuItemIdsMapToTheirAssociatedSortOptions() {
        final AtomicInteger nextId = new AtomicInteger(100);
        final Map<Integer, SortOption> menuItems =
                ChannelTabSortOptionSelection.createMenuItemMapping(
                        options, nextId::incrementAndGet);

        assertSame(latest, menuItems.get(101));
        assertSame(popular, menuItems.get(102));
        assertSame(oldest, menuItems.get(103));
        assertEquals(Integer.valueOf(102),
                ChannelTabSortOptionSelection.findMenuItemId(menuItems, popular));
    }

    private static SortOption option(final String name,
                                     final String continuation,
                                     final boolean selected) {
        return new SortOption(null, name, new Page("https://example.com/" + continuation),
                selected);
    }
}
