package org.schabi.newpipe.fragments.list.channel;

import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.SortOption;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntSupplier;

final class ChannelTabSortOptionSelection {
    private ChannelTabSortOptionSelection() {
    }

    @Nullable
    static SortOption resolve(final List<SortOption> options,
                              @Nullable final SortOption committedSelection) {
        if (committedSelection != null) {
            return options.stream()
                    .filter(option -> hasSamePage(option, committedSelection))
                    .findFirst()
                    .orElse(null);
        }

        return options.stream()
                .filter(SortOption::isSelected)
                .findFirst()
                .orElse(null);
    }

    static boolean isChecked(final SortOption option,
                             @Nullable final SortOption committedSelection) {
        return committedSelection != null && hasSamePage(option, committedSelection);
    }

    static boolean hasSamePage(final SortOption first, final SortOption second) {
        final Page firstPage = first.getPage();
        final Page secondPage = second.getPage();
        return Objects.equals(firstPage.getUrl(), secondPage.getUrl())
                && Objects.equals(firstPage.getId(), secondPage.getId())
                && Objects.equals(firstPage.getIds(), secondPage.getIds())
                && Objects.equals(firstPage.getCookies(), secondPage.getCookies())
                && Arrays.equals(firstPage.getBody(), secondPage.getBody());
    }

    static Map<Integer, SortOption> createMenuItemMapping(final List<SortOption> options,
                                                          final IntSupplier idSupplier) {
        final Map<Integer, SortOption> optionsByMenuItemId = new LinkedHashMap<>();
        for (final SortOption option : options) {
            final int itemId = idSupplier.getAsInt();
            if (optionsByMenuItemId.containsKey(itemId)) {
                throw new IllegalArgumentException("Menu item IDs must be unique");
            }
            optionsByMenuItemId.put(itemId, option);
        }
        return optionsByMenuItemId;
    }

    @Nullable
    static Integer findMenuItemId(final Map<Integer, SortOption> optionsByMenuItemId,
                                  final SortOption selectedOption) {
        return optionsByMenuItemId.entrySet().stream()
                .filter(entry -> hasSamePage(entry.getValue(), selectedOption))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }
}
