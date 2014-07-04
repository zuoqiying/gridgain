/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.hadoop.v2;

import org.apache.hadoop.fs.*;
import org.apache.hadoop.mapred.*;
import org.apache.hadoop.util.*;
import org.gridgain.grid.*;
import org.gridgain.grid.hadoop.*;
import org.gridgain.grid.kernal.processors.hadoop.fs.*;
import org.gridgain.grid.logger.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.jetbrains.annotations.*;

import java.io.*;
import java.net.*;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.util.*;

/**
 * Provides all resources are needed to the job execution. Downloads the main jar, the configuration and additional
 * files are needed to be placed on local files system.
 */
public class GridHadoopV2JobResourceManager {
    /** Hadoop job context. */
    private JobContextImpl ctx;

    /** Logger. */
    private GridLogger log;

    /** Job ID. */
    private GridHadoopJobId jobId;

    /** Directory to place localized resources. */
    private File jobLocDir;

    /** Class path list. */
    private List<URL> clsPath = new ArrayList<>();

    /** List of local resources. */
    private Collection<File> rsrcList = new ArrayList<>();

    /** Staging directory to delivery job jar and config to the work nodes. */
    private Path stagingDir;

    /**
     * Creates new instance.
     * @param jobId Job ID.
     * @param ctx Hadoop job context.
     * @param locNodeId Local node ID.
     * @param log Logger.
     */
    public GridHadoopV2JobResourceManager(GridHadoopJobId jobId, JobContextImpl ctx, UUID locNodeId, GridLogger log)
        throws GridException {
        this.jobId = jobId;
        this.ctx = ctx;
        this.log = log.getLogger(GridHadoopV2JobResourceManager.class);

        jobLocDir = new File(new File(U.resolveWorkDirectory("hadoop", false), "node-" + locNodeId), "job_" + jobId);
    }

    /**
     * Set {@link #jobLocDir} as working directory in local file system.
     *
     * @throws IOException If fails.
     */
    private void setJobWorkingDirectory() throws IOException {
        FileSystem.getLocal(ctx.getJobConf()).setWorkingDirectory(new Path(jobLocDir.getAbsolutePath()));
    }

    /**
     * Prepare job resources. Resolve the classpath list and download it if needed.
     *
     * @param download {@code true} If need to download resources.
     * @throws GridException If failed.
     */
    public void prepareJobEnvironment(boolean download) throws GridException {
        try {
            if (jobLocDir.exists())
                throw new GridException("Local job directory already exists: " + jobLocDir.getAbsolutePath());

            JobConf cfg = ctx.getJobConf();

            String mrDir = ctx.getJobConf().get("mapreduce.job.dir");

            if (mrDir != null) {
                stagingDir = new Path(new URI(mrDir));

                if (download) {
                    FileSystem fs = FileSystem.get(stagingDir.toUri(), cfg);

                    if (!fs.exists(stagingDir))
                        throw new GridException("Failed to find map-reduce submission directory (does not exist): " +
                                stagingDir);

                    if (!FileUtil.copy(fs, stagingDir, jobLocDir, false, cfg))
                        throw new GridException("Failed to copy job submission directory contents to local file system " +
                             "[path=" + stagingDir + ", locDir=" + jobLocDir.getAbsolutePath() + ", jobId=" + jobId + ']');
                }

                File jarJobFile = new File(jobLocDir, "job.jar");

                clsPath.add(jarJobFile.toURI().toURL());

                rsrcList.add(jarJobFile);
                rsrcList.add(new File(jobLocDir, "job.xml"));

                processFiles(ctx.getCacheFiles(), download, false, false);
                processFiles(ctx.getCacheArchives(), download, true, false);
                processFiles(ctx.getFileClassPaths(), download, false, true);
                processFiles(ctx.getArchiveClassPaths(), download, true, true);
            }
            else if (!jobLocDir.mkdirs())
                throw new GridException("Failed to create local job directory: " + jobLocDir.getAbsolutePath());

            setJobWorkingDirectory();
        }
        catch (URISyntaxException | IOException e) {
            throw new GridException(e);
        }
    }

