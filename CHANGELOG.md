# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses semantic versioning.

## [0.1.5-beta.5]

### Changed
- `config-core` is now 0.1.3-beta.4.
- `DataCopier.copy` rejects a target connection whose transaction is managed by the caller
  (auto-commit off) with a `DatabaseMigrationException`, before touching it, instead of committing or
  rolling back the caller's pending work.
- `TableSchema` also carries the catalog and schema of the table (`getCatalog()`, `getSchema()`).

### Added
- `TableCommittedException`: thrown when `onTableCompleted` of the progress listener fails after the
  table was committed. `DatabaseMigrator.migrateData` counts that table as completed.

### Fixed
- `DatabaseCluster.close()` completes exceptionally the futures of async work discarded by a forced
  shutdown, instead of leaving them pending forever.
- `DataCopier` no longer reports a rollback for a table that was already committed when a listener
  fails; failures of `onTableStarted`/`onBatchCopied` roll back, and `onError` failures are suppressed.
- `DataCopier` restores the auto-commit mode also when the first listener callback fails.
- `SchemaIntrospector` keeps catalog, schema and name, searches the current schema only and rejects
  ambiguous names instead of merging the columns of same-named tables in different schemas.
- `DatabaseMigrator.migrateSchema` reports the real outcome of the compensation: when a drop fails,
  the message lists the tables still on the target together with the original cause.

## [0.1.5-beta.4]

### Added
- Integration tests with Testcontainers for MySQL, PostgreSQL and MariaDB (bootstrap, concurrent
  bootstraps, cross-engine copy, quoting, pool); they run only with Docker (`./gradlew integrationTest`).
- Unit tests on H2 (MySQL and PostgreSQL modes) and SQLite for the bootstrap, the legacy history
  conversion, the data copier, the schema introspector, errors and concurrency.
- JaCoCo coverage gate (80% of lines per module) in `check`.

### Fixed
- `SchemaIntrospector` finds a table whose name differs from the requested one only in case on engines
  that match metadata patterns case-sensitively.
- `DataCopier` reads `Blob` and `Clob` values into `byte[]` and `String` before writing them to the target.

## [0.1.5-beta.3]

### Changed
- Pool defaults, health check, async API, logging and HikariCP 7.
- Build: version catalog, Dependabot, dependency verification, JDBC drivers no longer `api` dependencies.

## [0.1.5-beta.2]

### Changed
- Schema bootstrap now runs on Flyway (database lock, transaction per script, SQL parser, duplicate
  detection); `lss02_schema_history` is adopted as a baseline.
- Hardening: quoted identifiers in `DataCopier`, validated JDBC URLs, masked password in `toString()`.
