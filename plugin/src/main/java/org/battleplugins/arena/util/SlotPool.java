package org.battleplugins.arena.util;

public final class SlotPool {
    private final java.util.BitSet used = new java.util.BitSet();
    private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();

    public int acquire() {
        lock.lock();
        try {
            int i = used.nextClearBit(0);
            used.set(i);
            return i;
        } finally {
            lock.unlock();
        }
    }

    public void release(int i) {
        lock.lock();
        try {
            used.clear(i);
        } finally {
            lock.unlock();
        }
    }
}
