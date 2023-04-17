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
import com.github.kagkarlsson.scheduler.task.AsyncExecutionHandler;
import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

public class FetchCandidates implements PollStrategy {
    private static final Logger LOG = LoggerFactory.getLogger(FetchCandidates.class);
    private final Executor executor;
    private final TaskRepository taskRepository;
    private final SchedulerClient schedulerClient;
    private SchedulerClientEventListener earlyExecutionListener;
    private final StatsRegistry statsRegistry;
    private final SchedulerState schedulerState;
    private final ConfigurableLogger failureLogger;
    private final TaskResolver taskResolver;
    private final Clock clock;
    private final PollingStrategyConfig pollingStrategyConfig;
    private final Runnable triggerCheckForNewExecutions;
    AtomicInteger currentGenerationNumber = new AtomicInteger(0);
    private final int lowerLimit;
    private final int upperLimit;

    public FetchCandidates(Executor executor, TaskRepository taskRepository, SchedulerClient schedulerClient,
                           SchedulerClientEventListener earlyExecutionListener, int threadpoolSize, StatsRegistry statsRegistry, SchedulerState schedulerState,
                           ConfigurableLogger failureLogger, TaskResolver taskResolver, Clock clock,
                           PollingStrategyConfig pollingStrategyConfig, Runnable triggerCheckForNewExecutions) {
        this.executor = executor;
        this.taskRepository = taskRepository;
        this.schedulerClient = schedulerClient;
        this.earlyExecutionListener = earlyExecutionListener;
        this.statsRegistry = statsRegistry;
        this.schedulerState = schedulerState;
        this.failureLogger = failureLogger;
        this.taskResolver = taskResolver;
        this.clock = clock;
        this.pollingStrategyConfig = pollingStrategyConfig;
        this.triggerCheckForNewExecutions = triggerCheckForNewExecutions;
        lowerLimit = pollingStrategyConfig.getLowerLimit(threadpoolSize);
        //FIXLATER: this is not "upper limit", but rather nr of executions to get. those already in queue will become stale
        upperLimit = pollingStrategyConfig.getUpperLimit(threadpoolSize);
    }

    @SuppressWarnings("rawtypes")
    @Override
    public void run() {
        Instant now = clock.now();

        // Fetch new candidates for execution. Old ones still in ExecutorService will become stale and be discarded
        final int executionsToFetch = upperLimit;
        List<Execution> fetchedDueExecutions = taskRepository.getDue(now, executionsToFetch);
        LOG.trace("Fetched {} task instances due for execution at {}", fetchedDueExecutions.size(), now);

        currentGenerationNumber.incrementAndGet();
        DueExecutionsBatch newDueBatch = new DueExecutionsBatch(
            currentGenerationNumber.get(),
            fetchedDueExecutions.size(),
            executionsToFetch == fetchedDueExecutions.size(),
            (Integer leftInBatch) -> leftInBatch <= lowerLimit);

        for (Execution e : fetchedDueExecutions) {

            CompletableFuture<Void> future = CompletableFuture
                .runAsync(executor::incrementInQueue, executor.getExecutorService())
                .thenComposeAsync((result) -> CompletableFuture.supplyAsync(() -> {
                    Optional<Execution> candidate = new PickDue(e, newDueBatch).call();
                    return candidate.orElse(null); // TODO: remove optional before merge
                }, executor.getExecutorService()))
                .thenComposeAsync(picked -> {
                    if (picked == null) {
                        // Skip this step if we were not able to pick the execution (someone else got the lock)
                        return CompletableFuture.completedFuture(null);
                    }
                    // Experimental support for async execution. Peek at Task to see if support async
                    // Unresolved tasks will be handled further in
                    final Optional<Task> task = taskResolver.resolve(picked.taskInstance.getTaskName());
                    if (task.isPresent() && task.get() instanceof AsyncExecutionHandler) {

                        return new AsyncExecutePicked(executor, taskRepository, earlyExecutionListener,
                            schedulerClient, statsRegistry, taskResolver, schedulerState, failureLogger,
                            clock, picked).toCompletableFuture();
                    } else {

                        return CompletableFuture.runAsync(new ExecutePicked(executor, taskRepository, earlyExecutionListener,
                            schedulerClient, statsRegistry, taskResolver, schedulerState, failureLogger,
                            clock, picked), executor.getExecutorService());
                    }

                }, executor.getExecutorService())
                .thenAccept(x -> {
                    executor.decrementInQueue();
                    newDueBatch.oneExecutionDone(triggerCheckForNewExecutions::run);
                });
            executor.addOngoingWork(future);

        }
        statsRegistry.register(StatsRegistry.SchedulerStatsEvent.RAN_EXECUTE_DUE);
    }

    private class PickDue implements Callable<Optional<Execution>> {
        private final Execution candidate;
        private final DueExecutionsBatch addedDueExecutionsBatch;

        public PickDue(Execution candidate, DueExecutionsBatch dueExecutionsBatch) {
            this.candidate = candidate;
            this.addedDueExecutionsBatch = dueExecutionsBatch;
        }

        @Override
        public Optional<Execution> call() {
            if (schedulerState.isShuttingDown()) {
                LOG.info("Scheduler has been shutdown. Skipping fetched due execution: " + candidate.taskInstance.getTaskAndInstance());
                return Optional.empty();
            }

            if (addedDueExecutionsBatch.isOlderGenerationThan(currentGenerationNumber.get())) {
                // skipping execution due to it being stale
                addedDueExecutionsBatch.markBatchAsStale();
                statsRegistry.register(StatsRegistry.CandidateStatsEvent.STALE);
                LOG.trace("Skipping queued execution (current generationNumber: {}, execution generationNumber: {})", currentGenerationNumber, addedDueExecutionsBatch.getGenerationNumber());
                return Optional.empty();
            }

            final Optional<Execution> pickedExecution = taskRepository.pick(candidate, clock.now());

            if (!pickedExecution.isPresent()) {
                // someone else picked id
                LOG.debug("Execution picked by another scheduler. Continuing to next due execution.");
                statsRegistry.register(StatsRegistry.CandidateStatsEvent.ALREADY_PICKED);
                return Optional.empty();
            }

            return pickedExecution;
        }

    }

}
