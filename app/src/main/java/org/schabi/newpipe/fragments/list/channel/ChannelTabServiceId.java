package org.schabi.newpipe.fragments.list.channel;

import org.schabi.newpipe.util.Constants;

final class ChannelTabServiceId {
    private ChannelTabServiceId() {
    }

    static int resolve(final boolean hasArgument,
                       final int argumentServiceId,
                       final int restoredStateServiceId) {
        return hasArgument ? argumentServiceId : restoredStateServiceId;
    }

    static boolean isValid(final int serviceId) {
        return serviceId != Constants.NO_SERVICE_ID;
    }
}
