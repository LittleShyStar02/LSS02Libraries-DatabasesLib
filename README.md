# LSS02DatabasesLib

LSS02DatabasesLib is a Java library for applications that talk to more than one database through [MyBatis](https://mybatis.org/mybatis-3/). Instead of wiring a `SqlSessionFactory` by hand for every database and juggling connection pools yourself, you describe every database your application uses in a single YAML file, and the library builds a pooled, ready-to-use `SqlSessionFactory` for each one, keyed by a name you choose.

It builds on [LSS02ConfigurationLib](https://github.com/LittleShyStar02/LSS02Libraries-ConfigurationLib) to load that YAML file, and on [HikariCP](https://github.com/brettwooldridge/HikariCP) for pooling. Entities and mappers can live in the same project as the cluster configuration, or in a separate jar shared across several projects that talk to the same schema: MyBatis resolves mapper packages from the classpath either way, so nothing special is needed for the shared-jar case.

The full tutorial is published at [littleshystar02.github.io/LSS02DatabasesLib-Wiki](https://littleshystar02.github.io/LSS02DatabasesLib-Wiki/).

## How it works

You write one YAML file describing every database your application needs, under a `database-cluster` root key. Each entry is independent: different engines, different connection details, different mapper packages.

```yaml
database-cluster:
  primary-mysql:
    type: mysql
    host: db.internal.example.com
    port: 3306
    database: shop_prod
    username: shop_app
    password: ${SHOP_DB_PASSWORD}
    options:
      sslMode: REQUIRED
      serverTimezone: UTC
    pool:
      maximum-pool-size: 10
      minimum-idle: 2
      keepalive-time-ms: 120000
    migrations:
      setup-script: classpath:db/mysql/setup.sql
      versioned-scripts-path: classpath:db/mysql/migrations
    mappers:
      base-package: com.myapp.shop.mysql.mappers

  archive-sqlite:
    type: sqlite
    file-path: ./data/archive.db
    migrations:
      setup-script: classpath:db/sqlite/setup.sql
    mappers:
      base-package: com.myapp.shop.archive.mappers
```

`${SHOP_DB_PASSWORD}` is resolved from the process environment when the file is loaded. A referenced variable that isn't set is a hard failure at load time, not a blank password silently sent to the database. `mappers.base-package` and `mappers.entity-package` are interpolated too; every class in `entity-package` is registered as a MyBatis type alias under its simple name.

Loading the cluster and getting a `DatabaseCluster` out of it is two lines:

```java
DatabaseClusterConfiguration configuration = new DatabaseClusterConfiguration(new File("cluster.yml"));
configuration.load();

DatabaseCluster cluster = new DatabaseCluster(configuration);
```

Construction is eager: every database entry gets its pool built and its schema bootstrapped immediately, so a misconfigured entry fails at startup instead of on the first request that happens to touch it. The pools are opened in parallel (several unreachable servers cost one connection timeout, not one each, and the pool phase is bounded by the largest connection timeout plus 5 s). If one entry fails, every pool already opened is closed before the exception reaches you.

Two more constructors cover the cases where the YAML file is not the right entry point:

```java
DatabaseCluster cluster = new DatabaseCluster(configuration, plugin.getClass().getClassLoader());

DatabaseCluster cluster = new DatabaseCluster(List.of(definition));
```

The `ClassLoader` argument is the one used to resolve `classpath:` scripts and mapper packages while the cluster is built, which is what you want inside a Bukkit/Paper plugin or any other isolated class loader. Without it the thread's context class loader is used, as before. `classpath:` migration folders also work when they live inside a jar. A `DatabaseDefinition` can be built in code with its builder, so a cluster does not need a YAML file at all; database names must be unique.

## Running a mapper

`DatabaseCluster` never hands you a mapper proxy detached from its session: a MyBatis mapper stops working the moment its session closes, so the library only lets you use one inside a properly scoped session:

```java
cluster.useMapper("primary-mysql", UserMapper.class, mapper -> {
    mapper.insert(new User(1, "Ada"));
});

List<User> users = cluster.withMapper("primary-mysql", UserMapper.class, UserMapper::findAll);
```

`useMapper` and `withMapper` open a session, hand you the mapper, commit, and close the session for you. If you need more control (a transaction spanning several mapper calls, for instance), `cluster.openSession(name)` gives you the raw `SqlSession`; it is the manual entry point, so you must close it (try-with-resources), otherwise the connection never returns to the pool.

### Async API

On a server main thread use the non-blocking variants, which run on an executor owned by the cluster (daemon threads sized on the pools, stopped by `close()`):

```java
CompletableFuture<List<User>> users = cluster.withMapperAsync("primary-mysql", UserMapper.class, UserMapper::findAll);
CompletableFuture<Void> saved = cluster.useMapperAsync("primary-mysql", UserMapper.class, m -> m.insert(new User(1, "Ada")));
```

The synchronous methods are unchanged. Hand the result back to your server thread yourself (for example with the Bukkit scheduler).

## Pool defaults, health and logging

Without a `pool` section a server engine gets 10 connections, 2 kept idle, a 5 minute keep-alive and a fail-fast start. A SQLite database gets a single permanent connection (SQLite has one writer), the write-ahead log and a 5 s `busy_timeout`; set `journal_mode` or `busy_timeout` in `options` to override them. Additional `pool` keys: `leak-detection-threshold-ms` (logs the stack trace of a connection that was never returned), `initialization-fail-timeout-ms` (negative starts degraded without an initial connection), `validation-timeout-ms`, `connection-init-sql`, `register-mbeans`. A HikariCP `MetricsTrackerFactory` can be passed as the last constructor argument of `DatabaseCluster`.

`cluster.ping(name)` and `cluster.health()` check the databases and report the pool counters (`DatabaseHealth`).

The library logs through SLF4J (`slf4j-api` is the only logging dependency; bring your own implementation): pool startup, every applied migration with its duration, and errors. Passwords are never logged.

`DatabaseCluster.close()` is idempotent; afterwards every access fails with an `IllegalStateException`.

## Schema setup and migrations

Migrations are run by [Flyway](https://flywaydb.org). Each database entry can point at a `setup-script` (run once, as migration version `0`, the first time that database is bootstrapped) and a `versioned-scripts-path` directory of `V<n>__description.sql` files, applied in ascending version order. Every applied script is recorded in Flyway's `flyway_schema_history` table, keyed by version and checksum. Bootstrapping twice is a no-op the second time; changing the content of a script that has already been applied is a hard failure (`SchemaMigrationException`) rather than a silent skip or a silent re-run.

What Flyway gives you on top of the old home-made runner: a database-level lock for the whole run (two servers starting on the same database never interleave), one transaction per script on engines with transactional DDL (PostgreSQL, SQLite, H2), a real SQL parser (semicolons inside strings, comments, trigger and procedure bodies are fine), rejection of two scripts with the same version, and error messages that name the failing script and statement. MySQL and MariaDB cannot roll back DDL, so a script that fails midway there leaves a failed entry in the history that has to be repaired by hand.

Databases bootstrapped by releases before 0.1.5 keep their `lss02_schema_history` table. The first start with Flyway adopts it automatically: the highest applied version becomes the baseline, nothing runs again, and the old table is left in place (you can drop it later). A new script must have a version higher than that baseline.

The library bundles Flyway with the H2, SQLite, MySQL, MariaDB and PostgreSQL support; only the JDBC driver itself is yours to declare (see "JDBC drivers").

## Migrating between databases

`db-migration` copies a table's structure and data from one database in the cluster to another, including across different engines, such as SQLite to MySQL.

```java
DatabaseMigrator.migrateSchema(
        cluster.getDataSource("archive-sqlite"),
        cluster.getDataSource("primary-mysql"),
        DatabaseType.MYSQL,
        List.of("users", "orders"));

MigrationResult result = DatabaseMigrator.migrateData(
        cluster.getDataSource("archive-sqlite"),
        cluster.getDataSource("primary-mysql"),
        List.of("users", "orders"),
        500);
```

Tables are copied one at a time, each in a single transaction: a failure rolls the failing table back, so nothing is left half copied. Tables that completed before the failure stay committed; pass `skipNonEmptyTargets = true` to `migrateData` to resume a partial migration without duplicating them. Table and column names are quoted with the dialect of each database and table names must exist in the source, so reserved words and mixed case work and nothing can be injected through them. Rows are streamed (MySQL/MariaDB) or fetched through a cursor (PostgreSQL) instead of being loaded into memory.

The schema step copies column structure, nullability and the primary key. Supported types:

| Source JDBC type | MySQL / MariaDB | PostgreSQL | SQLite |
| --- | --- | --- | --- |
| `TINYINT`, `SMALLINT` | `SMALLINT` | `SMALLINT` | `INTEGER` |
| `INTEGER` / `BIGINT` | `INT` / `BIGINT` | `INTEGER` / `BIGINT` | `INTEGER` |
| `REAL` / `FLOAT`, `DOUBLE` | `FLOAT` / `DOUBLE` | `REAL` / `DOUBLE PRECISION` | `REAL` |
| `VARCHAR`, `CHAR` | `VARCHAR(n)` (`LONGTEXT` above 16383) | `VARCHAR(n)` | `TEXT` |
| `TEXT`, `CLOB` | `TEXT` | `TEXT` | `TEXT` |
| `BOOLEAN`, `BIT` | `BOOLEAN` | `BOOLEAN` | `INTEGER` |
| `DATE`, `TIME`, `TIMESTAMP` | same | same | `TEXT` |
| `DECIMAL`, `NUMERIC` | `DECIMAL(p,s)` (precision and scale kept) | `NUMERIC(p,s)` | `NUMERIC` |
| `BINARY`, `VARBINARY`, `BLOB` | `LONGBLOB` | `BYTEA` | `BLOB` |

Any other source type (for example `ARRAY`, `JSON`, `UUID`) raises `UnsupportedTypeMappingException` naming the table and column, rather than guessing at a mapping. The primary key is recreated unless a key column cannot be indexed safely in the target (for example a `TEXT`/`BLOB` column, or a `VARCHAR` longer than 768 on MySQL). Indexes, unique constraints, foreign keys, defaults and auto-increment/sequence semantics are **not** recreated; you add those yourself once the structure and data are in place. Auto-increment in particular varies enough between engines (MySQL's `AUTO_INCREMENT`, Postgres's identity columns, SQLite's `ROWID`) that getting it wrong silently would be worse than not touching it at all.

## Security

- **Use TLS for remote databases.** Set `sslMode: REQUIRED` (or `VERIFY_CA` / `VERIFY_IDENTITY`) for MySQL and MariaDB, `sslmode: verify-full` for PostgreSQL. The library logs a warning at startup for a non-loopback host without TLS.
- **Generated URLs are validated.** `host` and `database` accept only letters, digits and `_ . -` (plus bracketed IPv6 literals), `file-path` must not contain `?`, `;` or line breaks, option names must be plain names, and option values are URL-encoded, so a configuration value cannot add driver parameters. An explicit `jdbc-url` is used verbatim and is your responsibility. Enabling risky MySQL options such as `allowLoadLocalInfile` or `allowMultiQueries` is logged.
- **Secrets stay out of logs.** `DatabaseDefinition.toString()` masks the password and cuts the JDBC URL before its query string. Keep passwords in `${ENV_VARIABLES}` rather than in the file.

## Changes and security reports

See `CHANGELOG.md` for the release notes and `SECURITY.md` to report a vulnerability.

## Thread-safety

`DatabaseCluster` itself is safe to share across threads once constructed: the underlying `SqlSessionFactory` and `DataSource` per database are designed for concurrent use, and `useMapper`/`withMapper` open a fresh session per call. `DatabaseClusterConfiguration` is not: it's a `YamlFile` under the hood, same caveat as in LSS02ConfigurationLib, so load it once before handing it to `DatabaseCluster` rather than mutating it concurrently afterward.

## Modules and requirements

| Module | Requires | Depends on |
|---|---|---|
| `db-core` | Java 17 | LSS02ConfigurationLib, MyBatis, HikariCP, SLF4J API, Flyway |
| `db-migration` | Java 17 | `db-core` |

## JDBC drivers

The library does **not** ship JDBC drivers: it never references a driver class at compile time, so you declare, as `runtimeOnly`, the driver of each engine you actually use (and nothing else ends up in your jar or on your server). Pick the version you want; the table lists the coordinates the library is tested with.

| `type` | Dependency | Tested with |
| --- | --- | --- |
| `mysql` | `com.mysql:mysql-connector-j` | 26.10.0 |
| `mariadb` | `org.mariadb.jdbc:mariadb-java-client` | 4.0.0 |
| `postgresql` | `org.postgresql:postgresql` | 42.7.14 |
| `sqlite` | `org.xerial:sqlite-jdbc` | 3.49.1.0 |

```kotlin
dependencies {
    implementation("io.github.littleshystar02:db-core:0.1.5-beta.4")
    runtimeOnly("org.postgresql:postgresql:42.7.14") // the engine(s) you use
}
```

Flyway's MySQL/MariaDB and PostgreSQL support modules are bundled at runtime by `db-core`, and Flyway itself already includes H2 and SQLite support. If you also use H2 (for example in tests), add `com.h2database:h2` as `testRuntimeOnly`. Paper and Velocity servers ship some drivers (for example SQLite on Paper): do not declare those twice.

## Adding it to your build

```kotlin
dependencies {
    implementation("io.github.littleshystar02:db-core:0.1.5-beta.4")
    implementation("io.github.littleshystar02:db-migration:0.1.5-beta.4") // only if you need cross-database migration
    runtimeOnly("org.postgresql:postgresql:42.7.14") // see "JDBC drivers"
}
```

Both modules are on Maven Central (`mavenCentral()`); the version above is the current `version` in `gradle.properties`, pre-release versions are published only once the release tag is pushed. `db-core` depends on `config-core`, which pulls in SimpleYAML, published only on JitPack: add that repository restricted to its group so nothing else can be resolved from it:

```kotlin
repositories {
    mavenCentral()
    exclusiveContent {
        forRepository { maven("https://jitpack.io") }
        filter { includeGroupAndSubgroups("com.github.Carleslc") }
    }
}
```

## Building, testing and releasing

- Build and test: `./gradlew build` (Gradle wrapper 9.7.1, Java 17 toolchain downloaded by the foojay resolver if missing). All versions live in `gradle/libs.versions.toml` and dependency checksums are pinned in `gradle/verification-metadata.xml`; repositories are declared once in `settings.gradle.kts`.
- Other JDKs: `./gradlew test -PtestJavaVersion=21` runs the tests on Java 21 (compilation stays on the Java 17 toolchain).
- Integration tests: `./gradlew integrationTest` runs the Testcontainers based tests against real MySQL, PostgreSQL and MariaDB servers and needs a running Docker daemon; it is not part of `build`.
- Coverage: JaCoCo reports are written to `<module>/build/reports/jacoco/test/html`; `check` fails below 80% of lines per module. The tests that need no Docker also run H2 in MySQL and PostgreSQL mode and SQLite.
- CI: `.github/workflows/build.yml` runs the build on JDK 17 and 21 on every push and pull request to `main`, plus the integration tests on a Docker-enabled runner. Dependabot keeps Gradle dependencies and GitHub Actions up to date.
- Versions and releases: the library version is the `version` in `gradle.properties` (semantic versioning, currently `0.1.5-beta.4`); each release is a git tag `v<version>`. Pushing the tag runs `.github/workflows/release.yml`, which checks that the tag matches `gradle.properties`, builds, and uploads to Maven Central through the `com.vanniktech.maven.publish` plugin (the deployment is released manually from the Central Portal). The signing key and Central Portal credentials are repository secrets, never stored in the repository. Commits, pushes and tags are done by the owner.
- Related repositories: [LSS02Libraries-ConfigurationLib](https://github.com/LittleShyStar02/LSS02Libraries-ConfigurationLib) (configuration layer used by `db-core`), `LSS02PluginLib-MultiLanguageSystem` (plugin that stores player languages through `db-core`) and `AetheriaMC-WebIntegration` (shared database of the NGC website).

## License

LSS02DatabasesLib is licensed under the [GNU General Public License v3.0](LICENSE.md). You're free to use, modify and share it, including commercially, as long as any distributed derivative work is released under the same license with its source code available.
