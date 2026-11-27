package com.adhook.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class LatestStatsDeliveryTest {
    private static final class FakeScheduler implements LatestStatsDelivery.Scheduler {
        long now;
        long order;
        final PriorityQueue<Event> events = new PriorityQueue<>();
        @Override public void schedule(Runnable task, long delay) { events.add(new Event(now + delay, order++, task)); }
        void advance(long until) {
            while (!events.isEmpty() && events.peek().when <= until) {
                Event event = events.remove(); now = event.when; event.task.run();
            }
            now = until;
        }
        private record Event(long when, long order, Runnable task) implements Comparable<Event> {
            @Override public int compareTo(Event other) {
                int time = Long.compare(when, other.when);
                return time != 0 ? time : Long.compare(order, other.order);
            }
        }
    }

    @Test public void finalHitInsideTheWindowGetsATrailingFlushWithoutAnotherEvent() {
        FakeScheduler scheduler = new FakeScheduler();
        AtomicInteger count = new AtomicInteger();
        List<Integer> delivered = new ArrayList<>();
        LatestStatsDelivery<Integer> delivery = new LatestStatsDelivery<>(() -> scheduler.now, scheduler,
                (pkg, value) -> { delivered.add(value); return true; }, 2500, 7);
        delivery.offer("com.test.host", count::get);
        scheduler.advance(0);
        scheduler.advance(100);
        count.incrementAndGet();
        delivery.offer("com.test.host", count::get);
        scheduler.advance(2499);
        assertEquals(List.of(0), delivered);
        scheduler.advance(2500);
        assertEquals(List.of(0, 1), delivered);
        assertTrue(scheduler.events.isEmpty());
    }

    @Test public void burstsKeepOneLatestSlotAndMaterializeOnlyAtDelivery() {
        FakeScheduler scheduler = new FakeScheduler();
        AtomicInteger count = new AtomicInteger();
        AtomicInteger captures = new AtomicInteger();
        List<Integer> delivered = new ArrayList<>();
        LatestStatsDelivery<Integer> delivery = new LatestStatsDelivery<>(() -> scheduler.now, scheduler,
                (pkg, value) -> { delivered.add(value); return true; }, 2500, 7);
        for (int i = 0; i < 1000; i++) {
            count.incrementAndGet();
            delivery.offer("com.test.host", () -> { captures.incrementAndGet(); return count.get(); });
        }
        assertEquals(1, scheduler.events.size());
        assertEquals(0, captures.get());
        scheduler.advance(0);
        assertEquals(List.of(1000), delivered);
        assertEquals(1, captures.get());
    }

    @Test public void anOfferWhileTheSenderIsBusyIsNotDropped() {
        FakeScheduler scheduler = new FakeScheduler();
        List<Integer> delivered = new ArrayList<>();
        List<LatestStatsDelivery<Integer>> holder = new ArrayList<>();
        LatestStatsDelivery<Integer> delivery = new LatestStatsDelivery<>(() -> scheduler.now, scheduler,
                (pkg, value) -> {
                    delivered.add(value);
                    if (value == 1) holder.get(0).offer(pkg, () -> 2);
                    return true;
                }, 2500, 7);
        holder.add(delivery);
        delivery.offer("com.test.host", () -> 1);
        scheduler.advance(0);
        scheduler.advance(2500);
        assertEquals(List.of(1, 2), delivered);
        assertTrue(scheduler.events.isEmpty());
    }

    @Test public void failedAckRetriesLatestWithBackoffAndStopsOnSuccess() {
        FakeScheduler scheduler = new FakeScheduler();
        AtomicInteger attempts = new AtomicInteger();
        List<Integer> received = new ArrayList<>();
        LatestStatsDelivery<Integer> delivery = new LatestStatsDelivery<>(() -> scheduler.now, scheduler,
                (pkg, value) -> { received.add(value); return attempts.incrementAndGet() >= 3; }, 2500, 7);
        delivery.offer("com.test.host", () -> 1);
        scheduler.advance(0);
        scheduler.advance(100);
        delivery.offer("com.test.host", () -> 9);
        scheduler.advance(2500);
        assertEquals(List.of(1, 9), received);
        scheduler.advance(7500);
        assertEquals(List.of(1, 9, 9), received);
        assertTrue(scheduler.events.isEmpty());
    }

    @Test public void unavailableProviderHasBoundedRetriesAndANewChangeCanRearm() {
        FakeScheduler scheduler = new FakeScheduler();
        AtomicInteger attempts = new AtomicInteger();
        LatestStatsDelivery<Integer> delivery = new LatestStatsDelivery<>(() -> scheduler.now, scheduler,
                (pkg, value) -> { attempts.incrementAndGet(); throw new IllegalStateException("provider unavailable"); }, 2500, 3);
        delivery.offer("com.test.host", () -> 1);
        scheduler.advance(1_000_000);
        assertEquals(3, attempts.get());
        assertTrue(scheduler.events.isEmpty());
        delivery.offer("com.test.host", () -> 2);
        scheduler.advance(2_000_000);
        assertEquals(6, attempts.get());
        assertTrue(scheduler.events.isEmpty());
    }

    @Test public void backwardClockDoesNotSuppressAFinalUpdateForever() {
        FakeScheduler scheduler = new FakeScheduler();
        AtomicInteger count = new AtomicInteger();
        LatestStatsDelivery<Integer> delivery = new LatestStatsDelivery<>(() -> scheduler.now, scheduler,
                (pkg, value) -> { count.incrementAndGet(); return true; }, 2500, 3);
        scheduler.now = 10000;
        delivery.offer("com.test.host", () -> 1);
        scheduler.advance(10000);
        scheduler.now = 5;
        delivery.offer("com.test.host", () -> 2);
        scheduler.advance(5);
        assertEquals(2, count.get());
    }
}
