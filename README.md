# LSS02DatabasesLib

LSS02DatabasesLib is a Java library for applications that talk to more than one database through [MyBatis](https://mybatis.org/mybatis-3/). Instead of wiring a `SqlSessionFactory` by hand for every database and juggling connection pools yourself, you describe every database your application uses in a single YAML file, and the library builds a pooled, ready-to-use `SqlSessionFactory` for each one, keyed by a name you choose.

It builds on [LSS02ConfigurationLib](https://github.com/LittleShyStar02/LSS02ConfigurationLib) to load that YAML file, and on [HikariCP](https://github.com/brettwooldridge/HikariCP) for pooling. Entities and mappers can live in the same project as the cluster configuration, or in a separate jar shared across several projects that talk to the same schema — MyBatis resolves mapper packages from the classpath either way, so nothing special is needed for the shared-jar case.

The full tutorial is published at [littleshystar02.github.io](https://littleshystar02.github.io/) (link to be finalized once the docs site is live).

## How it works

You write one YAML file describing every database your application needs, under a `database-cluster` root key. Each entry is independent: different engines, different connection details, different mapper packages.

```yaml
database-cluster:
  primary-mysql:
    type: mysql
    host: 127.0.0.1
    port: 3306
    database: shop_prod
    username: shop_app
    password: ${SHOP_DB_PASSWORD}
    options:
      useSSL: "false"
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

`${SHOP_DB_PASSWORD}` is resolved from the process environment when the file is loaded. A referenced variable that isn't set is a hard failure at load time, not a blank password silently sent to the database.

Loading the cluster and getting a `DatabaseCluster` out of it is two lines:

```java
DatabaseClusterConfiguration configuration = new DatabaseClusterConfiguration(new File("cluster.yml"));
configuration.load();

DatabaseCluster cluster = new DatabaseCluster(configuration);
```

Construction is eager: every database entry gets its pool built and its schema bootstrapped immediately, so a misconfigured entry fails at startup instead of on the first request that happens to touch it. If one entry fails, the pools already opened for the earlier entries are closed before the exception reaches you.

Two more constructors cover the cases where the YAML file is not the right entry point:

```java
DatabaseCluster cluster = new DatabaseCluster(configuration, plugin.getClass().getClassLoader());

DatabaseCluster cluster = new DatabaseCluster(List.of(definition));
```

The `ClassLoader` argument is the one used to resolve `classpath:` scripts and mapper packages while the cluster is built, which is what you want inside a Bukkit/Paper plugin or any other isolated class loader. Without it the thread's context class loader is used, as before. `classpath:` migration folders also work when they live inside a jar. A `DatabaseDefinition` can be built in code with its builder, so a cluster does not need a YAML file at all; database names must be unique.

## Running a mapper

`DatabaseCluster` never hands you a mapper proxy detached from its session — a MyBatis mapper stops working the moment its session closes, so the library only lets you use one inside a properly scoped session:

```java
cluster.useMapper("primary-mysql", UserMapper.class, mapper -> {
    mapper.insert(new User(1, "Ada"));
});

List<User> users = cluster.withMapper("primary-mysql", UserMapper.class, UserMapper::findAll);
```

`useMapper` and `withMapper` open a session, hand you the mapper, commit, and close the session for you. If you need more control — a transaction spanning several mapper calls, for instance — `cluster.openSession(name)` gives you the raw `SqlSession`.

## Schema setup and migrations

Each database entry can point at a `setup-script` (run once, the first time that database is bootstrapped) and a `versioned-scripts-path` directory of `V<n>__description.sql` files, applied in order. Every applied script — the setup script and each versioned one — is recorded in a `lss02_schema_history` table on that database, keyed by version and a SHA-256 checksum of the script's content. Bootstrapping twice is a no-op the second time; changing the content of a script that has already been applied is a hard failure (`SchemaMigrationException`) rather than a silent skip or a silent re-run.

v0.1.0 only supports the versioned mode. A single mutable `alter.sql` file, tracked as one whole-file checksum, was considered and deliberately left out: tracking an entire file instead of each statement is a weaker guarantee, and pushes the burden of writing every statement idempotently onto you. Versioned scripts don't have that problem.

## Migrating between databases

`db-migration` copies a table's structure and data from one database in the cluster to another, including across different engines — SQLite to MySQL, for instance.

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

This is deliberately scoped down for v0.1.0. It copies column structure and row data for a safe set of common scalar types — integers, `VARCHAR`/`TEXT`, `BOOLEAN`, `DATE`/`TIMESTAMP`, `DECIMAL`/`NUMERIC` — and nothing else. Primary keys, indexes, unique constraints, foreign keys, and auto-increment/sequence semantics are **not** recreated on the target; you add those yourself once the structure and data are in place. A source column whose type isn't in the safe list raises `UnsupportedTypeMappingException` naming the table and column, rather than guessing at a mapping. Auto-increment in particular varies enough between engines (MySQL's `AUTO_INCREMENT`, Postgres's identity columns, SQLite's `ROWID`) that getting it wrong silently would be worse than not touching it at all.

## Thread-safety

`DatabaseCluster` itself is safe to share across threads once constructed — the underlying `SqlSessionFactory` and `DataSource` per database are designed for concurrent use, and `useMapper`/`withMapper` open a fresh session per call. `DatabaseClusterConfiguration` is not: it's a `YamlFile` under the hood, same caveat as in LSS02ConfigurationLib, so load it once before handing it to `DatabaseCluster` rather than mutating it concurrently afterward.

## Modules and requirements

| Module | Requires | Depends on |
|---|---|---|
| `db-core` | Java 17 | LSS02ConfigurationLib, MyBatis, HikariCP |
| `db-migration` | Java 17 | `db-core` |

Neither module bundles a JDBC driver. Add the one you need — `mysql-connector-j`, `org.postgresql:postgresql`, `org.xerial:sqlite-jdbc`, `org.mariadb.jdbc:mariadb-java-client` — as a `runtimeOnly` dependency in your own build.

## Adding it to your build

```kotlin
dependencies {
    implementation("io.github.littleshystar02:db-core:0.1.3")
    implementation("io.github.littleshystar02:db-migration:0.1.3") // only if you need cross-database migration
    runtimeOnly("com.mysql:mysql-connector-j:9.3.0") // whichever drivers your cluster actually uses
}
```

## License

LSS02DatabasesLib is licensed under the [GNU General Public License v3.0](LICENSE.md). You're free to use, modify and share it, including commercially, as long as any distributed derivative work is released under the same license with its source code available.
