package com.cts.web;

import java.time.LocalDate;
import java.security.Principal;

import com.cts.audit.AuditService;
import com.cts.cheque.ApprovalService;
import com.cts.cheque.CaptureService;
import com.cts.cheque.CaptureService.CaptureRequest;
import com.cts.cheque.Cheque;
import com.cts.cheque.ChequeImage;
import com.cts.cheque.ChequeImageRepository;
import com.cts.cheque.ChequeRepository;
import com.cts.cheque.ChequeStatus;
import com.cts.cheque.ValidationException;
import com.cts.clearing.DraweeService;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
class ChequeController {

	private final CaptureService capture;
	private final ApprovalService approval;
	private final DraweeService drawee;
	private final ChequeRepository cheques;
	private final ChequeImageRepository images;
	private final AuditService audit;
	private final JdbcClient jdbc;

	ChequeController(CaptureService capture, ApprovalService approval, DraweeService drawee, ChequeRepository cheques,
			ChequeImageRepository images, AuditService audit, JdbcClient jdbc) {
		this.capture = capture;
		this.approval = approval;
		this.drawee = drawee;
		this.cheques = cheques;
		this.images = images;
		this.audit = audit;
		this.jdbc = jdbc;
	}

	record DepositAccount(String accountNo, String holderName, long balancePaise) {
	}

	record ReturnReason(String code, String description) {
	}

	@GetMapping("/cheques/new")
	String captureForm(Model model) {
		model.addAttribute("accounts", depositAccounts());
		model.addAttribute("today", LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")));
		return "capture";
	}

	@PostMapping("/cheques")
	String capture(@RequestParam String serial, @RequestParam String micrCode, @RequestParam String accountNo,
			@RequestParam String txCode, @RequestParam String amount,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate chequeDate,
			@RequestParam String payeeName, @RequestParam String depositorAccount,
			@RequestParam(required = false) MultipartFile front, @RequestParam(required = false) MultipartFile back,
			Principal user, Model model, RedirectAttributes redirect) {
		CaptureRequest req = new CaptureRequest(serial, micrCode, accountNo, txCode, amount, chequeDate, payeeName,
				depositorAccount);
		try {
			Cheque cheque = capture.capture(req, front, back, user.getName());
			redirect.addFlashAttribute("notice", switch (cheque.getStatus()) {
				case REJECTED -> "Captured and rejected: " + cheque.getStatusReason();
				case PENDING_APPROVAL -> "Captured. High value, so it waits for a checker.";
				default -> "Captured and queued for presentment.";
			});
			return "redirect:/cheques/" + cheque.getId();
		}
		catch (ValidationException ex) {
			model.addAttribute("error", ex.getMessage());
			model.addAttribute("form", req);
			model.addAttribute("accounts", depositAccounts());
			model.addAttribute("today", LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")));
			return "capture";
		}
	}

	@GetMapping("/cheques/{id}")
	String detail(@PathVariable long id, Model model) {
		Cheque cheque = cheques.findById(id).orElseThrow(() -> new ValidationException("No cheque " + id));
		model.addAttribute("c", cheque);
		model.addAttribute("images", images.findByChequeIdOrderBySideDesc(id));
		model.addAttribute("events", audit.forCheque(id));
		model.addAttribute("bank", jdbc.sql("select name from bank where code = ?").param(cheque.draweeBankCode())
			.query(String.class).optional().orElse("Unknown bank"));
		model.addAttribute("returnReason", cheque.getReturnCode() == null ? null
				: jdbc.sql("select description from return_reason where code = ?").param(cheque.getReturnCode())
					.query(String.class).single());
		model.addAttribute("reasons", jdbc.sql("select code, description from return_reason order by code")
			.query(ReturnReason.class).list());
		return "cheque";
	}

	@GetMapping("/cheques/{id}/image/{side}")
	ResponseEntity<byte[]> image(@PathVariable long id, @PathVariable String side) {
		ChequeImage image = images.findByChequeIdAndSide(id, side.toUpperCase())
			.orElseThrow(() -> new ValidationException("No image"));
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(image.getContentType()))
			.cacheControl(CacheControl.noStore())
			.body(image.getData());
	}

	@GetMapping("/approvals")
	String approvals(Model model) {
		model.addAttribute("cheques", cheques.findByStatusOrderByIdAsc(ChequeStatus.PENDING_APPROVAL));
		return "approvals";
	}

	@PostMapping("/cheques/{id}/approve")
	String approve(@PathVariable long id, Principal user, RedirectAttributes redirect) {
		return act(id, redirect, "Approved. It goes out with the next presentment.",
				() -> approval.approve(id, user.getName()));
	}

	@PostMapping("/cheques/{id}/reject")
	String reject(@PathVariable long id, @RequestParam(required = false) String reason, Principal user,
			RedirectAttributes redirect) {
		return act(id, redirect, "Rejected.", () -> approval.reject(id, user.getName(), reason));
	}

	@PostMapping("/cheques/{id}/confirm")
	String confirm(@PathVariable long id, Principal user, RedirectAttributes redirect) {
		return act(id, redirect, "Confirmed. The drawer's account is debited; it settles in the next cycle.",
				() -> drawee.confirm(id, user.getName()));
	}

	@PostMapping("/cheques/{id}/return")
	String returnItem(@PathVariable long id, @RequestParam String code, @RequestParam(required = false) String reason,
			Principal user, RedirectAttributes redirect) {
		return act(id, redirect, "Returned with code " + code + ".",
				() -> drawee.returnManually(id, code, reason, user.getName()));
	}

	private String act(long id, RedirectAttributes redirect, String success, Runnable action) {
		try {
			action.run();
			redirect.addFlashAttribute("notice", success);
		}
		catch (ValidationException ex) {
			redirect.addFlashAttribute("error", ex.getMessage());
		}
		return "redirect:/cheques/" + id;
	}

	private java.util.List<DepositAccount> depositAccounts() {
		return jdbc.sql("select account_no, holder_name, balance_paise from deposit_account order by account_no")
			.query(DepositAccount.class)
			.list();
	}

}
