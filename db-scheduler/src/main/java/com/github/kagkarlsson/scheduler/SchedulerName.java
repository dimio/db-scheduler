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
package com.github.kagkarlsson.scheduler;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface SchedulerName {

  String getName();

  class Fixed implements SchedulerName {
    private final String name;

    public Fixed(String name) {
      this.name = name;
    }

    @Override
    public String getName() {
      return name;
    }
  }

  class Hostname implements SchedulerName {
    private static final Logger LOG = LoggerFactory.getLogger(Hostname.class);
    private String cachedHostname;

    public Hostname() {
      try {
        long start = System.currentTimeMillis();
        LOG.debug("Resolving hostname..");
        cachedHostname = InetAddress.getLocalHost().getHostName();
        LOG.debug("Resolved hostname..");
        long duration = System.currentTimeMillis() - start;
        if (duration > 1000) {
          LOG.warn("Hostname-lookup took {}ms", duration);
        }
      } catch (UnknownHostException e) {
        LOG.warn("Failed to resolve hostname. Using dummy-name for scheduler.");
        cachedHostname = "failed.hostname.lookup";
      }
    }

    @Override
    public String getName() {
      return cachedHostname;
    }
  }
}
