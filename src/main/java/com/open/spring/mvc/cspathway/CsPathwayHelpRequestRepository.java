package com.open.spring.mvc.cspathway;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CsPathwayHelpRequestRepository extends JpaRepository<CsPathwayHelpRequest, String> {
    Optional<CsPathwayHelpRequest> findFirstByUidOrderByCreatedAtDesc(String uid);

    List<CsPathwayHelpRequest> findTop200ByOrderByCreatedAtDesc();
}
