package io.github.littleshystar02.lss02.databases.mybatis;

import com.zaxxer.hikari.HikariDataSource;
import io.github.littleshystar02.lss02.databases.cluster.DatabaseDefinition;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

/** Builds the MyBatis {@link SqlSessionFactory} of one database of the cluster. */
public final class ClusterSqlSessionFactoryProvider {

    /** Not instantiable: this class only holds static helpers. */
    private ClusterSqlSessionFactoryProvider() {
    }

    /**
     * Creates a session factory that uses the given pool and JDBC transactions, and registers the mapper
     * package of the definition when one is configured.
     *
     * @param definition the database definition, source of the environment name and mapper package
     * @param dataSource the connection pool the sessions draw from
     * @return the session factory
     */
    public static SqlSessionFactory create(DatabaseDefinition definition, HikariDataSource dataSource) {
        Environment environment = new Environment(definition.getName(), new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);

        if (definition.getMapperBasePackage() != null) {
            MapperRegistrar.registerPackage(configuration, definition.getMapperBasePackage());
        }

        return new SqlSessionFactoryBuilder().build(configuration);
    }
}
