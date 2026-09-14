package io.github.littleshystar02.lss02.databases.mybatis;

import org.apache.ibatis.session.Configuration;

public final class MapperRegistrar {

    private MapperRegistrar() {
    }

    public static void registerPackage(Configuration configuration, String basePackage) {
        String marker = "mapper-package:" + basePackage;
        if (!configuration.isResourceLoaded(marker)) {
            configuration.addMappers(basePackage);
            configuration.addLoadedResource(marker);
        }
    }

    public static void registerMapper(Configuration configuration, Class<?> mapperClass) {
        if (!configuration.hasMapper(mapperClass)) {
            configuration.addMapper(mapperClass);
        }
    }
}
