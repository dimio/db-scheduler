/*
 * Copyright (C) Gustav Karlsson
 *
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.kagkarlsson.scheduler;

import com.github.kagkarlsson.scheduler.event.AbstractSchedulerListener;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ImmediateCheckForDueExecutions extends AbstractSchedulerListener {
  private static final Logger LOG = LoggerFactory.getLogger(ImmediateCheckForDueExecutions.class);
  private final Scheduler scheduler;
  private Clock clock;

  public ImmediateCheckForDueExecutions(Scheduler scheduler, Clock clock) {
    this.scheduler = scheduler;
    this.clock = clock;
  }

  @Override
  public void onExecutionScheduled(
      TaskInstanceId taskInstanceId, Instant scheduledToExecutionTime) {
    SchedulerState schedulerState = scheduler.getSchedulerState();
    if (!schedulerState.isStarted() || schedulerState.isShuttingDown()) {
      LOG.debug(
          "Will not act on scheduling event for execution (task: '{}', id: '{}') as scheduler is starting or shutting down.",
          taskInstanceId.getTaskName(),
          taskInstanceId.getId());
      return;
    }

    if (scheduledToExecutionTime.toEpochMilli() <= clock.now().toEpochMilli()) {
      LOG.debug(
          "Task-instance scheduled to run directly, triggering check for due executions (unless it is already running). Task: {}, instance: {}",
          taskInstanceId.getTaskName(),
          taskInstanceId.getId());
      scheduler.triggerCheckForDueExecutions();
    }
  }
}
