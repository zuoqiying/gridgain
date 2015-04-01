/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.cache.ttl;

import org.gridgain.grid.cache.*;

/**
 * TTL test with offheap.
 */
public class GridCacheTtlOffheapTransactionalLocalSelfTest extends GridCacheTtlOffheapTransactionalAbstractSelfTest {
    /** {@inheritDoc} */
    @Override protected GridCacheMode cacheMode() {
        return GridCacheMode.LOCAL;
    }

    /** {@inheritDoc} */
    @Override protected int gridCount() {
        return 1;
    }
}