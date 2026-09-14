package io.github.littleshystar02.lss02.databases.migration.data;

import io.github.littleshystar02.lss02.databases.migration.DatabaseMigrationException;
import io.github.littleshystar02.lss02.databases.migration.schema.TableSchema;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Collectors;

public final class DataCopier {

    private DataCopier() {
    }

    public static long copy(Connection source, Connection target, TableSchema table, int batchSize, MigrationProgressListener listener) {
        String columns = table.getColumns().stream().map(c -> c.getName()).collect(Collectors.joining(", "));
        String selectSql = "SELECT " + columns + " FROM " + table.getName();
        String insertSql = "INSERT INTO " + table.getName() + " (" + columns + ") VALUES ("
                + table.getColumns().stream().map(c -> "?").collect(Collectors.joining(", ")) + ")";

        boolean originalAutoCommit;
        try {
            originalAutoCommit = target.getAutoCommit();
            target.setAutoCommit(false);
        } catch (SQLException e) {
            throw new DatabaseMigrationException("Could not prepare the target connection for table '" + table.getName() + "'", e);
        }

        listener.onTableStarted(table.getName());
        long totalRows = 0;
        try (Statement selectStatement = source.createStatement();
             ResultSet resultSet = selectStatement.executeQuery(selectSql);
             PreparedStatement insertStatement = target.prepareStatement(insertSql)) {

            int batched = 0;
            while (resultSet.next()) {
                for (int i = 0; i < table.getColumns().size(); i++) {
                    insertStatement.setObject(i + 1, resultSet.getObject(i + 1));
                }
                insertStatement.addBatch();
                batched++;
                totalRows++;

                if (batched >= batchSize) {
                    insertStatement.executeBatch();
                    target.commit();
                    listener.onBatchCopied(table.getName(), batched);
                    batched = 0;
                }
            }
            if (batched > 0) {
                insertStatement.executeBatch();
                target.commit();
                listener.onBatchCopied(table.getName(), batched);
            }

            listener.onTableCompleted(table.getName(), totalRows);
            return totalRows;
        } catch (SQLException e) {
            listener.onError(table.getName(), e);
            throw new DatabaseMigrationException("Could not copy data for table '" + table.getName() + "'", e);
        } finally {
            try {
                target.setAutoCommit(originalAutoCommit);
            } catch (SQLException ignored) {
                // best-effort restore; the connection is likely being closed by the caller anyway
            }
        }
    }
}
