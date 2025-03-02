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
package io.trino.plugin.hive;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.RecursiveDeleteOption;
import com.google.common.reflect.ClassPath;
import io.airlift.log.Logger;
import io.trino.filesystem.Location;
import io.trino.plugin.hive.metastore.Column;
import io.trino.plugin.hive.metastore.Database;
import io.trino.plugin.hive.metastore.HiveMetastore;
import io.trino.plugin.hive.metastore.PrincipalPrivileges;
import io.trino.plugin.hive.metastore.SortingColumn;
import io.trino.plugin.hive.metastore.StorageFormat;
import io.trino.plugin.hive.metastore.Table;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ConnectorMetadata;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.predicate.TupleDomain;
import io.trino.spi.security.PrincipalType;
import io.trino.testing.MaterializedResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static com.google.common.io.MoreFiles.deleteRecursively;
import static com.google.common.io.RecursiveDeleteOption.ALLOW_INSECURE;
import static io.trino.plugin.hive.HiveMetadata.PRESTO_QUERY_ID_NAME;
import static io.trino.plugin.hive.HiveMetadata.PRESTO_VERSION_NAME;
import static io.trino.plugin.hive.HiveMetadata.TABLE_COMMENT;
import static io.trino.plugin.hive.HiveStorageFormat.ORC;
import static io.trino.plugin.hive.HiveStorageFormat.TEXTFILE;
import static io.trino.plugin.hive.HiveTestUtils.HDFS_ENVIRONMENT;
import static io.trino.plugin.hive.HiveType.HIVE_INT;
import static io.trino.plugin.hive.HiveType.HIVE_STRING;
import static io.trino.plugin.hive.TableType.EXTERNAL_TABLE;
import static io.trino.plugin.hive.TableType.MANAGED_TABLE;
import static io.trino.plugin.hive.metastore.PrincipalPrivileges.NO_PRIVILEGES;
import static io.trino.plugin.hive.metastore.StorageFormat.fromHiveStorageFormat;
import static io.trino.plugin.hive.util.HiveBucketing.BucketingVersion.BUCKETING_V1;
import static io.trino.plugin.hive.util.HiveUtil.SPARK_TABLE_PROVIDER_KEY;
import static java.nio.file.Files.copy;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;
import static org.testng.Assert.assertEquals;

