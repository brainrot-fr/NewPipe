package org.schabi.newpipe.fragments.list.channel;

import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;

import com.evernote.android.state.State;

import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.ChannelTabHeaderBinding;
import org.schabi.newpipe.databinding.PlaylistControlBinding;
import org.schabi.newpipe.error.ErrorInfo;
import org.schabi.newpipe.error.UserAction;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.SortOption;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandlerFactory;
import org.schabi.newpipe.extractor.linkhandler.ReadyChannelTabListLinkHandler;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.fragments.list.BaseListInfoFragment;
import org.schabi.newpipe.fragments.list.playlist.PlaylistControlViewHolder;
import org.schabi.newpipe.player.playqueue.ChannelTabPlayQueue;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.util.ChannelTabHelper;
import org.schabi.newpipe.util.Constants;
import org.schabi.newpipe.util.ExtractorHelper;
import org.schabi.newpipe.util.PlayButtonHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class ChannelTabFragment extends BaseListInfoFragment<InfoItem, ChannelTabInfo>
        implements PlaylistControlViewHolder {
    private static final int LEGACY_SORT_ORDER_MOST_VIEWED = 0;
    private static final int LEGACY_SORT_ORDER_NEWEST = 1;

    // states must be protected and not private for State being able to access them
    @State
    protected ListLinkHandler tabHandler;
    @State
    protected String channelName;
    // Kept under its old saved-state name to safely migrate fragments from client-side sorting.
    @State
    protected int sortOrder = LEGACY_SORT_ORDER_NEWEST;
    @State
    @Nullable
    protected SortOption selectedSortOption;
    @State
    @Nullable
    protected SortOption pendingSortOption;
    @State
    protected boolean hasServerSortedContent;

    private PlaylistControlBinding playlistControlBinding;
    private ChannelTabHeaderBinding channelTabHeaderBinding;
    private long sortPageRequestGeneration;
    private boolean sortPageRequestInProgress;

    /**
     * Replacement for {@code tabHandler} without the raw data it may contain.
     * Created on a background thread as {@code BandcampChannelLinkHandlerFactory}
     * performs network requests, which must not be performed on the main thread.
     */
    @Nullable
    private ListLinkHandler lightweightTabHandler;

    @NonNull
    public static ChannelTabFragment getInstance(final int serviceId,
                                                 final ListLinkHandler tabHandler,
                                                 final String channelName) {
        final ChannelTabFragment instance = new ChannelTabFragment();
        final Bundle arguments = new Bundle();
        arguments.putInt(Constants.KEY_SERVICE_ID, serviceId);
        instance.setArguments(arguments);
        instance.serviceId = serviceId;
        instance.tabHandler = tabHandler;
        instance.channelName = channelName;
        return instance;
    }

    public ChannelTabFragment() {
        super(UserAction.REQUESTED_CHANNEL);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // LifeCycle
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Bundle arguments = getArguments();
        final boolean hasServiceIdArgument = arguments != null
                && arguments.containsKey(Constants.KEY_SERVICE_ID);
        final int argumentServiceId = hasServiceIdArgument
                ? arguments.getInt(Constants.KEY_SERVICE_ID, Constants.NO_SERVICE_ID)
                : Constants.NO_SERVICE_ID;
        serviceId = ChannelTabServiceId.resolve(
                hasServiceIdArgument, argumentServiceId, serviceId);
        setHasOptionsMenu(false);
    }

    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_channel_tab, container, false);
    }

    @Override
    public void onDestroyView() {
        if (sortPageRequestInProgress) {
            invalidateSortPageRequest();
            if (currentWorker != null) {
                currentWorker.dispose();
                currentWorker = null;
            }
            isLoading.set(false);
        }
        super.onDestroyView();
        playlistControlBinding = null;
        channelTabHeaderBinding = null;
    }

    @Override
    public void onPause() {
        super.onPause();
        if (isSortableTab() && isLoading.get()) {
            cancelCurrentRequest();
        }
    }

    @Override
    public void onResume() {
        final boolean hasPendingSortOption = pendingSortOption != null;
        if (hasPendingSortOption) {
            wasLoading.set(false);
        }
        super.onResume();
        if (hasPendingSortOption) {
            retryPendingSortOption();
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void setUserVisibleHint(final boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);
        if (!isSortableTab()) {
            return;
        }

        if (isVisibleToUser) {
            if (getView() != null) {
                doInitialLoadLogic();
            }
        } else if (isLoading.get()) {
            cancelCurrentRequest();
        }
    }

    @Override
    protected Supplier<View> getListHeaderSupplier() {
        if (ChannelTabHelper.isStreamsTab(tabHandler)) {
            if (isSortableTab()) {
                channelTabHeaderBinding = ChannelTabHeaderBinding
                        .inflate(activity.getLayoutInflater(), itemsList, false);
                playlistControlBinding = channelTabHeaderBinding.playlistControl;
                channelTabHeaderBinding.sortButton.setVisibility(View.GONE);
                updateSortButtonText();
                channelTabHeaderBinding.sortButton.setOnClickListener(this::showSortMenu);
                if (currentInfo != null) {
                    updateSortOptions(currentInfo);
                }
                return channelTabHeaderBinding::getRoot;
            }
            playlistControlBinding = PlaylistControlBinding.inflate(
                    activity.getLayoutInflater(), itemsList, false);
            return playlistControlBinding::getRoot;
        }
        return null;
    }

    @Override
    protected Single<ChannelTabInfo> loadResult(final boolean forceLoad) {
        if (!ChannelTabServiceId.isValid(serviceId)) {
            return Single.error(missingServiceIdError());
        }
        return ExtractorHelper.getChannelTab(serviceId, tabHandler, forceLoad)
                // Creating the lightweight handler may perform network requests, so it must not
                // happen on the main thread.
                .doOnSuccess(result ->
                        lightweightTabHandler = createLightweightTabHandler(result, tabHandler));
    }

    @Override
    protected Single<ListExtractor.InfoItemsPage<InfoItem>> loadMoreItemsLogic() {
        if (!ChannelTabServiceId.isValid(serviceId)) {
            return Single.error(missingServiceIdError());
        }
        return ExtractorHelper.getMoreChannelTabItems(serviceId, tabHandler, currentNextPage);
    }

    @Override
    protected void doInitialLoadLogic() {
        if (isSortableTab() && !getUserVisibleHint()) {
            return;
        }
        if (pendingSortOption != null) {
            retryPendingSortOption();
        } else {
            super.doInitialLoadLogic();
        }
    }

    @Override
    public void startLoading(final boolean forceLoad) {
        if (isSortableTab()) {
            invalidateSortPageRequest();
        }
        super.startLoading(forceLoad);
    }

    @Override
    public void readFrom(@NonNull final Queue<Object> savedObjects) throws Exception {
        final boolean isLegacyMostViewed = sortOrder == LEGACY_SORT_ORDER_MOST_VIEWED;
        if (isLegacyMostViewed) {
            setUseDefaultStateSaving(false);
        }
        super.readFrom(savedObjects);
        if (isLegacyMostViewed) {
            sortOrder = LEGACY_SORT_ORDER_NEWEST;
            hasServerSortedContent = false;
            selectedSortOption = null;
            pendingSortOption = null;
            if (currentInfo != null) {
                currentNextPage = currentInfo.getNextPage();
            }
            setUseDefaultStateSaving(true);
        } else {
            sortOrder = LEGACY_SORT_ORDER_NEWEST;
        }
    }

    @Override
    public void setTitle(final String title) {
        // The channel name is displayed as title in the toolbar.
        // The title is always a description of the content of the tab fragment.
        // It should be unique for each channel because multiple channel tabs
        // can be added to the main page. Therefore, the channel name is used.
        // Using the title variable would cause the title to be the same for all channel tabs.
        super.setTitle(channelName);
    }

    /**
     * Creates a link handler with the same properties as {@code handler}, but without the raw
     * data a {@link ReadyChannelTabListLinkHandler} may contain. Must not be called on the main
     * thread, since some services perform network requests to build the link handler.
     *
     * @param result  the loaded tab info, used to get the service's channel tab factory
     * @param handler the link handler that was used to load the tab
     * @return the lightweight link handler, or {@code null} if it is not needed or could not
     *         be created
     */
    @Nullable
    private ListLinkHandler createLightweightTabHandler(final ChannelTabInfo result,
                                                        final ListLinkHandler handler) {
        // FIXME this is a really hacky workaround, to avoid storing useless data in the fragment
        //  state. The problem is, `ReadyChannelTabListLinkHandler` might contain raw JSON data that
        //  uses a lot of memory (e.g. ~800KB for YouTube). While 800KB doesn't seem much, if
        //  you combine just a couple of channel tab fragments you easily go over the 1MB
        //  save&restore transaction limit, and get `TransactionTooLargeException`s. A proper
        //  solution would require rethinking about `ReadyChannelTabListLinkHandler`s.
        if (handler instanceof ReadyChannelTabListLinkHandler) {
            try {
                // once the tab info has been loaded, the parsed data was already saved to cache, so
                // we can discard any raw data in ReadyChannelTabListLinkHandler and create a
                // link handler with identical properties, but without any raw data
                final ListLinkHandlerFactory channelTabLHFactory = result.getService()
                        .getChannelTabLHFactory();
                if (channelTabLHFactory != null) {
                    // some services do not have a ChannelTabLHFactory
                    return channelTabLHFactory.fromQuery(handler.getId(),
                            handler.getContentFilters(), handler.getSortFilter());
                }
            } catch (final ParsingException e) {
                // silently ignore the error, as the app can continue to function normally
                Log.w(TAG, "Could not recreate channel tab handler", e);
            }
        }
        return null;
    }

    @Override
    public void handleResult(@NonNull final ChannelTabInfo result) {
        useLightweightTabHandler();
        if (infoListAdapter.getItemsList().isEmpty()
                && (hasServerSortedContent || pendingSortOption != null)) {
            updateSortOptions(result);
            if (playlistControlBinding != null) {
                updatePlaylistControlVisibility();
                PlayButtonHelper.initPlaylistControlClickListener(
                        activity, playlistControlBinding, this);
            }
            final SortOption optionToRestore = pendingSortOption != null
                    ? pendingSortOption : selectedSortOption;
            if (optionToRestore == null) {
                recoverDefaultOrdering(result, new IllegalStateException(
                        "The saved channel sorting option is unavailable"));
            } else {
                pendingSortOption = optionToRestore;
                loadSortOptionPage(optionToRestore, result);
            }
            return;
        }

        super.handleResult(result);
        updateSortOptions(result);

        if (playlistControlBinding != null) {
            // PlaylistControls should be visible only if there is some item in
            // infoListAdapter other than header
            updatePlaylistControlVisibility();
            PlayButtonHelper.initPlaylistControlClickListener(
                    activity, playlistControlBinding, this);
        }
    }

    private boolean isSortableTab() {
        if (tabHandler == null || tabHandler.getContentFilters().isEmpty()) {
            return false;
        }
        final String tab = tabHandler.getContentFilters().get(0);
        return ChannelTabs.VIDEOS.equals(tab) || ChannelTabs.SHORTS.equals(tab);
    }

    private void cancelCurrentRequest() {
        invalidateSortPageRequest();
        if (currentWorker != null) {
            currentWorker.dispose();
            currentWorker = null;
        }
        isLoading.set(false);
    }

    private void showSortMenu(final View anchor) {
        if (currentInfo == null) {
            return;
        }
        updateSortOptions(currentInfo);
        final List<SortOption> sortOptions = currentInfo.getSortOptions();
        if (sortOptions.isEmpty()) {
            return;
        }

        final SortOption menuSelection = ChannelTabSortOptionSelection.resolve(
                sortOptions, selectedSortOption);
        if (menuSelection == null) {
            showSortPageError(new IllegalStateException(
                    "The committed channel sorting option is unavailable in the menu"));
            return;
        }
        selectedSortOption = menuSelection;
        updateSortButtonText();

        final PopupMenu popupMenu = new PopupMenu(requireContext(), anchor, Gravity.END);
        final Map<Integer, SortOption> optionsByMenuItemId =
                ChannelTabSortOptionSelection.createMenuItemMapping(
                        sortOptions, View::generateViewId);
        int menuOrder = 0;
        for (final Map.Entry<Integer, SortOption> entry : optionsByMenuItemId.entrySet()) {
            popupMenu.getMenu().add(Menu.NONE, entry.getKey(),
                    menuOrder++, entry.getValue().getName());
        }
        popupMenu.getMenu().setGroupCheckable(Menu.NONE, true, true);
        final Integer selectedMenuItemId = ChannelTabSortOptionSelection.findMenuItemId(
                optionsByMenuItemId, menuSelection);
        if (selectedMenuItemId == null) {
            showSortPageError(new IllegalStateException(
                    "The committed channel sorting option has no menu item"));
            return;
        }
        // AppCompat selects an exclusive item even when setChecked(false) is called.
        popupMenu.getMenu().findItem(selectedMenuItemId).setChecked(true);

        popupMenu.setOnMenuItemClickListener(item -> {
            final SortOption selectedOption = optionsByMenuItemId.get(item.getItemId());
            if (selectedOption == null) {
                return false;
            }
            selectSortOption(selectedOption);
            return true;
        });
        popupMenu.show();
    }

    private void selectSortOption(final SortOption option) {
        if (pendingSortOption != null
                && ChannelTabSortOptionSelection.isChecked(option, selectedSortOption)) {
            cancelPendingSortRequest();
            if (infoListAdapter.getItemsList().isEmpty() && selectedSortOption != null) {
                pendingSortOption = selectedSortOption;
                loadSortOptionPage(selectedSortOption, currentInfo);
            }
            return;
        }
        if ((pendingSortOption != null
                && ChannelTabSortOptionSelection.hasSamePage(option, pendingSortOption))
                || (pendingSortOption == null
                && ChannelTabSortOptionSelection.isChecked(option, selectedSortOption))) {
            return;
        }

        pendingSortOption = option;
        loadSortOptionPage(option, null);
    }

    private void updateSortOptions(final ChannelTabInfo info) {
        final List<SortOption> sortOptions = info.getSortOptions();
        if (pendingSortOption != null) {
            final SortOption currentPendingOption = findSortOptionForPage(sortOptions,
                    pendingSortOption);
            if (currentPendingOption != null) {
                pendingSortOption = currentPendingOption;
            }
        }

        if (sortOptions.isEmpty()) {
            if (!hasServerSortedContent) {
                selectedSortOption = null;
            }
        } else {
            final SortOption resolvedSelection = ChannelTabSortOptionSelection.resolve(
                    sortOptions, selectedSortOption);
            if (resolvedSelection != null) {
                selectedSortOption = resolvedSelection;
            }
        }

        if (channelTabHeaderBinding != null) {
            channelTabHeaderBinding.sortButton.setVisibility(
                    sortOptions.isEmpty() ? View.GONE : View.VISIBLE);
            updateSortButtonText();
        }
    }

    private void retryPendingSortOption() {
        if (pendingSortOption == null || sortPageRequestInProgress
                || isLoading.get() || !getUserVisibleHint() || getView() == null) {
            return;
        }

        final ChannelTabInfo fallbackInfo = currentInfo != null
                && infoListAdapter.getItemsList().isEmpty()
                ? currentInfo : null;
        loadSortOptionPage(pendingSortOption, fallbackInfo);
    }

    @Nullable
    private static SortOption findSortOptionForPage(final List<SortOption> sortOptions,
                                                    @Nullable final SortOption selectedOption) {
        if (selectedOption == null) {
            return null;
        }
        return sortOptions.stream()
                .filter(option ->
                        ChannelTabSortOptionSelection.hasSamePage(option, selectedOption))
                .findFirst()
                .orElse(null);
    }

    private void loadSortOptionPage(final SortOption sortOption,
                                    @Nullable final ChannelTabInfo fallbackInfo) {
        invalidateSortPageRequest();
        if (currentWorker != null) {
            currentWorker.dispose();
            currentWorker = null;
        }
        final long requestGeneration = sortPageRequestGeneration;

        if (!ChannelTabServiceId.isValid(serviceId)) {
            pendingSortOption = null;
            showSortPageError(missingServiceIdError());
            return;
        }

        if (!Page.isValid(sortOption.getPage())) {
            final IllegalStateException error = new IllegalStateException(
                    "The channel sorting continuation is no longer valid");
            pendingSortOption = null;
            if (fallbackInfo != null) {
                recoverDefaultOrdering(fallbackInfo, error);
            } else {
                showSortPageError(error);
            }
            return;
        }

        sortPageRequestInProgress = true;
        isLoading.set(true);
        showListFooter(true);

        currentWorker = ExtractorHelper.getMoreChannelTabItems(
                        serviceId, tabHandler, sortOption.getPage())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(page -> {
                    if (!isCurrentSortPageRequest(requestGeneration)) {
                        return;
                    }

                    currentWorker = null;
                    sortPageRequestInProgress = false;
                    isLoading.set(false);
                    hideLoading();
                    selectedSortOption = sortOption;
                    pendingSortOption = null;
                    updateSortButtonText();
                    hasServerSortedContent = true;
                    currentNextPage = page.getNextPage();
                    infoListAdapter.replaceInfoItemList(new ArrayList<>(page.getItems()));
                    showListFooter(hasMoreItems());
                    updatePlaylistControlVisibility();
                    if (itemsList != null) {
                        itemsList.scrollToPosition(0);
                    }
                    if (page.getItems().isEmpty() && !hasMoreItems()) {
                        showEmptyState();
                    }
                    if (!page.getErrors().isEmpty()) {
                        showSnackBarError(new ErrorInfo(page.getErrors(),
                                UserAction.REQUESTED_CHANNEL, "Loading sorted channel tab items",
                                serviceId, url));
                    }
                }, error -> {
                    if (!isCurrentSortPageRequest(requestGeneration)) {
                        return;
                    }

                    currentWorker = null;
                    sortPageRequestInProgress = false;
                    isLoading.set(false);
                    pendingSortOption = null;
                    if (fallbackInfo != null) {
                        recoverDefaultOrdering(fallbackInfo, error);
                    } else {
                        showListFooter(hasMoreItems());
                        showSortPageError(error);
                    }
                });
    }

    private boolean isCurrentSortPageRequest(final long requestGeneration) {
        return requestGeneration == sortPageRequestGeneration
                && sortPageRequestInProgress
                && isAdded()
                && !isRemoving()
                && getView() != null;
    }

    private void invalidateSortPageRequest() {
        sortPageRequestGeneration++;
        sortPageRequestInProgress = false;
    }

    private void cancelPendingSortRequest() {
        invalidateSortPageRequest();
        if (currentWorker != null) {
            currentWorker.dispose();
            currentWorker = null;
        }
        pendingSortOption = null;
        isLoading.set(false);
        showListFooter(hasMoreItems());
    }

    private void recoverDefaultOrdering(final ChannelTabInfo info, final Throwable error) {
        hasServerSortedContent = false;
        selectedSortOption = null;
        pendingSortOption = null;
        currentNextPage = info.getNextPage();
        updateSortOptions(info);
        super.handleResult(info);
        updatePlaylistControlVisibility();
        showSnackBarError(new ErrorInfo(error, UserAction.REQUESTED_CHANNEL,
                "Restoring channel tab sorting: " + url, serviceId, url));
    }

    private void useLightweightTabHandler() {
        // Swap in the lightweight handler prepared in loadResult(), now that we are on main thread.
        if (lightweightTabHandler != null) {
            tabHandler = lightweightTabHandler;
            lightweightTabHandler = null;
        }
    }

    private void updatePlaylistControlVisibility() {
        if (playlistControlBinding != null) {
            playlistControlBinding.getRoot().setVisibility(
                    infoListAdapter.getItemCount() > 1 ? View.VISIBLE : View.GONE);
        }
    }

    private void showSortPageError(final Throwable error) {
        final ErrorInfo errorInfo = new ErrorInfo(error, UserAction.REQUESTED_CHANNEL,
                "Loading sorted channel tab: " + url, serviceId, url);
        if (infoListAdapter.getItemsList().isEmpty()) {
            showError(errorInfo);
        } else {
            showSnackBarError(errorInfo);
        }
    }

    private IllegalStateException missingServiceIdError() {
        return new IllegalStateException("Channel tab fragment is missing its service ID");
    }

    private void updateSortButtonText() {
        if (channelTabHeaderBinding == null) {
            return;
        }
        final String selectedSort = selectedSortOption == null
                ? "" : selectedSortOption.getName();
        channelTabHeaderBinding.sortValue.setText(selectedSort);
        channelTabHeaderBinding.sortButton.setContentDescription(selectedSort.isEmpty()
                ? getString(R.string.channel_tab_sort_label)
                : getString(R.string.channel_tab_sort_by, selectedSort));
    }

    @Override
    public PlayQueue getPlayQueue() {
        final List<StreamInfoItem> streamItems = infoListAdapter.getItemsList().stream()
                .filter(StreamInfoItem.class::isInstance)
                .map(StreamInfoItem.class::cast)
                .collect(Collectors.toList());
        return new ChannelTabPlayQueue(currentInfo.getServiceId(), tabHandler,
                currentNextPage, streamItems, 0, hasServerSortedContent);
    }
}
