package com.open.spring.mvc.person;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ResetTicketJpaRepository extends JpaRepository<ResetTicket, Long> {
    List<ResetTicket> findByResolvedFalseOrderByIdDesc();
    List<ResetTicket> findByUidAndResolvedFalse(String uid);
}
