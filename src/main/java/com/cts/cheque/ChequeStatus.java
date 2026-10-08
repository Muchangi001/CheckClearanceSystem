package com.cts.cheque;

/**
 * <pre>
 * capture ─┬─ REJECTED            (stale, post-dated, duplicate, unknown bank)
 *          ├─ PENDING_APPROVAL ─┬─ READY
 *          │                    └─ REJECTED
 *          └─ READY ── PRESENTED ─┬─ CONFIRMED ── SETTLED
 *                                 └─ RETURNED
 * </pre>
 */
public enum ChequeStatus {
	PENDING_APPROVAL,
	READY,
	PRESENTED,
	CONFIRMED,
	RETURNED,
	SETTLED,
	REJECTED
}
