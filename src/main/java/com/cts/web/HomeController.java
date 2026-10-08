package com.cts.web;

import java.util.HashMap;
import java.util.Map;

import com.cts.audit.AuditService;
import com.cts.cheque.ChequeRepository;
import com.cts.cheque.ChequeStatus;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
class HomeController {

	private final ChequeRepository cheques;
	private final AuditService audit;

	HomeController(ChequeRepository cheques, AuditService audit) {
		this.cheques = cheques;
		this.audit = audit;
	}

	@GetMapping("/login")
	String login() {
		return "login";
	}

	@GetMapping("/")
	String dashboard(@RequestParam(required = false) ChequeStatus status, Model model) {
		// Keyed by name: SpEL can't index a map by an enum value.
		Map<String, ChequeRepository.StatusCount> counts = new HashMap<>();
		cheques.countByStatus().forEach(c -> counts.put(c.getStatus().name(), c));
		model.addAttribute("statuses", ChequeStatus.values());
		model.addAttribute("counts", counts);
		model.addAttribute("filter", status);
		model.addAttribute("cheques",
				status == null ? cheques.findTop100ByOrderByIdDesc() : cheques.findTop100ByStatusOrderByIdDesc(status));
		model.addAttribute("events", audit.recent());
		return "index";
	}

}
