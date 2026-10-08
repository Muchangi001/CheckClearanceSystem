package com.cts.cheque;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ChequeRepository extends JpaRepository<Cheque, Long> {

	List<Cheque> findByStatusOrderByIdAsc(ChequeStatus status);

	List<Cheque> findTop100ByOrderByIdDesc();

	List<Cheque> findTop100ByStatusOrderByIdDesc(ChequeStatus status);

	List<Cheque> findBySettlementBatchId(Long batchId);

	List<Cheque> findByStatusAndExpiresAtBefore(ChequeStatus status, Instant cutoff);

	boolean existsByDedupeKeyAndStatusNotIn(String dedupeKey, Collection<ChequeStatus> statuses);

	@Query("select c.status as status, count(c) as count, coalesce(sum(c.amountPaise), 0) as total from Cheque c group by c.status")
	List<StatusCount> countByStatus();

	interface StatusCount {
		ChequeStatus getStatus();
		long getCount();
		long getTotal();
	}

}
