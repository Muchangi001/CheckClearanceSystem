package com.cts.audit;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Append-only. The table's trigger refuses updates and deletes. */
@Service
public class AuditService {

	private final JdbcClient jdbc;

	public AuditService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void record(Long chequeId, String actor, String action, String detail) {
		jdbc.sql("insert into audit_event (cheque_id, actor, action, detail) values (?, ?, ?, ?)")
			.params(chequeId, actor, action, detail)
			.update();
	}

	public List<Event> forCheque(long chequeId) {
		return jdbc.sql("select actor, action, detail, at from audit_event where cheque_id = ? order by id")
			.param(chequeId)
			.query(Event.class)
			.list();
	}

	public List<Event> recent() {
		return jdbc.sql("""
				select a.actor, a.action, coalesce('#' || a.cheque_id || ' ', '') || coalesce(a.detail, '') as detail, a.at
				from audit_event a order by a.id desc limit 50""")
			.query(Event.class)
			.list();
	}

	public record Event(String actor, String action, String detail, OffsetDateTime at) {
	}

}
