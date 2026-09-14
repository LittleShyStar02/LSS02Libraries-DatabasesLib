package io.github.littleshystar02.lss02.databases.mybatis;

import com.zaxxer.hikari.HikariDataSource;
import io.github.littleshystar02.lss02.databases.bootstrap.SchemaBootstrapper;
import io.github.littleshystar02.lss02.databases.cluster.ClusterConfigurationException;
import io.github.littleshystar02.lss02.databases.cluster.ClusterLoader;
import io.github.littleshystar02.lss02.databases.cluster.DatabaseClusterConfiguration;
import io.github.littleshystar02.lss02.databases.cluster.DatabaseDefinition;
import io.github.littleshystar02.lss02.databases.connection.DataSourceFactory;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public class DatabaseCluster implements AutoCloseable {

    private final Map<String, DatabaseDefinition> definitions;
    private final Map<String, HikariDataSource> dataSources = new LinkedHashMap<>();
    private final Map<String, SqlSessionFactory> sessionFactories = new LinkedHashMap<>();

    public DatabaseCluster(DatabaseClusterConfiguration configuration) {
        this.definitions = ClusterLoader.load(configuration);
        for (DatabaseDefinition definition : definitions.values()) {
            HikariDataSource dataSource = DataSourceFactory.create(definition);
            dataSources.put(definition.getName(), dataSource);

            if (definition.getSetupScript() != null || definition.getVersionedScriptsPath() != null) {
                SchemaBootstrapper.bootstrap(dataSource, definition.getSetupScript(), definition.getVersionedScriptsPath());
            }

            sessionFactories.put(definition.getName(), ClusterSqlSessionFactoryProvider.create(definition, dataSource));
        }
    }

    public DatabaseDefinition getDefinition(String name) {
        return requireEntry(definitions, name);
    }

    public DataSource getDataSource(String name) {
        return requireEntry(dataSources, name);
    }

    public SqlSession openSession(String name) {
        return requireEntry(sessionFactories, name).openSession();
    }

    public SqlSession openSession(String name, boolean autoCommit) {
        return requireEntry(sessionFactories, name).openSession(autoCommit);
    }

    public <T, R> R withMapper(String name, Class<T> mapperClass, Function<T, R> action) {
        try (SqlSession session = openSession(name)) {
            R result = action.apply(session.getMapper(mapperClass));
            session.commit();
            return result;
        }
    }

    public <T> void useMapper(String name, Class<T> mapperClass, Consumer<T> action) {
        try (SqlSession session = openSession(name)) {
            action.accept(session.getMapper(mapperClass));
            session.commit();
        }
    }

    @Override
    public void close() {
        for (HikariDataSource dataSource : dataSources.values()) {
            dataSource.close();
        }
    }

    private static <T> T requireEntry(Map<String, T> map, String name) {
        T value = map.get(name);
        if (value == null) {
            throw new ClusterConfigurationException("Unknown database '" + name + "' — not present in the cluster configuration");
        }
        return value;
    }
}
