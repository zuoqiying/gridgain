/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.cache;

import org.gridgain.grid.*;
import org.gridgain.grid.dr.*;
import org.gridgain.grid.dr.cache.receiver.*;
import org.gridgain.grid.kernal.processors.cache.dr.*;
import org.gridgain.grid.kernal.processors.dr.*;

import static org.gridgain.grid.dr.cache.receiver.GridDrReceiverCacheConflictResolverMode.*;

/**
 * Real conflict resolver.
 */
public class GridCacheRealConflictResolver implements GridCacheConflictResolver {
    /** Mode. */
    private final GridDrReceiverCacheConflictResolverMode mode;

    /** Resolver. */
    private final GridDrReceiverCacheConflictResolver rslvr;

    /**
     * Constructor.
     *
     * @param mode Mode.
     * @param rslvr Resolver.
     */
    public GridCacheRealConflictResolver(GridDrReceiverCacheConflictResolverMode mode,
        GridDrReceiverCacheConflictResolver rslvr) {
        assert mode != null;

        this.mode = mode;
        this.rslvr = rslvr;
    }

    /** {@inheritDoc} */
    @Override public boolean needResolve(GridCacheVersion oldVer, GridCacheVersion newVer) {
        return true;
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public <K, V> GridDrReceiverConflictContextImpl<K, V> resolve(GridDrEntryEx<K, V> oldEntry,
        GridDrEntry<K, V> newEntry) throws GridException {
        GridDrReceiverConflictContextImpl<K, V> ctx = new GridDrReceiverConflictContextImpl<>(oldEntry, newEntry);

        if (newEntry.dataCenterId() != oldEntry.dataCenterId() || mode == DR_ALWAYS) {
            assert mode == DR_ALWAYS && rslvr != null || mode == DR_AUTO :
                "Invalid resolver configuration (must be checked on startup) [mode=" + mode + ", rslvr=" + rslvr + ']';

            if (rslvr != null)
                // Try falling back to user resolver.
                rslvr.resolve(ctx);
            else
                // No other option, but to use new entry.
                ctx.useNew();
        }
        else {
            // Resolve the conflict automatically.
            long topVerDiff = newEntry.topologyVersion() - oldEntry.topologyVersion();

            if (topVerDiff > 0)
                ctx.useNew();
            else if (topVerDiff < 0)
                ctx.useOld();
            else if (newEntry.order() > oldEntry.order())
                ctx.useNew();
            else
                ctx.useOld();
        }

        return ctx;
    }
}