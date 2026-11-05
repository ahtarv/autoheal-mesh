package com.autoheal.core;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.function.Consumer;
import java.util.logging.Logger;

public final class EventBus<T> {

    private static final Logger LOG = Logger.getLogger(EventBus.class.getName());

    private final String name;
    private final BlockingQueue<T> queue;
    private volatile Consumer<T> subscriber;
    private volatile boolean running = false;
    private Thread dispatchThread;

    public EventBus(String name, int capacity) {
        this.name = name;
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    public void publish(T event) {
        if (!queue.offer(event)) {
            LOG.warning("[EventBus:" + name + "] Queue full, dropping event");
        }
    }

    public void subscribe(Consumer<T> handler) {
        this.subscriber = handler;
    }

    public void start() {
        if (subscriber == null) throw new IllegalStateException("No subscriber on bus: " + name);
        running = true;
        dispatchThread = Thread.ofVirtual()
                .name("event-bus-" + name)
                .start(this::dispatchLoop);
    }

    public void stop() {
        running = false;
        if (dispatchThread != null) dispatchThread.interrupt();
    }

    private void dispatchLoop() {
        while (running) {
            try {
                T event = queue.take();
                subscriber.accept(event);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOG.severe("[EventBus:" + name + "] Subscriber threw: " + e.getMessage());
            }
        }
    }
}
