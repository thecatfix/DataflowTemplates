/*
 * Copyright (C) 2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.teleport.it.gcp.datastore.matchers;

import static com.google.cloud.teleport.it.truthmatchers.PipelineAsserts.assertThatRecords;

import com.google.cloud.datastore.Entity;
import com.google.cloud.teleport.it.truthmatchers.RecordsSubject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DatastoreAsserts {

  /**
   * Convert Datastore {@link com.google.cloud.datastore.QueryResults} to a list of maps.
   *
   * @param results Results to parse.
   * @return List of maps to use in {@link RecordsSubject}.
   */
  public static List<Map<String, Object>> datastoreResultsToRecords(Collection<Entity> results) {
    try {
      List<Map<String, Object>> records = new ArrayList<>();

      for (Entity entity : results) {
        Map<String, Object> converted = new HashMap<>();

        for (Map.Entry<String, com.google.cloud.datastore.Value<?>> entry :
            entity.getProperties().entrySet()) {
          converted.put(entry.getKey(), entry.getValue().get());
        }
        records.add(converted);
      }

      return records;
    } catch (Exception e) {
      throw new RuntimeException("Error converting Datastore Entities to Records", e);
    }
  }

  /**
   * Creates a {@link RecordsSubject} to assert information within a list of records.
   *
   * @param results Records in Datastore {@link com.google.cloud.datastore.Entity} format to use in
   *     the comparison.
   * @return Truth subject to chain assertions.
   */
  public static RecordsSubject assertThatDatastoreRecords(Collection<Entity> results) {
    return assertThatRecords(datastoreResultsToRecords(results));
  }
}
