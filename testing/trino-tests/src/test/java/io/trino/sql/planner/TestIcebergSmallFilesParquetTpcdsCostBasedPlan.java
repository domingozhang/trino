/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.trino.sql.planner;

import java.util.List;

/**
 * This class tests cost-based optimization rules related to joins. It contains unmodified TPC-DS queries.
 * This class is using Iceberg connector unpartitioned TPC-DS tables.
 */
public class TestIcebergSmallFilesParquetTpcdsCostBasedPlan
        extends BaseIcebergCostBasedPlanTest
{
    public TestIcebergSmallFilesParquetTpcdsCostBasedPlan()
    {
        super("tpcds_50mb_files_sf1000_parquet", "parquet", false, true);
    }

    @Override
    protected void doPrepareTables()
    {
        io.trino.tpcds.Table.getBaseTables().forEach(table -> {
            if (table == io.trino.tpcds.Table.DBGEN_VERSION) {
                return;
            }
            populateTableFromResource(
                    table.getName(),
                    "iceberg_small_files/tpcds/sf1000/parquet/unpartitioned/" + table.getName(),
                    "iceberg-50MB-files-tpcds-sf1000-PARQUET/" + table.getName());
        });
    }

    @Override
    protected List<String> getQueryResourcePaths()
    {
        return TPCDS_SQL_FILES;
    }

    public static void main(String[] args)
    {
        new TestIcebergSmallFilesParquetTpcdsCostBasedPlan().generate();
    }
}
