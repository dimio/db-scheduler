/**
 * Copyright (C) Gustav Karlsson
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.kagkarlsson.scheduler;

import com.github.kagkarlsson.scheduler.logging.ConfigurableLogger;
import com.github.kagkarlsson.scheduler.stats.StatsRegistry;
import com.github.kagkarlsson.scheduler.task.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@SuppressWarnings("rawtypes")
class AsyncExecutePicked implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(AsyncExecutePicked.class);
    private final Executor executor;
    private final TaskRepository taskRepository;
    private SchedulerClientEventListener earlyExecutionListener;
    private final SchedulerClient schedulerClient;
    private final StatsRegistry statsRegistry;
    private final TaskResolver taskResolver;
    private final SchedulerState schedulerState;
    private final ConfigurableLogger failureLogger;
    private final Clock clock;
    private final Execution pickedExecution;

    public AsyncExecutePicked(Executor executor, TaskRepository taskRepository, SchedulerClientEventListener earlyExecutionListener, SchedulerClient schedulerClient, StatsRegistry statsRegistry,
                              TaskResolver taskResolver, SchedulerState schedulerState, ConfigurableLogger failureLogger,
                              Clock clock, Execution pickedExecution) {
        this.executor = executor;
        this.taskRepository = taskRepository;
        this.earlyExecutionListener = earlyExecutionListener;
        this.schedulerClient = schedulerClient;
        this.statsRegistry = statsRegistry;
        this.taskResolver = taskResolver;
        this.schedulerState = schedulerState;
        this.failureLogger = failureLogger;
        this.clock = clock;
        this.pickedExecution = pickedExecution;
    }

    @Override
    public void run() {
        // FIXLATER: need to cleanup all the references back to scheduler fields
        final UUID executionId = executor.addCurrentlyProcessing(new CurrentlyExecuting(pickedExecution, clock));
        statsRegistry.register(StatsRegistry.CandidateStatsEvent.EXECUTED);
        executePickedExecution(pickedExecution).whenComplete((c, ex) -> executor.removeCurrentlyProcessing(executionId));
    }

    private CompletableFuture<CompletionHandler> executePickedExecution(Execution execution) {
        final Optional<Task> task = taskResolver.resolve(execution.taskInstance.getTaskName());
        if (!task.isPresent()) {
            LOG.error("Failed to find implementation for task with name '{}'. Should have been excluded in JdbcRepository.", execution.taskInstance.getTaskName());
            statsRegistry.register(StatsRegistry.SchedulerStatsEvent.UNEXPECTED_ERROR);
            return new CompletableFuture<>();
        }
        if (!(task.get() instanceof AsyncExecutionHandler)) {
            throw new IllegalStateException("Should only ever try to execute async when task has an AsyncExecutionHandler");
        }

        AsyncExecutionHandler asyncHandler = (AsyncExecutionHandler) task.get();
        Instant executionStarted = clock.now();
        LOG.debug("Executing " + execution);
        CompletableFuture<CompletionHandler> completableFuture = asyncHandler.executeAsync(execution.taskInstance, new AsyncExecutionContext(schedulerState, execution, schedulerClient, executor.getExecutorService()));

        return completableFuture.whenCompleteAsync((completion, ex) -> {
            if (ex != null) {
                if (ex instanceof RuntimeException) {
                    failure(task.get(), execution, ex, executionStarted, "Unhandled exception");
                    statsRegistry.register(StatsRegistry.ExecutionStatsEvent.FAILED);
                } else {
                    failure(task.get(), execution, ex, executionStarted, "Error");
                    statsRegistry.register(StatsRegistry.ExecutionStatsEvent.FAILED);
                }
                return;
            }
            LOG.debug("Execution done");
            complete(completion, execution, executionStarted);
            statsRegistry.register(StatsRegistry.ExecutionStatsEvent.COMPLETED);

        }, executor.getExecutorService());
    }

    private void complete(CompletionHandler completion, Execution execution, Instant executionStarted) {
        ExecutionComplete completeEvent = ExecutionComplete.success(execution, executionStarted, clock.now());
        try {
            completion.complete(completeEvent, new ExecutionOperations(taskRepository, earlyExecutionListener, execution));
            statsRegistry.registerSingleCompletedExecution(completeEvent);
        } catch (Throwable e) {
            statsRegistry.register(StatsRegistry.SchedulerStatsEvent.COMPLETIONHANDLER_ERROR);
            statsRegistry.register(StatsRegistry.SchedulerStatsEvent.UNEXPECTED_ERROR);
            LOG.error("Failed while completing execution {}. Execution will likely remain scheduled and locked/picked. " +
                "The execution should be detected as dead after a while, and handled according to the tasks DeadExecutionHandler.", execution, e);
        }
    }

    private void failure(Task task, Execution execution, Throwable cause, Instant executionStarted, String errorMessagePrefix) {
        String logMessage = errorMessagePrefix + " during execution of task with name '{}'. Treating as failure.";
        failureLogger.log(logMessage, cause, task.getName());

        ExecutionComplete completeEvent = ExecutionComplete.failure(execution, executionStarted, clock.now(), cause);
        try {
            task.getFailureHandler().onFailure(completeEvent, new ExecutionOperations(taskRepository, earlyExecutionListener, execution));
            statsRegistry.registerSingleCompletedExecution(completeEvent);
        } catch (Throwable e) {
            statsRegistry.register(StatsRegistry.SchedulerStatsEvent.FAILUREHANDLER_ERROR);
            statsRegistry.register(StatsRegistry.SchedulerStatsEvent.UNEXPECTED_ERROR);
            LOG.error("Failed while completing execution {}. Execution will likely remain scheduled and locked/picked. " +
                "The execution should be detected as dead after a while, and handled according to the tasks DeadExecutionHandler.", execution, e);
        }
    }
}
