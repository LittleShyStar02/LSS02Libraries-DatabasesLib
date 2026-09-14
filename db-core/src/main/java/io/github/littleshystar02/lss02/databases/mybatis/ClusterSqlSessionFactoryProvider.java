package io.github.littleshystar02.lss02.databases.mybatis;

import com.zaxxer.hikari.HikariDataSource;
import io.github.littleshystar02.lss02.databases.cluster.DatabaseDefinition;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;

public final class ClusterSqlSessionFactoryProvider {

    private ClusterSqlSessionFactoryProvider() {
    }

    public static SqlSessionFactory create(DatabaseDefinition definition, HikariDataSource dataSource) {
        Environment environment = new Environment(definition.getName(), new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);

        if (definition.getMapperBasePackage() != null) {
            MapperRegistrar.registerPackage(configuration, definition.getMapperBasePackage());
        }

        return new SqlSessionFactoryBuilder().build(configuration);
    }
}
