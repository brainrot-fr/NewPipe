package org.schabi.newpipe.player.playqueue;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.util.ExtractorHelper;

import java.util.Collections;
import java.util.List;

import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

public class ChannelTabPlayQueueTest {
    private static final int SERVICE_ID = 0;

    @BeforeClass
    public static void useTrampolineSchedulers() {
        RxJavaPlugins.setIoSchedulerHandler(scheduler -> Schedulers.trampoline());
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(
                schedulerCallable -> Schedulers.trampoline());
    }

    @AfterClass
    public static void resetSchedulers() {
        RxJavaPlugins.reset();
        RxAndroidPlugins.reset();
    }

    @Test
    public void nonEmptySortedQueuePreservesItemsAndFetchesContinuation() {
        final ListLinkHandler handler = linkHandler();
        final Page sortedNextPage = nextPage();
        final List<StreamInfoItem> streams = List.of(
                streamItem("https://example.com/popular-1"),
                streamItem("https://example.com/popular-2"));
        final ChannelTabPlayQueue queue = new ChannelTabPlayQueue(
                SERVICE_ID, handler, sortedNextPage, streams, 0, true);

        assertEquals(2, queue.size());
        assertEquals(streams.get(0).getUrl(), queue.getItem(0).getUrl());
        assertEquals(streams.get(1).getUrl(), queue.getItem(1).getUrl());
        assertFalse(queue.isComplete());

        try (MockedStatic<ExtractorHelper> extractorHelper = mockStatic(ExtractorHelper.class)) {
            extractorHelper.when(() ->
                    ExtractorHelper.getMoreChannelTabItems(SERVICE_ID, handler, sortedNextPage))
                    .thenReturn(Single.never());

            queue.fetch();
            extractorHelper.verify(() -> ExtractorHelper.getMoreChannelTabItems(
                    SERVICE_ID, handler, sortedNextPage));
            queue.dispose();
        }
    }

    @Test
    public void emptySortedQueueFetchesItsValidContinuation() {
        final ListLinkHandler handler = linkHandler();
        final Page sortedNextPage = nextPage();
        final ChannelTabPlayQueue queue = new ChannelTabPlayQueue(
                SERVICE_ID, handler, sortedNextPage, Collections.emptyList(), 0, true);

        assertFalse(queue.isInitial);
        assertFalse(queue.isComplete());
        assertSame(sortedNextPage, queue.nextPage);

        try (MockedStatic<ExtractorHelper> extractorHelper = mockStatic(ExtractorHelper.class)) {
            extractorHelper.when(() ->
                    ExtractorHelper.getMoreChannelTabItems(SERVICE_ID, handler, sortedNextPage))
                    .thenReturn(Single.never());

            queue.fetch();
            extractorHelper.verify(() -> ExtractorHelper.getMoreChannelTabItems(
                    SERVICE_ID, handler, sortedNextPage));
            queue.dispose();
        }
    }

    @Test
    public void emptySortedQueueWithoutContinuationIsCompleteAndNeverFallsBack() {
        try (MockedStatic<ExtractorHelper> extractorHelper = mockStatic(ExtractorHelper.class)) {
            for (final Page page : new Page[]{null, new Page("")}) {
                final ChannelTabPlayQueue queue = new ChannelTabPlayQueue(
                        SERVICE_ID, linkHandler(), page, Collections.emptyList(), 0, true);

                assertFalse(queue.isInitial);
                assertTrue(queue.isComplete());
                queue.fetch();
            }
            extractorHelper.verifyNoInteractions();
        }
    }

    @Test
    public void legacyEmptyQueueUsesInitialChannelFetch() {
        final ListLinkHandler handler = linkHandler();
        final ChannelTabPlayQueue queue = new ChannelTabPlayQueue(SERVICE_ID, handler);

        assertTrue(queue.isInitial);
        assertFalse(queue.isComplete());

        try (MockedStatic<ExtractorHelper> extractorHelper = mockStatic(ExtractorHelper.class)) {
            extractorHelper.when(() -> ExtractorHelper.getChannelTab(SERVICE_ID, handler, false))
                    .thenReturn(Single.never());

            queue.fetch();
            extractorHelper.verify(() ->
                    ExtractorHelper.getChannelTab(SERVICE_ID, handler, false));
            queue.dispose();
        }
    }

    private static Page nextPage() {
        return new Page("https://example.com/continuation");
    }

    private static StreamInfoItem streamItem(final String url) {
        return new StreamInfoItem(SERVICE_ID, url, url, StreamType.VIDEO_STREAM);
    }

    private static ListLinkHandler linkHandler() {
        final ListLinkHandler handler = mock(ListLinkHandler.class);
        when(handler.getUrl()).thenReturn("https://example.com/channel");
        return handler;
    }
}
