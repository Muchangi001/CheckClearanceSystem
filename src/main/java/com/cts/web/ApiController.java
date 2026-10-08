package com.cts.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.cts.cheque.Cheque;
import com.cts.cheque.ChequeRepository;
import com.cts.cheque.ChequeStatus;
import com.cts.cheque.ValidationException;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * JSON over the same services the pages use. This is the surface a Flutter
 * capture app or another system builds against; the pages are one client of it.
 */
@RestController
@RequestMapping("/api")
class ApiController {

	private final ChequeRepository cheques;

	ApiController(ChequeRepository cheques) {
		this.cheques = cheques;
	}

	record ChequeView(long id, String serial, String micrCode, String accountNo, String txCode, long amountPaise,
			LocalDate chequeDate, String payeeName, String depositorAccount, ChequeStatus status, String statusReason,
			String returnCode, boolean deemed, Instant capturedAt, Instant presentedAt, Instant expiresAt,
			Instant decidedAt, Long settlementBatchId) {

		static ChequeView of(Cheque c) {
			return new ChequeView(c.getId(), c.getSerial(), c.getMicrCode(), c.getAccountNo(), c.getTxCode(),
					c.getAmountPaise(), c.getChequeDate(), c.getPayeeName(), c.getDepositorAccount(), c.getStatus(),
					c.getStatusReason(), c.getReturnCode(), c.isDeemed(), c.getCapturedAt(), c.getPresentedAt(),
					c.getExpiresAt(), c.getDecidedAt(), c.getSettlementBatchId());
		}
	}

	@GetMapping("/cheques")
	List<ChequeView> list(@RequestParam(required = false) ChequeStatus status) {
		return (status == null ? cheques.findTop100ByOrderByIdDesc() : cheques.findTop100ByStatusOrderByIdDesc(status))
			.stream()
			.map(ChequeView::of)
			.toList();
	}

	@GetMapping("/cheques/{id}")
	ChequeView get(@PathVariable long id) {
		return cheques.findById(id).map(ChequeView::of).orElseThrow(() -> new ValidationException("No cheque " + id));
	}

}
