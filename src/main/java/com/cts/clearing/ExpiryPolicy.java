package com.cts.clearing;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;

import com.cts.CtsProperties;

import org.springframework.stereotype.Component;

/**
 * When a presented item is deemed approved if the drawee hasn't answered.
 *
 * Phase 1 (live since 4 Oct 2025): the end of the confirmation window, 19:00 IST.
 * Phase 2 (deferred by RBI on 24 Dec 2025): T + 3 clear hours per item.
 * Switching is configuration (CTS_CLEARING_PHASE), not a code change.
 */
@Component
public class ExpiryPolicy {

	private final CtsProperties props;

	public ExpiryPolicy(CtsProperties props) {
		this.props = props;
	}

	public Instant expiryFor(Instant presentedAt) {
		if (props.clearingPhase() == 2) {
			return presentedAt.plus(Duration.ofHours(3));
		}
		ZonedDateTime local = presentedAt.atZone(props.zoneId());
		ZonedDateTime cutoff = local.with(LocalTime.parse(props.confirmationCutoff()));
		if (!local.isBefore(cutoff)) {
			cutoff = cutoff.plusDays(1);
		}
		return cutoff.toInstant();
	}

}
