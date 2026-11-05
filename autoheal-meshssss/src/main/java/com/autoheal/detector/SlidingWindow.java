package com.autoheal.detector;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.OptionalDouble;

public final class SlidingWindow {

    private final int capacity;
    private final Deque<Double> window;

    public SlidingWindow(int capacity) {
        if (capacity < 2) throw new IllegalArgumentException("Capacity must be >= 2");
        this.capacity = capacity;
        this.window = new ArrayDeque<>(capacity);
    }

    public void add(double value) {
        if (window.size() == capacity) {
            window.pollFirst();
        }
        window.addLast(value);
    }

    public int size() { return window.size(); }
    public boolean isFull() { return window.size() == capacity; }

    public OptionalDouble average() {
        return window.stream().mapToDouble(Double::doubleValue).average();
    }

    public OptionalDouble max() {
        return window.stream().mapToDouble(Double::doubleValue).max();
    }

    public double slope() {
        int n = window.size();
        if (n < 2) return 0.0;

        double[] values = window.stream().mapToDouble(Double::doubleValue).toArray();
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;

        for (int i = 0; i < n; i++) {
            sumX  += i;
            sumY  += values[i];
            sumXY += i * values[i];
            sumX2 += (double) i * i;
        }

        double denom = n * sumX2 - sumX * sumX;
        if (denom == 0) return 0.0;
        return (n * sumXY - sumX * sumY) / denom;
    }

    public double[] toArray() {
        return window.stream().mapToDouble(Double::doubleValue).toArray();
    }

    @Override
    public String toString() {
        return String.format("SlidingWindow[size=%d/%d avg=%.2f slope=%.4f]",
                window.size(), capacity, average().orElse(0.0), slope());
    }
}
