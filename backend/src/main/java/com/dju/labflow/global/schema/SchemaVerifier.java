package com.dju.labflow.global.schema;

import jakarta.persistence.Entity;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.hibernate.SessionFactory;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.ForeignKey;
import org.hibernate.mapping.Index;
import org.hibernate.mapping.Table;
import org.hibernate.mapping.UniqueKey;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/** baseline 전에 JPA 검증에서 빠지는 필수 키/인덱스도 JDBC 메타데이터로 대조한다. 쓰기 SQL은 실행하지 않는다. */
public final class SchemaVerifier {
    private SchemaVerifier() {}

    public static void verify(String url, String user, String password) throws Exception {
        StandardServiceRegistry registry =
                new StandardServiceRegistryBuilder()
                        .applySetting("jakarta.persistence.jdbc.url", url)
                        .applySetting("jakarta.persistence.jdbc.user", user)
                        .applySetting("jakarta.persistence.jdbc.password", password)
                        .applySetting(
                                "hibernate.physical_naming_strategy",
                                PhysicalNamingStrategySnakeCaseImpl.class.getName())
                        .applySetting("hibernate.hbm2ddl.auto", "validate")
                        .build();
        try {
            MetadataSources sources = new MetadataSources(registry);
            ClassPathScanningCandidateComponentProvider scanner =
                    new ClassPathScanningCandidateComponentProvider(false);
            scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
            for (BeanDefinition entity : scanner.findCandidateComponents("com.dju.labflow"))
                sources.addAnnotatedClass(Class.forName(entity.getBeanClassName()));
            Metadata metadata = sources.buildMetadata();
            try (SessionFactory ignored = metadata.buildSessionFactory();
                    Connection connection = DriverManager.getConnection(url, user, password)) {
                List<String> problems = new ArrayList<>();
                for (org.hibernate.boot.model.relational.Namespace namespace :
                        metadata.getDatabase().getNamespaces())
                    for (Table table : namespace.getTables()) {
                        if (!table.isPhysicalTable()) continue;
                        verifyTable(connection, metadata, table, problems);
                    }
                // The original deployment DDL also requires unique GPU indices within a snapshot.
                requireIndex(
                        indexes(connection, "run_system_metric_gpu"),
                        "run_system_metric_gpu",
                        List.of("system_metric_id", "gpu_index"),
                        true,
                        problems);
                if (!problems.isEmpty())
                    throw new IllegalStateException(
                            "Schema cannot be baselined:\n" + String.join("\n", problems));
            }
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }

    private static void verifyTable(
            Connection connection, Metadata metadata, Table table, List<String> problems)
            throws Exception {
        DatabaseMetaData jdbc = connection.getMetaData();
        String catalog = connection.getCatalog(), name = table.getName();
        Map<String, ActualColumn> columns = new HashMap<>();
        Map<String, ColumnDomain> domains = new HashMap<>();
        try (PreparedStatement query =
                connection.prepareStatement(
                        "select column_name,column_type,datetime_precision,character_set_name from information_schema.columns where table_schema=? and table_name=?")) {
            query.setString(1, catalog);
            query.setString(2, name);
            try (ResultSet result = query.executeQuery()) {
                while (result.next())
                    domains.put(
                            result.getString(1),
                            new ColumnDomain(
                                    result.getString(2), result.getInt(3), result.getString(4)));
            }
        }
        try (ResultSet result = jdbc.getColumns(catalog, null, name, "%")) {
            while (result.next())
                columns.put(
                        result.getString("COLUMN_NAME"),
                        new ActualColumn(
                                result.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                                result.getInt("COLUMN_SIZE"),
                                result.getString("TYPE_NAME")));
        }
        for (Column column : table.getColumns()) {
            ActualColumn actual = columns.get(column.getName());
            if (actual == null)
                continue; // Hibernate already rejects missing columns and incompatible types.
            String expectedSqlType = column.getSqlType(metadata).replace(" ", "");
            String expectedType = expectedSqlType.toLowerCase(Locale.ROOT);
            ColumnDomain domain = domains.get(column.getName());
            if (expectedType.startsWith("enum(")) {
                if (domain == null
                        || !domain.type().startsWith("enum(")
                        || !enumMembers(expectedSqlType).equals(enumMembers(domain.type())))
                    problems.add(name + "." + column.getName() + " has a different enum domain");
            }
            if (expectedType.equals("longtext")
                    && (domain == null || !domain.type().equalsIgnoreCase("longtext")))
                problems.add(name + "." + column.getName() + " must be LONGTEXT");
            if ((expectedType.startsWith("varchar(") || expectedType.equals("longtext"))
                    && (domain == null || !"utf8mb4".equalsIgnoreCase(domain.charset())))
                problems.add(name + "." + column.getName() + " must use utf8mb4");
            if ((expectedType.startsWith("datetime(6)") || expectedType.startsWith("timestamp(6)"))
                    && (domain == null || domain.precision() < 6))
                problems.add(name + "." + column.getName() + " requires microsecond precision");
            if (!column.isNullable() && actual.nullable())
                problems.add(name + "." + column.getName() + " must be NOT NULL");
            if (column.getLength() != null
                    && actual.type().toLowerCase(Locale.ROOT).contains("varchar")
                    && actual.size() < column.getLength())
                problems.add(name + "." + column.getName() + " is too short");
        }
        if (table.getPrimaryKey() != null) {
            TreeMap<Integer, String> primary = new TreeMap<>();
            try (ResultSet result = jdbc.getPrimaryKeys(catalog, null, name)) {
                while (result.next())
                    primary.put(result.getInt("KEY_SEQ"), result.getString("COLUMN_NAME"));
            }
            List<String> expected = names(table.getPrimaryKey().getColumns());
            if (!List.copyOf(primary.values()).equals(expected))
                problems.add(name + " requires primary key " + expected);
        }
        Map<String, ActualIndex> indexes = indexes(connection, name);
        for (Index index : table.getIndexes().values())
            requireIndex(indexes, name, names(index.getColumns()), false, problems);
        for (UniqueKey unique : table.getUniqueKeys().values())
            requireIndex(indexes, name, names(unique.getColumns()), true, problems);
        for (ForeignKey foreign : table.getForeignKeyCollection()) {
            if (!foreign.isPhysicalConstraint()) continue;
            List<String> referenced =
                    names(
                            foreign.getReferencedColumns().isEmpty()
                                    ? foreign.getReferencedTable().getPrimaryKey().getColumns()
                                    : foreign.getReferencedColumns());
            boolean found = false;
            try (ResultSet result = jdbc.getImportedKeys(catalog, null, name)) {
                Map<String, List<String>> relationships = new HashMap<>();
                while (result.next())
                    relationships
                            .computeIfAbsent(result.getString("FK_NAME"), key -> new ArrayList<>())
                            .add(
                                    result.getString("FKCOLUMN_NAME")
                                            + ":"
                                            + result.getString("PKTABLE_NAME")
                                            + ":"
                                            + result.getString("PKCOLUMN_NAME"));
                List<String> expected = new ArrayList<>();
                for (int index = 0; index < foreign.getColumns().size(); index++)
                    expected.add(
                            foreign.getColumns().get(index).getName()
                                    + ":"
                                    + foreign.getReferencedTable().getName()
                                    + ":"
                                    + referenced.get(index));
                found = relationships.values().stream().anyMatch(expected::equals);
            }
            if (!found)
                problems.add(
                        name
                                + " requires foreign key to "
                                + foreign.getReferencedTable().getName());
        }
    }

    private static Map<String, ActualIndex> indexes(Connection connection, String table)
            throws Exception {
        Map<String, ActualIndex> indexes = new HashMap<>();
        try (ResultSet result =
                connection
                        .getMetaData()
                        .getIndexInfo(connection.getCatalog(), null, table, false, false)) {
            while (result.next()) {
                String column = result.getString("COLUMN_NAME");
                if (column == null) continue;
                String name = result.getString("INDEX_NAME");
                boolean unique = !result.getBoolean("NON_UNIQUE");
                ActualIndex index =
                        indexes.computeIfAbsent(
                                name, key -> new ActualIndex(unique, new TreeMap<>()));
                index.columns().put(result.getInt("ORDINAL_POSITION"), column);
            }
        }
        return indexes;
    }

    private static void requireIndex(
            Map<String, ActualIndex> indexes,
            String table,
            List<String> columns,
            boolean unique,
            List<String> problems) {
        boolean found =
                indexes.values().stream()
                        .anyMatch(
                                index ->
                                        (!unique || index.unique())
                                                && List.copyOf(index.columns().values())
                                                        .equals(columns));
        if (!found)
            problems.add(table + " requires " + (unique ? "unique " : "") + "index " + columns);
    }

    private static List<String> names(List<Column> columns) {
        return columns.stream().map(Column::getName).toList();
    }

    private static List<String> enumMembers(String type) {
        return java.util.Arrays.stream(
                        type.substring(type.indexOf('(') + 1, type.lastIndexOf(')')).split(","))
                .map(value -> value.trim().replace("'", ""))
                .sorted()
                .toList();
    }

    private record ActualColumn(boolean nullable, int size, String type) {}

    private record ColumnDomain(String type, int precision, String charset) {}

    private record ActualIndex(boolean unique, TreeMap<Integer, String> columns) {}
}
