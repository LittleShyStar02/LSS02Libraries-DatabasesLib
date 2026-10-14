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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public class DatabaseCluster implements AutoCloseable {

    private final Map<String, DatabaseDefinition> definitions;
    private final Map<String, HikariDataSource> dataSources = new LinkedHashMap<>();
    private final Map<String, SqlSessionFactory> sessionFactories = new LinkedHashMap<>();

    public DatabaseCluster(DatabaseClusterConfiguration configuration) {
        this(configuration, null);
    }

    public DatabaseCluster(DatabaseClusterConfiguration configuration, ClassLoader classLoader) {
        this(ClusterLoader.load(configuration), classLoader);
    }

    public DatabaseCluster(Collection<DatabaseDefinition> definitions) {
        this(definitions, null);
    }

    public DatabaseCluster(Collection<DatabaseDefinition> definitions, ClassLoader classLoader) {
        this(indexByName(definitions), classLoader);
    }

    private DatabaseCluster(Map<String, DatabaseDefinition> definitions, ClassLoader classLoader) {
        this.definitions = definitions;
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        if (classLoader != null) {
            thread.setContextClassLoader(classLoader);
        }
        try {
            for (DatabaseDefinition definition : definitions.values()) {
                HikariDataSource dataSource = DataSourceFactory.create(definition);
                dataSources.put(definition.getName(), dataSource);

                if (definition.getSetupScript() != null || definition.getVersionedScriptsPath() != null) {
                    SchemaBootstrapper.bootstrap(
                            dataSource, definition.getSetupScript(), definition.getVersionedScriptsPath(), classLoader);
                }

                sessionFactories.put(definition.getName(), ClusterSqlSessionFactoryProvider.create(definition, dataSource));
            }
        } catch (RuntimeException e) {
            close();
            throw e;
        } finally {
            if (classLoader != null) {
                thread.setContextClassLoader(previous);
            }
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

    private static Map<String, DatabaseDefinition> indexByName(Collection<DatabaseDefinition> definitions) {
        Map<String, DatabaseDefinition> indexed = new LinkedHashMap<>();
        for (DatabaseDefinition definition : definitions) {
            if (definition.getName() == null) {
                throw new ClusterConfigurationException("Every database definition needs a name");
            }
            if (indexed.put(definition.getName(), definition) != null) {
                throw new ClusterConfigurationException("Duplicate database name '" + definition.getName() + "'");
            }
        }
        return indexed;
    }

    private static <T> T requireEntry(Map<String, T> map, String name) {
        T value = map.get(name);
        if (value == null) {
            throw new ClusterConfigurationException("Unknown database '" + name + "' — not present in the cluster configuration");
        }
        return value;
    }
}
