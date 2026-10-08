package com.cts.cheque;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChequeImageRepository extends JpaRepository<ChequeImage, Long> {

	Optional<ChequeImage> findByChequeIdAndSide(Long chequeId, String side);

	List<ChequeImage> findByChequeIdOrderBySideDesc(Long chequeId);

}
