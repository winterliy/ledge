package dev.ledge.glap1;

/** Terrain pre-generation so the map has something to show. */
interface Pregenerator {
    String start(String world, double radius, String shape, Double centerX, Double centerZ);
    String pause(String world);
    String resume(String world);
    String cancel(String world);
    String status();
}
