package com.open.spring.mvc.preferences;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface StylePreferenceJpaRepository extends JpaRepository<StylePreference, Long> {
    Optional<StylePreference> findByPersonId(Long personId);
}
