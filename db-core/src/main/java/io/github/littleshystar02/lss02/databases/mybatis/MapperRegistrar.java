package io.github.littleshystar02.lss02.databases.mybatis;

import org.apache.ibatis.session.Configuration;

/** Registers MyBatis mapper interfaces on a {@link Configuration} without registering them twice. */
public final class MapperRegistrar {

    /** Not instantiable: this class only holds static helpers. */
    private MapperRegistrar() {
    }

    /**
     * Registers every mapper interface found in a package. A marker resource is stored in the
     * configuration so repeated calls for the same package do nothing.
     *
     * @param configuration the MyBatis configuration to extend
     * @param basePackage the package to scan for mapper interfaces
     */
    public static void registerPackage(Configuration configuration, String basePackage) {
        String marker = "mapper-package:" + basePackage;
        if (!configuration.isResourceLoaded(marker)) {
            configuration.addMappers(basePackage);
            configuration.addLoadedResource(marker);
        }
    }

    /**
     * Registers a single mapper interface if it is not registered yet.
     *
     * @param configuration the MyBatis configuration to extend
     * @param mapperClass the mapper interface
     */
    public static void registerMapper(Configuration configuration, Class<?> mapperClass) {
        if (!configuration.hasMapper(mapperClass)) {
            configuration.addMapper(mapperClass);
        }
    }
}
