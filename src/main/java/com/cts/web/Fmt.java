package com.cts.web;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import org.springframework.stereotype.Component;

/** Formatting for templates: ${@fmt.inr(paise)}. */
@Component("fmt")
public class Fmt {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm");

	/** Indian grouping: ₹12,34,567.89. java.text can't do the 2-digit groups. */
	public String inr(long paise) {
		boolean negative = paise < 0;
		long abs = Math.abs(paise);
		String rupees = Long.toString(abs / 100);
		String grouped;
		if (rupees.length() <= 3) {
			grouped = rupees;
		}
		else {
			String head = rupees.substring(0, rupees.length() - 3);
			String tail = rupees.substring(rupees.length() - 3);
			grouped = head.replaceAll("\\B(?=(\\d{2})+$)", ",") + "," + tail;
		}
		return (negative ? "−₹" : "₹") + grouped + "." + String.format("%02d", abs % 100);
	}

	public String time(Instant instant) {
		return instant == null ? "" : TIME.format(instant.atZone(IST)) + " IST";
	}

	public String time(OffsetDateTime time) {
		return time == null ? "" : time(time.toInstant());
	}

}
