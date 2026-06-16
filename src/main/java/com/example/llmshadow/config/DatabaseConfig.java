package com.example.llmshadow.config;

import java.io.File;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Configuration
public class DatabaseConfig {

    @Bean
    public DataSource dataSource(@Value("${shadow.sqlite.path}") String sqlitePath) {
        File databaseFile = new File(sqlitePath);
        File parent = databaseFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }

        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + databaseFile.getAbsolutePath());
        return dataSource;
    }
}
