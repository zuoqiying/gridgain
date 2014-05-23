/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.ggfs;

import org.gridgain.grid.*;
import org.gridgain.grid.cache.*;
import org.gridgain.grid.ggfs.*;
import org.gridgain.grid.kernal.*;
import org.gridgain.grid.kernal.processors.port.*;
import org.gridgain.grid.spi.discovery.tcp.*;
import org.gridgain.grid.spi.discovery.tcp.ipfinder.*;
import org.gridgain.grid.spi.discovery.tcp.ipfinder.vm.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.gridgain.grid.util.ipc.loopback.*;
import org.gridgain.grid.util.ipc.shmem.*;
import org.gridgain.testframework.junits.common.*;
import org.jetbrains.annotations.*;

import java.util.*;
import java.util.concurrent.atomic.*;

import static org.gridgain.grid.cache.GridCacheAtomicityMode.*;
import static org.gridgain.grid.ggfs.GridGgfsConfiguration.*;

/**
 * Base test class for {@link GridGgfsServer} checking IPC endpoint registrations.
 */
public abstract class GridGgfsServerManagerIpcEndpointRegistrationAbstractSelfTest extends GridCommonAbstractTest {
    /** IP finder. */
    private static final GridTcpDiscoveryIpFinder IP_FINDER = new GridTcpDiscoveryVmIpFinder(true);

    private static final AtomicInteger mgmtPort = new AtomicInteger(DFLT_MGMT_PORT);

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        stopAllGrids();
    }

    /**
     * @throws Exception If failed.
     */
    public void testLoopbackEndpointsRegistration() throws Exception {
        GridConfiguration cfg = gridConfiguration();

        cfg.setGgfsConfiguration(
            gridGgfsConfiguration("{type:'tcp', port:" + DFLT_IPC_PORT + "}")
        );

        G.start(cfg);

        T2<Integer, Integer> res = checkRegisteredIpcEndpoints();

        // One regular enpoint + one management endpoint.
        assertEquals(2, res.get1().intValue());
        assertEquals(0, res.get2().intValue());
    }

    /**
     * @throws Exception If failed.
     */
    public void testLoopbackEndpointsCustomHostRegistration() throws Exception {
        GridConfiguration cfg = gridConfiguration();

        cfg.setGgfsConfiguration(
            gridGgfsConfiguration("{type:'tcp', port:" + DFLT_IPC_PORT + ", host:'127.0.0.1'}"),
            gridGgfsConfiguration("{type:'tcp', port:" + (DFLT_IPC_PORT + 1) + ", host:'" +
                U.getLocalHost().getHostName() + "'}"));

        G.start(cfg);

        T2<Integer, Integer> res = checkRegisteredIpcEndpoints();

        // Two regular endpoints + two management endpoints.
        assertEquals(4, res.get1().intValue());
        assertEquals(0, res.get2().intValue());
    }

    /**
     * Counts all registered IPC endpoints.
     *
     * @return Tuple2 where (tcp endpoints count, shmem endpoints count).
     */
    protected T2<Integer, Integer> checkRegisteredIpcEndpoints() throws Exception {
        GridKernalContext ctx = ((GridKernal)grid()).context();

        int tcp = 0;
        int shmem = 0;

        for (GridPortRecord record : ctx.ports().records()) {
            if (record.clazz() == GridIpcSharedMemoryServerEndpoint.class)
                shmem++;
            else if (record.clazz() == GridIpcServerTcpEndpoint.class)
                tcp++;
        }

        return new T2<>(tcp, shmem);
    }

    /**
     * Creates base grid configuration.
     *
     * @return Base grid configuration.
     * @throws Exception In case of any error.
     */
    protected GridConfiguration gridConfiguration() throws Exception {
        GridConfiguration cfg = getConfiguration(getTestGridName());

        GridTcpDiscoverySpi discoSpi = new GridTcpDiscoverySpi();
        discoSpi.setIpFinder(IP_FINDER);

        cfg.setDiscoverySpi(discoSpi);

        GridCacheConfiguration cc = defaultCacheConfiguration();

        cc.setName("partitioned");
        cc.setCacheMode(GridCacheMode.PARTITIONED);
        cc.setAffinityMapper(new GridGgfsGroupDataBlocksKeyMapper(128));
        cc.setBackups(0);
        cc.setAtomicityMode(TRANSACTIONAL);
        cc.setQueryIndexEnabled(false);

        GridCacheConfiguration metaCfg = defaultCacheConfiguration();

        metaCfg.setName("replicated");
        metaCfg.setCacheMode(GridCacheMode.REPLICATED);
        metaCfg.setAtomicityMode(TRANSACTIONAL);
        metaCfg.setQueryIndexEnabled(false);

        cfg.setCacheConfiguration(metaCfg, cc);

        return cfg;
    }

    /**
     * Creates test-purposed GridGgfsConfiguration.
     *
     * @param endpointCfg Optional REST endpoint configuration.
     * @return test-purposed GridGgfsConfiguration.
     */
    protected GridGgfsConfiguration gridGgfsConfiguration(@Nullable String endpointCfg) {
        GridGgfsConfiguration ggfsConfiguration = new GridGgfsConfiguration();

        ggfsConfiguration.setDataCacheName("partitioned");
        ggfsConfiguration.setMetaCacheName("replicated");
        ggfsConfiguration.setName("ggfs" + UUID.randomUUID());
        ggfsConfiguration.setManagementPort(mgmtPort.getAndIncrement());

        if (endpointCfg != null)
            ggfsConfiguration.setIpcEndpointConfiguration(endpointCfg);

        return ggfsConfiguration;
    }
}