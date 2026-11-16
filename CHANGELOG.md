# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses semantic versioning.

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
