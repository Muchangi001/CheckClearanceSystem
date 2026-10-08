package com.cts.clearing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** The clock of continuous clearing: deemed approvals every minute, settlement hourly from 11:00 IST. */
@Component
class ClearingScheduler {

	private static final Logger log = LoggerFactory.getLogger(ClearingScheduler.class);

	private final DraweeService drawee;
	private final SettlementService settlement;

	ClearingScheduler(DraweeService drawee, SettlementService settlement) {
		this.drawee = drawee;
		this.settlement = settlement;
	}

	@Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
	void deemExpired() {
		int count = drawee.deemExpired();
		if (count > 0) {
			log.info("Deemed approved {} expired items", count);
		}
	}

	@Scheduled(cron = "0 0 11-19 * * *", zone = "Asia/Kolkata")
	void settleHourly() {
		Long batch = settlement.settle("SCHEDULER");
		if (batch != null) {
			log.info("Settlement batch {}", batch);
		}
	}

}
