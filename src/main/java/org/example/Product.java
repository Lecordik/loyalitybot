package org.example;

public class Product {
    int id;
    String name;
    String inn;
    int points;

    public Product(int id, String name, String inn, int points) {
        this.id = id;
        this.name = name;
        this.inn = inn;
        this.points = points;
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public String getInn() { return inn; }
    public int getPoints() { return points; }
}
