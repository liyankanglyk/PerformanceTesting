package com.xiaohua.performancetesting.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;

@Component
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final DataSource dataSource;

    @Value("${spring.datasource.url}")
    private String url;

    @Value("${spring.datasource.username}")
    private String user;

    @Value("${spring.datasource.password}")
    private String pass;

    public DataInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // Step 1: create database via raw JDBC (before DataSource is used)
        String baseUrl = url.substring(0, url.lastIndexOf('/'));
        String dbName = url.substring(url.lastIndexOf('/') + 1);
        if (dbName.contains("?")) dbName = dbName.substring(0, dbName.indexOf("?"));

        try (Connection conn = DriverManager.getConnection(baseUrl, user, pass)) {
            conn.createStatement().execute(
                    "CREATE DATABASE IF NOT EXISTS `" + dbName + "` DEFAULT CHARACTER SET utf8mb4");
            log.info("Database '{}' ensured", dbName);
        }

        // Step 2: execute schema.sql + data.sql
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource("schema.sql"));
        populator.addScript(new ClassPathResource("data.sql"));
        populator.setContinueOnError(false);
        populator.execute(dataSource);
        log.info("Schema and data initialized");
    }
}
