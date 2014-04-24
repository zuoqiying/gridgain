/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.hadoop.taskexecutor;

import org.gridgain.grid.*;
import org.gridgain.grid.hadoop.*;
import org.gridgain.grid.kernal.processors.hadoop.*;
import org.gridgain.grid.kernal.processors.hadoop.jobtracker.*;
import org.gridgain.grid.util.future.*;
import org.gridgain.grid.util.lang.*;
import org.gridgain.grid.util.typedef.*;

import java.util.*;

import static org.gridgain.grid.hadoop.GridHadoopTaskType.*;
import static org.gridgain.grid.kernal.processors.hadoop.taskexecutor.GridHadoopTaskState.*;

/**
 * TODO write doc
 */
public class GridHadoopTaskExecutor extends GridHadoopComponent {
    /** Job tracker. */
    private GridHadoopJobTracker jobTracker;

    @Override public void onKernalStart() throws GridException {
        super.onKernalStart();

        jobTracker = ctx.jobTracker();
    }

    /**
     * Runs tasks.
     *
     * @param tasks Tasks.
     */
    public void run(Collection<GridHadoopTask> tasks) {
        if (log.isDebugEnabled())
            log.debug("Submitting tasks for local execution [locNodeId=" + ctx.localNodeId() +
                ", tasksCnt=" + tasks.size() + ']');

        for (final GridHadoopTask task : tasks) {
            assert task != null;

            GridChainedFuture<?> fut = new GridChainedFuture<>(ctx.kernalContext());

            fut.listenAsync(new CIX1<GridFuture<?>>() {
                @Override public void applyx(GridFuture<?> f) throws GridException {
                    GridHadoopTaskState state = COMPLETED;
                    Throwable err = null;

                    try {
                        f.get();
                    }
                    catch (Exception e) {
                        state = FAILED;
                        err = e;
                    }

                    jobTracker.onTaskFinished(task.info(), new GridHadoopTaskStatus(state, err));
                }
            });

            ctx.kernalContext().closure().callLocalSafe(new GridPlainCallable<GridFuture<?>>() {
                @Override public GridFuture<?> call() throws Exception {
                    GridHadoopTaskInfo info = task.info();

                    try (GridHadoopTaskOutput out = createOutput(info);
                         GridHadoopTaskInput in = createInput(info)) {
                        GridHadoopTaskContext taskCtx = new GridHadoopTaskContext(ctx.kernalContext());

                        if (log.isDebugEnabled())
                            log.debug("Running task: " + task);

                        task.run(taskCtx);

                        return out.finish();
                    }
                }
            }, false).listenAsync(fut);
        }
    }

    /**
     * Cancels all currently running tasks for given job ID and cancels scheduled execution of tasks
     * for this job ID.
     * <p>
     * It is guaranteed that this method will not be called concurrently with
     * {@link #run(Collection)} method. No more job submissions will be performed via
     * {@link #run(Collection)} method for given job ID after this method is called.
     *
     * @param jobId Job ID to cancel.
     */
    public void cancelTasks(GridHadoopJobId jobId) {
        // TODO.
    }

    /**
     * Creates task output.
     *
     * @param taskInfo Task info.
     * @return Task output.
     */
    private GridHadoopTaskOutput createOutput(GridHadoopTaskInfo taskInfo) {
        if (taskInfo.type() == REDUCE)
            return null;

        return ctx.shuffle().getMapperOutput(taskInfo);
    }

    /**
     * Creates task input.
     *
     * @param taskInfo Task info.
     * @return Task input.
     */
    private GridHadoopTaskInput createInput(GridHadoopTaskInfo taskInfo) {
        if (taskInfo.type() == MAP)
            return null;

        return ctx.shuffle().getReducerInput(taskInfo);
    }
}
