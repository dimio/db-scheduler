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
package com.github.kagkarlsson.scheduler.event;

import com.github.kagkarlsson.scheduler.CurrentlyExecuting;
import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionComplete;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import java.time.Instant;

public abstract class AbstractSchedulerListener implements SchedulerListener {

  @Override
  public void onExecutionScheduled(TaskInstanceId taskInstanceId, Instant executionTime) {}

  @Override
  public void onExecutionStart(CurrentlyExecuting currentlyExecuting) {}

  @Override
  public void onExecutionComplete(ExecutionComplete executionComplete) {}

  @Override
  public void onExecutionDead(Execution execution) {}

  @Override
  public void onExecutionFailedHeartbeat(CurrentlyExecuting currentlyExecuting) {}

  @Override
  public void onSchedulerEvent(SchedulerEventType type) {}

  @Override
  public void onCandidateEvent(CandidateEventType type) {}
}
