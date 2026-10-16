package com.gyeongsan.cabinet.support;

import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

public class MariaDbDriverMySqlContainer extends MySQLContainer<MariaDbDriverMySqlContainer> {

    public MariaDbDriverMySqlContainer(String image) {
        super(compatible(image));
    }

    private static DockerImageName compatible(String image) {
        DockerImageName name = DockerImageName.parse(image);
        return image.startsWith("mariadb") ? name.asCompatibleSubstituteFor("mysql") : name;
    }

    @Override
    public String getDriverClassName() {
        return "org.mariadb.jdbc.Driver";
    }

    @Override
    public String getJdbcUrl() {
        return "jdbc:mariadb://"
                + getHost()
                + ":"
                + getMappedPort(MYSQL_PORT)
                + "/"
                + getDatabaseName();
    }
}
