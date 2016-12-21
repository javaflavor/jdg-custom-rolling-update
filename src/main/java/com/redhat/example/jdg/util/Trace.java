package com.redhat.example.jdg.util;

import java.util.concurrent.atomic.AtomicInteger;

public class Trace {
	public static AtomicInteger maxConcurrency = new AtomicInteger();
	public static AtomicInteger concurrency = new AtomicInteger();

	public static void init() {
		maxConcurrency.set(0);
		concurrency.set(0);
	}
	
	public static void begin() {
		int cur = concurrency.incrementAndGet();
		if (cur > maxConcurrency.get()) {
			maxConcurrency.set(cur);
		}
	}
	
	public static void end() {
		concurrency.decrementAndGet();
	}
}