@TestInstance(PER_CLASS)
public abstract class AbstractTestHiveLocal
        extends AbstractTestHive
{
    private static final Logger log = Logger.get(AbstractTestHiveLocal.class);
    private static final String DEFAULT_TEST_DB_NAME = "test";

    private File tempDir;
    private final String testDbName;

    protected AbstractTestHiveLocal()
    {
        this(DEFAULT_TEST_DB_NAME);
    }

    protected AbstractTestHiveLocal(String testDbName)
    {
        this.testDbName = requireNonNull(testDbName, "testDbName is null");
    }

    protected abstract HiveMetastore createMetastore(File tempDir);

    @BeforeAll
    public void initialize()
            throws Exception
    {
        tempDir = Files.createTempDirectory(null).toFile();

        HiveMetastore metastore = createMetastore(tempDir);

        metastore.createDatabase(
                Database.builder()
                        .setDatabaseName(testDbName)
                        .setOwnerName(Optional.of("public"))
                        .setOwnerType(Optional.of(PrincipalType.ROLE))
                        .build());

        HiveConfig hiveConfig = new HiveConfig()
                .setParquetTimeZone("America/Los_Angeles")
                .setRcfileTimeZone("America/Los_Angeles");

        setup(testDbName, hiveConfig, metastore, HDFS_ENVIRONMENT);

        createTestTables();
    }

    protected void createTestTables()
            throws Exception
    {
        Location location = Location.of((metastoreClient.getDatabase(database).orElseThrow()
                .getLocation().orElseThrow()));

        createTestTable(
                // Matches create-test.sql » trino_test_partition_format
                Table.builder()
                        .setDatabaseName(database)
                        .setTableName(tablePartitionFormat.getTableName())
                        .setTableType(MANAGED_TABLE.name())
                        .setOwner(Optional.empty())
                        .setDataColumns(List.of(
                                new Column("t_string", HiveType.HIVE_STRING, Optional.empty(), Map.of()),
                                new Column("t_tinyint", HiveType.HIVE_BYTE, Optional.empty(), Map.of()),
                                new Column("t_smallint", HiveType.HIVE_SHORT, Optional.empty(), Map.of()),
                                new Column("t_int", HiveType.HIVE_INT, Optional.empty(), Map.of()),
                                new Column("t_bigint", HiveType.HIVE_LONG, Optional.empty(), Map.of()),
                                new Column("t_float", HiveType.HIVE_FLOAT, Optional.empty(), Map.of()),
                                new Column("t_boolean", HiveType.HIVE_BOOLEAN, Optional.empty(), Map.of())))
                        .setPartitionColumns(List.of(
                                new Column("ds", HiveType.HIVE_STRING, Optional.empty(), Map.of()),
                                new Column("file_format", HiveType.HIVE_STRING, Optional.empty(), Map.of()),
                                new Column("dummy", HiveType.HIVE_INT, Optional.empty(), Map.of())))
                        .setParameter(TABLE_COMMENT, "Presto test data")
                        .withStorage(storage -> storage
                                .setStorageFormat(fromHiveStorageFormat(new HiveConfig().getHiveStorageFormat()))
                                .setLocation(Optional.of(location.appendPath(tablePartitionFormat.getTableName()).toString())))
                        .build());

        createTestTable(
                // Matches create-test.sql » trino_test_partition_format
                Table.builder()
                        .setDatabaseName(database)
                        .setTableName(tableUnpartitioned.getTableName())
                        .setTableType(MANAGED_TABLE.name())
                        .setOwner(Optional.empty())
                        .setDataColumns(List.of(
                                new Column("t_string", HiveType.HIVE_STRING, Optional.empty(), Map.of()),
                                new Column("t_tinyint", HiveType.HIVE_BYTE, Optional.empty(), Map.of())))
                        .setParameter(TABLE_COMMENT, "Presto test data")
                        .withStorage(storage -> storage
                                .setStorageFormat(fromHiveStorageFormat(TEXTFILE))
                                .setLocation(Optional.of(location.appendPath(tableUnpartitioned.getTableName()).toString())))
                        .build());
    }

    protected void createTestTable(Table table)
            throws Exception
    {
        metastoreClient.createTable(table, NO_PRIVILEGES);
    }

    @AfterAll
    public void cleanup()
            throws IOException
    {
        try {
            for (String tableName : metastoreClient.getAllTables(database)) {
                metastoreClient.dropTable(database, tableName, true);
            }
            metastoreClient.dropDatabase(testDbName, true);
        }
        finally {
            deleteRecursively(tempDir.toPath(), ALLOW_INSECURE);
        }
    }

    @Override
    protected ConnectorTableHandle getTableHandle(ConnectorMetadata metadata, SchemaTableName tableName)
    {
        if (tableName.getTableName().startsWith(TEMPORARY_TABLE_PREFIX)) {
            return super.getTableHandle(metadata, tableName);
        }
        return abort("tests using existing tables are not supported");
    }

    @Test
    @Override
    public void testGetAllTableColumns()
    {
        abort("Test disabled for this subclass");
    }

    @Test
    @Override
    public void testGetAllTableColumnsInSchema()
    {
        abort("Test disabled for this subclass");
    }

    @Test
    @Override
    public void testGetTableNames()
    {
        abort("Test disabled for this subclass");
    }

    @Test
    @Override
    public void testGetTableSchemaOffline()
    {
        abort("Test disabled for this subclass");
    }

    @Test
    public void testSparkBucketedTableValidation()
            throws Exception
    {
        SchemaTableName table = temporaryTable("spark_bucket_validation");
        try {
            doTestSparkBucketedTableValidation(table);
        }
        finally {
            dropTable(table);
        }
    }

    private void doTestSparkBucketedTableValidation(SchemaTableName tableName)
            throws Exception
    {
        Path externalLocation = copyResourceDirToTemporaryDirectory("spark_bucketed_nation");
        try {
            createExternalTable(
                    tableName,
                    ORC,
                    ImmutableList.of(
                            new Column("nationkey", HIVE_INT, Optional.empty()),
                            new Column("name", HIVE_STRING, Optional.empty()),
                            new Column("regionkey", HIVE_INT, Optional.empty()),
                            new Column("comment", HIVE_STRING, Optional.empty())),
                    ImmutableList.of(),
                    Optional.of(new HiveBucketProperty(
                            ImmutableList.of("nationkey"),
                            BUCKETING_V1,
                            3,
                            ImmutableList.of(new SortingColumn("name", SortingColumn.Order.ASCENDING)))),
                    Location.of(externalLocation.toUri().toString()));

            assertReadFailsWithMessageMatching(ORC, tableName, "Hive table is corrupt\\. File '.*/.*' is for bucket [0-2], but contains a row for bucket [0-2].");
            markTableAsCreatedBySpark(tableName, "orc");
            assertReadReturnsRowCount(ORC, tableName, 25);
        }
        finally {
            deleteRecursively(externalLocation, RecursiveDeleteOption.ALLOW_INSECURE);
        }
    }

    private void assertReadReturnsRowCount(HiveStorageFormat storageFormat, SchemaTableName tableName, int rowCount)
            throws Exception
    {
        try (Transaction transaction = newTransaction()) {
            ConnectorSession session = newSession();
            ConnectorMetadata metadata = transaction.getMetadata();
            metadata.beginQuery(session);
            ConnectorTableHandle tableHandle = getTableHandle(metadata, tableName);
            List<ColumnHandle> columnHandles = filterNonHiddenColumnHandles(metadata.getColumnHandles(session, tableHandle).values());
            MaterializedResult result = readTable(transaction, tableHandle, columnHandles, session, TupleDomain.all(), OptionalInt.empty(), Optional.of(storageFormat));
            assertEquals(result.getRowCount(), rowCount);
        }
    }

    private void markTableAsCreatedBySpark(SchemaTableName tableName, String provider)
    {
        try (Transaction transaction = newTransaction()) {
            ConnectorSession session = newSession();
            PrincipalPrivileges principalPrivileges = testingPrincipalPrivilege(session);
            Table oldTable = transaction.getMetastore().getTable(tableName.getSchemaName(), tableName.getTableName()).get();
            Table.Builder newTable = Table.builder(oldTable).setParameter(SPARK_TABLE_PROVIDER_KEY, provider);
            transaction.getMetastore().replaceTable(tableName.getSchemaName(), tableName.getTableName(), newTable.build(), principalPrivileges);
            transaction.commit();
        }
    }

    private void createExternalTable(SchemaTableName schemaTableName, HiveStorageFormat hiveStorageFormat, List<Column> columns, List<Column> partitionColumns, Optional<HiveBucketProperty> bucketProperty, Location externalLocation)
    {
        try (Transaction transaction = newTransaction()) {
            ConnectorSession session = newSession();

            String tableOwner = session.getUser();
            String schemaName = schemaTableName.getSchemaName();
            String tableName = schemaTableName.getTableName();

            Table.Builder tableBuilder = Table.builder()
                    .setDatabaseName(schemaName)
                    .setTableName(tableName)
                    .setOwner(Optional.of(tableOwner))
                    .setTableType(EXTERNAL_TABLE.name())
                    .setParameters(ImmutableMap.of(
                            PRESTO_VERSION_NAME, TEST_SERVER_VERSION,
                            PRESTO_QUERY_ID_NAME, session.getQueryId()))
                    .setDataColumns(columns)
                    .setPartitionColumns(partitionColumns);

            tableBuilder.getStorageBuilder()
                    .setLocation(externalLocation.toString())
                    .setStorageFormat(StorageFormat.create(hiveStorageFormat.getSerde(), hiveStorageFormat.getInputFormat(), hiveStorageFormat.getOutputFormat()))
                    .setBucketProperty(bucketProperty)
                    .setSerdeParameters(ImmutableMap.of());

            PrincipalPrivileges principalPrivileges = testingPrincipalPrivilege(tableOwner, session.getUser());
            transaction.getMetastore().createTable(session, tableBuilder.build(), principalPrivileges, Optional.of(externalLocation), Optional.empty(), true, ZERO_TABLE_STATISTICS, false);

            transaction.commit();
        }
    }

    private Path copyResourceDirToTemporaryDirectory(String resourceName)
            throws IOException
    {
        Path tempDir = java.nio.file.Files.createTempDirectory(getClass().getSimpleName()).normalize();
        log.info("Copying resource dir '%s' to %s", resourceName, tempDir);
        ClassPath.from(getClass().getClassLoader())
                .getResources().stream()
                .filter(resourceInfo -> resourceInfo.getResourceName().startsWith(resourceName))
                .forEach(resourceInfo -> {
                    try {
                        Path target = tempDir.resolve(resourceInfo.getResourceName());
                        java.nio.file.Files.createDirectories(target.getParent());
                        try (InputStream inputStream = resourceInfo.asByteSource().openStream()) {
                            copy(inputStream, target);
                        }
                    }
                    catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
        return tempDir.resolve(resourceName).normalize();
    }
}
