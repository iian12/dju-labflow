package com.dju.labflow.global.schema;

import org.flywaydb.core.Flyway;

/** 명시한 DB만 검사/편입한다. 앱의 .env를 읽지 않으며 기존 DB의 DDL이나 데이터를 변경하지 않는다. */
public final class SchemaManagementCommand {
    private SchemaManagementCommand() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !java.util.Set.of("info", "check", "baseline").contains(args[0]))
            throw new IllegalArgumentException("Expected info, check or baseline");
        String url = required("LABFLOW_SCHEMA_URL");
        if (!url.startsWith("jdbc:mariadb://"))
            throw new IllegalArgumentException(
                    "LABFLOW_SCHEMA_URL must explicitly select a MariaDB database");
        String user = required("LABFLOW_SCHEMA_USER");
        String password = System.getenv("LABFLOW_SCHEMA_PASSWORD");
        if (password == null)
            throw new IllegalArgumentException("Set LABFLOW_SCHEMA_PASSWORD explicitly");
        execute(args[0], url, user, password);
    }

    public static void execute(String action, String url, String user, String password)
            throws Exception {
        if (!java.util.Set.of("info", "check", "baseline").contains(action))
            throw new IllegalArgumentException("Expected info, check or baseline");
        if (!url.startsWith("jdbc:mariadb://"))
            throw new IllegalArgumentException("An explicit MariaDB database is required");
        String databasePath = java.net.URI.create(url.substring("jdbc:".length())).getPath();
        if (databasePath == null
                || databasePath.length() < 2
                || databasePath.indexOf('/', 1) >= 0
                || java.util.Set.of("mysql", "information_schema", "performance_schema", "sys")
                        .contains(databasePath.substring(1).toLowerCase(java.util.Locale.ROOT)))
            throw new IllegalArgumentException(
                    "Select an application database explicitly; system databases are not supported");
        Flyway flyway =
                Flyway.configure()
                        .dataSource(url, user, password)
                        .locations("classpath:db/migration")
                        .baselineVersion("1")
                        .baselineDescription("Verified current LabFlow V1 schema")
                        .baselineOnMigrate(false)
                        .cleanDisabled(true)
                        .load();
        if (action.equals("info")) {
            for (org.flywaydb.core.api.MigrationInfo migration : flyway.info().all())
                System.out.printf(
                        "%s %s %s%n",
                        migration.getVersion(), migration.getDescription(), migration.getState());
            return;
        }
        SchemaVerifier.verify(url, user, password);
        if (action.equals("baseline")) {
            if (flyway.info().current() != null)
                throw new IllegalStateException(
                        "This database already has Flyway history; do not baseline it again");
            flyway.baseline();
            System.out.println(
                    "Recorded baseline V1. No application tables or records were changed.");
        } else
            System.out.println(
                    "Verified mapped columns, primary keys, required indexes and foreign keys.");
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("Set " + key + " explicitly");
        return value;
    }
}
