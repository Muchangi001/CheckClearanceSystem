package com.cts;

import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Business rules that are policy, not code. Amounts are paise.
 *
 * @param approvalThresholdPaise items at or above this need a checker before presentment
 * @param ppsThresholdPaise      Positive Pay is checked at or above this (RBI: ₹50,000)
 * @param ppsMandatoryPaise      above this, no Positive Pay registration means return (bank policy, RBI allows from ₹5,00,000)
 * @param clearingPhase          1 = confirm by end of the confirmation window, 2 = T + 3 clear hours per item
 * @param confirmationCutoff     end of the confirmation window, local time (RBI: 19:00)
 */
@ConfigurationProperties("cts")
public record CtsProperties(
		long approvalThresholdPaise,
		long ppsThresholdPaise,
		long ppsMandatoryPaise,
		int clearingPhase,
		String confirmationCutoff,
		String zone) {

	public ZoneId zoneId() {
		return ZoneId.of(zone);
	}

}
