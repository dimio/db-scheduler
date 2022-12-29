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
package com.github.kagkarlsson.scheduler.task.helper;

import com.github.kagkarlsson.scheduler.task.*;
import com.github.kagkarlsson.scheduler.task.schedule.Schedule;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;

public class Tasks {
    public static final Duration DEFAULT_RETRY_INTERVAL = Duration.ofMinutes(5);

    public static RecurringTaskBuilder<Void> recurring(String name, Schedule schedule) {
        return recurring(HasTaskName.of(name), schedule);
    }

    public static RecurringTaskBuilder<Void> recurring(HasTaskName name, Schedule schedule) {
        return new RecurringTaskBuilder<>(name.getTaskName(), schedule, Void.class);
    }

    // TODO: fix the rest

    public static <T> RecurringTaskBuilder<T> recurring(String name, Schedule schedule, Class<T> dataClass) {
        return new RecurringTaskBuilder<>(name, schedule, dataClass);
    }

    public static <T extends ScheduleAndData> RecurringTaskWithPersistentScheduleBuilder<T> recurringWithPersistentSchedule(String name, Class<T> dataClass) {
        return new RecurringTaskWithPersistentScheduleBuilder<T>(name, dataClass);
    }

    public static OneTimeTaskBuilder<Void> oneTime(String name) {
        return new OneTimeTaskBuilder<>(name, Void.class);
    }

    public static <T> OneTimeTaskBuilder<T> oneTime(String name, Class<T> dataClass) {
        return oneTime(HasTaskName.of(name), dataClass);
        // TODO: add signature for TaskDescriptor, where data-class is fetched from descriptor. need to make two implementations of descriptors, with/without data and correct instance() methods for them
    }

    public static <T> OneTimeTaskBuilder<T> oneTime(HasTaskName name, Class<T> dataClass) {
        return new OneTimeTaskBuilder<>(name.getTaskName(), dataClass);
    }

    public static <T> TaskBuilder<T> custom(String name, Class<T> dataClass) {
        return new TaskBuilder<>(name, dataClass);
    }


    public static class RecurringTaskBuilder<T> {
        private final String name;
        private final Schedule schedule;
        private Class<T> dataClass;
        private FailureHandler<T> onFailure;
        private DeadExecutionHandler<T> onDeadExecution;
        private ScheduleRecurringOnStartup<T> scheduleOnStartup;

        public RecurringTaskBuilder(String name, Schedule schedule, Class<T> dataClass) {
            this.name = name;
            this.schedule = schedule;
            this.dataClass = dataClass;
            this.onFailure = new FailureHandler.OnFailureReschedule<>(schedule);
            this.onDeadExecution = new DeadExecutionHandler.ReviveDeadExecution<>();
            this.scheduleOnStartup = new ScheduleRecurringOnStartup<>(RecurringTask.INSTANCE, null, schedule);
        }

        public RecurringTaskBuilder<T> onFailureReschedule() {
            this.onFailure = new FailureHandler.OnFailureReschedule<>(schedule);
            return this;
        }

        public RecurringTaskBuilder<T> onDeadExecutionRevive() {
            this.onDeadExecution = new DeadExecutionHandler.ReviveDeadExecution<>();
            return this;
        }

        public RecurringTaskBuilder<T> onFailure(FailureHandler<T> failureHandler) {
            this.onFailure = failureHandler;
            return this;
        }

        public RecurringTaskBuilder<T> onDeadExecution(DeadExecutionHandler<T> deadExecutionHandler) {
            this.onDeadExecution = deadExecutionHandler;
            return this;
        }

        public RecurringTaskBuilder<T> initialData(T initialData) {
            this.scheduleOnStartup = new ScheduleRecurringOnStartup<>(RecurringTask.INSTANCE, initialData, schedule);
            return this;
        }

        public RecurringTask<T> execute(VoidExecutionHandler<T> executionHandler) {
            return new RecurringTask<T>(name, schedule, dataClass, scheduleOnStartup, onFailure, onDeadExecution) {

                @Override
                public void executeRecurringly(TaskInstance<T> taskInstance, ExecutionContext executionContext) {
                    executionHandler.execute(taskInstance, executionContext);
                }
            };
        }

        public RecurringTask<T> executeStateful(StateReturningExecutionHandler<T> executionHandler) {
            return new RecurringTask<T>(name, schedule, dataClass, scheduleOnStartup, onFailure, onDeadExecution) {

                @Override
                public CompletionHandler<T> execute(TaskInstance<T> taskInstance, ExecutionContext executionContext) {
                    final T nextData = executionHandler.execute(taskInstance, executionContext);
                    return new CompletionHandler.OnCompleteReschedule<>(schedule, nextData);
                }

                @Override
                public void executeRecurringly(TaskInstance<T> taskInstance, ExecutionContext executionContext) {
                    // never called
                }
            };
        }
    }

    public static class RecurringTaskWithPersistentScheduleBuilder<T extends ScheduleAndData> {
        private final String name;
        private final Class<T> dataClass;
        private FailureHandler<T> onFailure = new FailureHandler.OnFailureRescheduleUsingTaskDataSchedule<>();

        public RecurringTaskWithPersistentScheduleBuilder(String name, Class<T> dataClass) {
            this.name = name;
            this.dataClass = dataClass;
        }

        public RecurringTaskWithPersistentScheduleBuilder<T> onFailure(FailureHandler<T> failureHandler) {
            this.onFailure = failureHandler;
            return this;
        }

