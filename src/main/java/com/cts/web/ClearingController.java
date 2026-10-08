package com.cts.web;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;

import com.cts.cheque.ChequeRepository;
import com.cts.cheque.ChequeStatus;
import com.cts.cheque.ValidationException;
import com.cts.clearing.PresentmentService;
import com.cts.clearing.SettlementService;
import com.cts.ledger.LedgerService;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
class ClearingController {

	private final PresentmentService presentment;
	private final SettlementService settlement;
	private final LedgerService ledger;
	private final ChequeRepository cheques;
	private final JdbcClient jdbc;

	ClearingController(PresentmentService presentment, SettlementService settlement, LedgerService ledger,
			ChequeRepository cheques, JdbcClient jdbc) {
		this.presentment = presentment;
		this.settlement = settlement;
		this.ledger = ledger;
		this.cheques = cheques;
		this.jdbc = jdbc;
	}

	@PostMapping("/clearing/present")
	String present(Principal user, RedirectAttributes redirect) {
		int count = presentment.presentReady(user.getName());
		redirect.addFlashAttribute("notice", count == 0 ? "Nothing ready to present."
				: "Presented " + count + " item(s): signed and sent to the clearing house.");
		return "redirect:/";
	}

	@GetMapping("/inward")
	String inward(Model model) {
		model.addAttribute("cheques", cheques.findByStatusOrderByIdAsc(ChequeStatus.PRESENTED));
		return "inward";
	}

	@GetMapping("/settlement")
	String settlementPage(@RequestParam(required = false) Long batch, Model model) {
		List<SettlementService.Batch> batches = settlement.batches();
		Long selected = batch != null ? batch : batches.isEmpty() ? null : batches.get(0).id();
		model.addAttribute("batches", batches);
		model.addAttribute("selected", selected);
		model.addAttribute("positions", selected == null ? List.of() : settlement.positions(selected));
		model.addAttribute("items", selected == null ? List.of() : cheques.findBySettlementBatchId(selected));
		model.addAttribute("confirmed", cheques.findByStatusOrderByIdAsc(ChequeStatus.CONFIRMED).size());
		return "settlement";
	}

	@PostMapping("/settlement/run")
	String settle(Principal user, RedirectAttributes redirect) {
		Long batch = settlement.settle(user.getName());
		if (batch == null) {
			redirect.addFlashAttribute("notice", "Nothing confirmed to settle.");
			return "redirect:/settlement";
		}
		redirect.addFlashAttribute("notice", "Settlement batch " + batch + " complete; payees credited.");
		return "redirect:/settlement?batch=" + batch;
	}

	@GetMapping("/ledger")
	String ledgerPage(Model model) {
		model.addAttribute("entries", ledger.recent());
		model.addAttribute("totals", ledger.totals());
		model.addAttribute("balances", ledger.balances());
		return "ledger";
	}

	record DraweeAccount(long id, String micrCode, String accountNo, String holderName, long balancePaise,
			String status) {
	}

	record PositivePayRow(String holderName, String accountNo, String serial, LocalDate chequeDate, String payeeName,
			long amountPaise) {
	}

	@GetMapping("/positive-pay")
	String positivePay(Model model) {
		model.addAttribute("accounts", jdbc.sql("""
				select id, micr_code, account_no, holder_name, balance_paise, status from drawee_account order by id""")
			.query(DraweeAccount.class).list());
		model.addAttribute("entries", jdbc.sql("""
				select a.holder_name, a.account_no, p.serial, p.cheque_date, p.payee_name, p.amount_paise
				from positive_pay p join drawee_account a on a.id = p.account_id order by p.id desc""")
			.query(PositivePayRow.class).list());
		model.addAttribute("today", LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")));
		return "positive-pay";
	}

	@PostMapping("/positive-pay")
	String registerPositivePay(@RequestParam long accountId, @RequestParam String serial,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate chequeDate,
			@RequestParam String payeeName, @RequestParam String amount, RedirectAttributes redirect) {
		try {
			if (!serial.matches("\\d{6}")) {
				throw new ValidationException("Cheque serial must be 6 digits");
			}
			if (payeeName.isBlank()) {
				throw new ValidationException("Payee name is required");
			}
			long paise = com.cts.cheque.CaptureService.parseRupees(amount);
			jdbc.sql("""
					insert into positive_pay (account_id, serial, cheque_date, payee_name, amount_paise)
					values (?, ?, ?, ?, ?)""")
				.params(accountId, serial, chequeDate, payeeName.trim(), paise)
				.update();
			redirect.addFlashAttribute("notice", "Positive Pay registered for cheque " + serial + ".");
		}
		catch (ValidationException ex) {
			redirect.addFlashAttribute("error", ex.getMessage());
		}
		catch (DuplicateKeyException ex) {
			redirect.addFlashAttribute("error", "Cheque " + serial + " is already registered for that account.");
		}
		return "redirect:/positive-pay";
	}

}
