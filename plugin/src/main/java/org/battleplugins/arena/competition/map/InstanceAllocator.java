package org.battleplugins.arena.competition.map;

import org.battleplugins.arena.competition.map.options.Bounds;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Allocates non-overlapping regions inside the shared
 * instances world so dynamic maps cannot collide.
 */
public final class InstanceAllocator {
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<Integer, Bounds> reservations = new HashMap<>();

    public record Allocation(int offsetX, Bounds shiftedBounds) {}

    /**
     * Reserves a region for the supplied slot.
     *
     * @param slot the logical slot id
     * @param template the template bounds to shift
     * @param spacing the gap to keep between maps
     * @return the computed allocation
     */
    public Allocation reserve(int slot, Bounds template, int spacing) {
        if (template == null) {
            throw new IllegalArgumentException("Cannot reserve instances space without bounds");
        }

        lock.lock();
        try {
            int width = template.getMaxX() - template.getMinX();
            int candidateMinX = 0;
            List<Bounds> sorted = new ArrayList<>(this.reservations.values());
            sorted.sort(Comparator.comparingInt(Bounds::getMinX));

            for (Bounds existing : sorted) {
                int availableEnd = existing.getMinX() - spacing;
                if (candidateMinX + width <= availableEnd) {
                    break;
                }

                candidateMinX = existing.getMaxX() + spacing;
            }

            int offsetX = candidateMinX - template.getMinX();
            Bounds shifted = template.shift(offsetX, 0, 0);
            this.reservations.put(slot, shifted);
            return new Allocation(offsetX, shifted);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Releases the reservation for the supplied slot id.
     *
     * @param slot the slot id to release
     */
    public void release(int slot) {
        lock.lock();
        try {
            this.reservations.remove(slot);
        } finally {
            lock.unlock();
        }
    }
}
