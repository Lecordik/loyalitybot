package org.example;

import java.util.List;

public class OrderRequest {
    private String name;
    private String phone;
    private List<Item> items;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public List<Item> getItems() { return items; }
    public void setItems(List<Item> items) { this.items = items; }

    public static class Item {
        private String name;
        private int points;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getPoints() { return points; }
        public void setPoints(int points) { this.points = points; }
    }
}
