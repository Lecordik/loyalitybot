package org.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.sql.*;
import java.util.List;

@Component
public class DatabaseInitializer {
    @Value("${spring.datasource.url}")
    private String dbUrl;

    @Value("${spring.datasource.username}")
    private String dbUser;

    @Value("${spring.datasource.password}")
    private String dbPassword;

    public void initDatabase() {
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
            Statement stmt = conn.createStatement();
            stmt.execute("CREATE TABLE IF NOT EXISTS clients (" +
                    "client_id SERIAL PRIMARY KEY, " +
                    "telegram_id BIGINT NOT NULL, " +
                    "unique_code TEXT NOT NULL, " +
                    "bonus_balance DOUBLE PRECISION NOT NULL)");
            stmt.execute("CREATE TABLE IF NOT EXISTS purchases (" +
                    "purchase_id SERIAL PRIMARY KEY, " +
                    "client_id INTEGER NOT NULL, " +
                    "product_id INTEGER NOT NULL, " +
                    "product_name TEXT NOT NULL, " +
                    "purchase_date TIMESTAMP NOT NULL, " +
                    "FOREIGN KEY (client_id) REFERENCES clients(client_id))");
            stmt.execute("CREATE TABLE IF NOT EXISTS products (" +
                    "product_id INTEGER PRIMARY KEY, " +
                    "name TEXT NOT NULL, " +
                    "inn TEXT NOT NULL, " +
                    "points INTEGER NOT NULL)");
            stmt.execute("CREATE TABLE IF NOT EXISTS orders (" +
                    "order_id SERIAL PRIMARY KEY, " +
                    "client_name TEXT NOT NULL, " +
                    "client_phone TEXT NOT NULL, " +
                    "items TEXT NOT NULL, " +
                    "total_points INTEGER NOT NULL, " +
                    "order_date TIMESTAMP NOT NULL)");

            loadProductsFromJson(conn);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void loadProductsFromJson(Connection conn) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            File jsonFile = new File("src/main/resources/products.json");
            ProductJson productJson = mapper.readValue(jsonFile, ProductJson.class);
            String sql = "INSERT INTO products (product_id, name, inn, points) VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT (product_id) DO NOTHING";
            PreparedStatement stmt = conn.prepareStatement(sql);
            for (ProductJson.Product p : productJson.products) {
                if (p.id <= 0 || p.name == null || p.inn == null || p.points <= 0) {
                    System.err.println("Invalid product data: " + p.id + ", " + p.name + ", " + p.inn + ", " + p.points);
                    continue;
                }
                stmt.setInt(1, p.id);
                stmt.setString(2, p.name);
                stmt.setString(3, p.inn);
                stmt.setInt(4, p.points);
                stmt.executeUpdate();
                System.out.println("Inserted product: " + p.name);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static class ProductJson {
        public List<Product> products;

        public static class Product {
            public int id;
            public String name;
            public String inn;
            public int points;
            public String description;
        }
    }
}