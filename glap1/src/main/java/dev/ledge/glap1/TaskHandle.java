package dev.ledge.glap1;

/** Cancellable scheduled task, independent of the server's scheduler API generation. */
interface TaskHandle {
    void cancel();
}