        public RecurringTaskWithPersistentSchedule<T> execute(VoidExecutionHandler<T> executionHandler) {
            return new RecurringTaskWithPersistentSchedule<T>(name, dataClass, onFailure) {
                @Override
                public CompletionHandler<T> execute(TaskInstance<T> taskInstance, ExecutionContext executionContext) {
                    executionHandler.execute(taskInstance, executionContext);

                    return (executionComplete, executionOperations) -> {
                        executionOperations.reschedule(
                            executionComplete,
                            taskInstance.getData().getSchedule().getNextExecutionTime(executionComplete)
                        );
                    };

                }
            };
        }

        public RecurringTaskWithPersistentSchedule<T> executeStateful(StateReturningExecutionHandler<T> executionHandler) {
            return new RecurringTaskWithPersistentSchedule<T>(name, dataClass, onFailure) {

                @Override
                public CompletionHandler<T> execute(TaskInstance<T> taskInstance, ExecutionContext executionContext) {
                    final T nextData = executionHandler.execute(taskInstance, executionContext);

                    return (executionComplete, executionOperations) -> {
                        executionOperations.reschedule(
                            executionComplete,
                            nextData.getSchedule().getNextExecutionTime(executionComplete),
                            nextData
                        );
                    };
                }
            };
        }
    }


    public static class OneTimeTaskBuilder<T> {
        private final String name;
        private Class<T> dataClass;
        private FailureHandler<T> onFailure;
        private DeadExecutionHandler<T> onDeadExecution;

        public OneTimeTaskBuilder(String name, Class<T> dataClass) {
            this.name = name;
            this.dataClass = dataClass;
            this.onDeadExecution = new DeadExecutionHandler.ReviveDeadExecution<>();
            this.onFailure = new FailureHandler.OnFailureRetryLater<>(DEFAULT_RETRY_INTERVAL);
        }

        public OneTimeTaskBuilder<T> onFailureRetryLater() {
            this.onFailure = new FailureHandler.OnFailureRetryLater<>(DEFAULT_RETRY_INTERVAL);
            return this;
        }

        public OneTimeTaskBuilder<T> onDeadExecutionRevive() {
            this.onDeadExecution = new DeadExecutionHandler.ReviveDeadExecution<>();
            return this;
        }

        public OneTimeTaskBuilder<T> onFailure(FailureHandler<T> failureHandler) {
            this.onFailure = failureHandler;
            return this;
        }

        public OneTimeTaskBuilder<T> onDeadExecution(DeadExecutionHandler<T> deadExecutionHandler) {
            this.onDeadExecution = deadExecutionHandler;
            return this;
        }

        public OneTimeTask<T> execute(VoidExecutionHandler<T> executionHandler) {
            return new OneTimeTask<T>(name, dataClass, onFailure, onDeadExecution) {
                @Override
                public void executeOnce(TaskInstance<T> taskInstance, ExecutionContext executionContext) {
                    executionHandler.execute(taskInstance, executionContext);
                }
            };
        }
    }

    public static class TaskBuilder<T> {
        private final String name;
        private Class<T> dataClass;
        private FailureHandler<T> onFailure;
        private DeadExecutionHandler<T> onDeadExecution;
        private ScheduleOnStartup<T> onStartup;
        private Function<Instant, Instant> defaultExecutionTime = Function.identity();

        public TaskBuilder(String name, Class<T> dataClass) {
            this.name = name;
            this.dataClass = dataClass;
            this.onDeadExecution = new DeadExecutionHandler.ReviveDeadExecution<>();
            this.onFailure = new FailureHandler.OnFailureRetryLater<T>(DEFAULT_RETRY_INTERVAL);
        }

        public TaskBuilder<T> onFailureReschedule(Schedule schedule) {
            this.onFailure = new FailureHandler.OnFailureReschedule<T>(schedule);
            return this;
        }

        public TaskBuilder<T> onDeadExecutionRevive() {
            this.onDeadExecution = new DeadExecutionHandler.ReviveDeadExecution<>();
            return this;
        }

        public TaskBuilder<T> onFailure(FailureHandler<T> failureHandler) {
            this.onFailure = failureHandler;
            return this;
        }

        public TaskBuilder<T> onDeadExecution(DeadExecutionHandler<T> deadExecutionHandler) {
            this.onDeadExecution = deadExecutionHandler;
            return this;
        }

        public TaskBuilder<T> scheduleOnStartup(String instance, T initialData, Function<Instant,Instant> firstExecutionTime) {
            this.onStartup = new ScheduleOnceOnStartup<T>(instance, initialData, firstExecutionTime);
            return this;
        }

        public TaskBuilder<T> scheduleOnStartup(String instance, T initialData, Schedule schedule) {
            this.onStartup = new ScheduleOnceOnStartup<T>(
                instance,
                initialData,
                now -> schedule.getNextExecutionTime(ExecutionComplete.simulatedSuccess(now)));
            return this;
        }

        public TaskBuilder<T> defaultExecutionTime(Function<Instant,Instant> defaultExecutionTime) {
            this.defaultExecutionTime = defaultExecutionTime;
            return this;
        }

        public CustomTask<T> execute(ExecutionHandler<T> executionHandler) {
            return new CustomTask<T>(name, dataClass, onStartup, defaultExecutionTime, onFailure, onDeadExecution) {
                @Override
                public CompletionHandler<T> execute(TaskInstance<T> taskInstance, ExecutionContext executionContext) {
                    return executionHandler.execute(taskInstance, executionContext);
                }
            };
        }

    }

}
