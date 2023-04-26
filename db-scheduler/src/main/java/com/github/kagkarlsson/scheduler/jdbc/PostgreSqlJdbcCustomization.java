/**
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
package com.github.kagkarlsson.scheduler.jdbc;

import static com.github.kagkarlsson.scheduler.StringUtils.truncate;

import com.github.kagkarlsson.scheduler.task.Execution;
import java.time.Instant;
import java.util.List;

public class PostgreSqlJdbcCustomization extends DefaultJdbcCustomization {

  @Override
  public String getName() {
    return "PostgreSQL";
  }

  @Override
  public boolean supportsExplicitQueryLimitPart() {
    return true;
  }

  @Override
  public String getQueryLimitPart(int limit) {
    return " LIMIT " + limit;
  }

  @Override
  public boolean supportsLockAndFetch() {
    return true;
  }

  @Override
  public List<Execution> lockAndFetch(JdbcTaskRepositoryContext ctx, Instant now, int limit) {
    final JdbcTaskRepository.UnresolvedFilter unresolvedFilter =
        new JdbcTaskRepository.UnresolvedFilter(ctx.taskResolver.getUnresolved());

    String selectForUpdateQuery =
        " UPDATE "
            + ctx.tableName
            + " st1 SET picked = ?, picked_by = ?, last_heartbeat = ?, version = version + 1 "
            + " WHERE (st1.task_name, st1.task_instance) IN ("
            + "   SELECT st2.task_name, st2.task_instance FROM "
            + ctx.tableName
            + " st2 "
            + "   WHERE picked = ? and execution_time <= ? "
            + unresolvedFilter.andCondition()
            + " order by execution_time asc FOR UPDATE SKIP LOCKED LIMIT ?)"
            + " RETURNING st1.*";

    return ctx.jdbcRunner.query(
        selectForUpdateQuery,
        ps -> {
          int index = 1;
          // Update
          ps.setBoolean(index++, true); // picked (new)
          ps.setString(index++, truncate(ctx.schedulerName.getName(), 50)); // picked_by
          setInstant(ps, index++, now); // last_heartbeat
          // Inner select
          ps.setBoolean(index++, false); // picked (old)
          setInstant(ps, index++, now); // execution_time
          index = unresolvedFilter.setParameters(ps, index);
          ps.setInt(index++, limit); // limit
        },
        ctx.resultSetMapper.get());
  }
}
