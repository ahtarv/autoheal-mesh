package com.autoheal;

import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Main {

    public static void main(String[] args) throws InterruptedException {
        configureLogging();

        AutoHealEngine engine = new AutoHealEngine(AutoHealEngine.Config.fastDemo());
        engine.start();

        System.out.println("[Main] Engine running. Injecting failures in 5 seconds...");
        Thread.sleep(5_000);

        System.out.println("\n[Simulation] Injecting exception storm...");
        for (int i = 0; i < 20; i++) {
            engine.getCollector().recordException();
            Thread.sleep(50);
        }

        Thread.sleep(8_000);

        System.out.println("\n[Simulation] Allocating memory to pressure the heap...");
        byte[][] blocks = new byte[50][];
        for (int i = 0; i < 50; i++) {
            blocks[i] = new byte[1024 * 1024];
            Thread.sleep(100);
        }

        Thread.sleep(10_000);

        System.out.println("\n[Simulation] State: " + engine.getStateMachine().getState());
        System.out.println("[Simulation] Remediation attempts: " + engine.getStateMachine().getRemediationAttempts());

        for (int i = 0; i < blocks.length; i++) blocks[i] = null;
        System.gc();

        Thread.sleep(5_000);

        System.out.println("\n[Main] Shutting down.");
        engine.stop();
    }

    private static void configureLogging() {
        Logger root = Logger.getLogger("");
        root.setLevel(Level.INFO);

        ConsoleHandler handler = new ConsoleHandler();
        handler.setLevel(Level.ALL);
        handler.setFormatter(new java.util.logging.SimpleFormatter() {
            @Override
            public synchronized String format(java.util.logging.LogRecord record) {
                String color = switch (record.getLevel().getName()) {
                    case "SEVERE"  -> "\u001B[31m";
                    case "WARNING" -> "\u001B[33m";
                    case "INFO"    -> "\u001B[36m";
                    default        -> "\u001B[0m";
                };
                return String.format("%s[%-7s] %s%s%n",
                        color, record.getLevel().getName(), record.getMessage(), "\u001B[0m");
            }
        });

        root.getHandlers()[0].setFormatter(handler.getFormatter());
    }
}