    /**
     * Process list of resources.
     *
     * @param files Array of {@link URI} or {@link Path} to process resources.
     * @param download {@code true}, if need to download. Process class path only else.
     * @param extract {@code true}, if need to extract archive.
     * @param addToClsPath {@code true}, if need to add the resource to class path.
     * @throws IOException If errors.
     */
    private void processFiles(@Nullable Object[] files, boolean download, boolean extract, boolean addToClsPath)
        throws IOException {
        if (F.isEmptyOrNulls(files))
            return;

        for (Object pathObj : files) {
            String locName = null;
            Path srcPath;

            if (pathObj instanceof URI) {
                URI uri = (URI)pathObj;

                locName = uri.getFragment();

                srcPath = new Path(uri);
            }
            else
                srcPath = (Path)pathObj;

            if (locName == null)
                locName = srcPath.getName();

            File dstPath = new File(jobLocDir.getAbsolutePath(), locName);

            rsrcList.add(dstPath);

            if (addToClsPath)
                clsPath.add(dstPath.toURI().toURL());

            if (!download)
                return;

            JobConf cfg = ctx.getJobConf();

            FileSystem dstFs = FileSystem.getLocal(cfg);

            FileSystem srcFs = srcPath.getFileSystem(cfg);

            if (extract) {
                File archivesPath = new File(jobLocDir.getAbsolutePath(), ".cached-archives");

                if (!archivesPath.exists() && !archivesPath.mkdir())
                    throw new IOException("Failed to create directory " +
                         "[path=" + archivesPath + ", jobId=" + jobId + ']');

                File archiveFile = new File(archivesPath, locName);

                FileUtil.copy(srcFs, srcPath, dstFs, new Path(archiveFile.toString()), false, cfg);

                String archiveNameLC = archiveFile.getName().toLowerCase();

                if (archiveNameLC.endsWith(".jar")) {
                    RunJar.unJar(archiveFile, dstPath);
                }
                else if (archiveNameLC.endsWith(".zip")) {
                    FileUtil.unZip(archiveFile, dstPath);
                }
                else if (archiveNameLC.endsWith(".tar.gz") ||
                    archiveNameLC.endsWith(".tgz") ||
                    archiveNameLC.endsWith(".tar")) {
                    FileUtil.unTar(archiveFile, dstPath);
                }
                else {
                    throw new IOException("Cannot unpack archive [path=" + srcPath + ", jobId=" + jobId + ']');
                }
            }
            else
                FileUtil.copy(srcFs, srcPath, dstFs, new Path(dstPath.toString()), false, cfg);
        }
    }

    /**
     * Class path list.
     */
    public List<URL> getClassPath() {
        return clsPath;
    }

    /**
     * Release resources allocated fot job execution.
     */
    public void releaseJobEnvironment() {
        if (jobLocDir.exists())
            U.delete(jobLocDir);
    }

    /**
     * Returns subdirectory of {@code jobLocDir} for task execution.
     *
     * @param info Task info.
     * @return Working directory for task.
     */
    private File taskLocalDir(GridHadoopTaskInfo info) {
        return new File(jobLocDir, info.type() + "_" + info.taskNumber() + "_" + info.attempt());
    }

    /**
     * Prepares the environment for task execution.
     *
     * <ul>
     *     <li>Creates working directory.</li>
     *     <li>Creates symbolic links to all job resources in working directory.</li>
     *     <li>Sets working directory for the local file system in current thread.</li>
     * </ul>
     * @param info Task info.
     * @throws GridException If fails.
     */
    public void prepareTaskEnvironment(GridHadoopTaskInfo info) throws GridException {
        try {
            switch(info.type()) {
                case MAP:
                case REDUCE:
                    File locDir = taskLocalDir(info);

                    if (locDir.exists())
                        throw new IOException("Task local directory already exists: " + locDir);

                    if (!locDir.mkdir())
                        throw new IOException("Failed to create directory: " + locDir);

                    for (File resource : rsrcList) {
                        File symLink = new File(locDir, resource.getName());

                        try {
                            Files.createSymbolicLink(symLink.toPath(), resource.toPath());
                        }
                        catch (IOException e) {
                            String msg = "Unable to create symlink \"" + symLink + "\" to \"" + resource + "\".";

                            if (U.isWindows() && e instanceof FileSystemException)
                                msg += "\n\nAbility to create symbolic links is required!\n" +
                                        "On Windows platform you have to grant permission 'Create symbolic links'\n" +
                                        "to your user or run the Accelerator as Administrator.\n";

                            throw new IOException(msg, e);
                        }
                    }

                    FileSystem.getLocal(ctx.getJobConf()).setWorkingDirectory(new Path(locDir.getAbsolutePath()));

                    break;

                default:
                    setJobWorkingDirectory();
            }
        }
        catch (IOException e) {
            throw new GridException("Unable to prepare local working directory for the task " +
                 "[jobId=" + jobId + ", task=" + info + ']', e);
        }
    }

    /**
     * Releases resources allocated by {@link #prepareTaskEnvironment} and restores working directory to initial state.
     *
     * @param info Task info.
     * @throws GridException If fails.
     */
    public void releaseTaskEnvironment(GridHadoopTaskInfo info) throws GridException {
        GridHadoopRawLocalFileSystem fs;

        File locDir = taskLocalDir(info);

        try {
            if (locDir.exists())
                U.delete(locDir);

            fs = (GridHadoopRawLocalFileSystem)FileSystem.getLocal(ctx.getJobConf()).getRaw();
        }
        catch (IOException e) {
            throw new GridException("Unable to release local working directory of the task " +
                 "[path=" + locDir + ", jobId=" + jobId + ", task=" + info + ']', e);
        }

        fs.setWorkingDirectory(fs.getInitialWorkingDirectory());
    }

    /**
     * Cleans up job staging directory.
     */
    public void cleanupStagingDirectory() {
        try {
            if (stagingDir != null)
                stagingDir.getFileSystem(ctx.getJobConf()).delete(stagingDir, true);
        }
        catch (Exception e) {
            log.error("Failed to remove job staging directory [path=" + stagingDir + ", jobId=" + jobId + ']' , e);
        }
    }
}