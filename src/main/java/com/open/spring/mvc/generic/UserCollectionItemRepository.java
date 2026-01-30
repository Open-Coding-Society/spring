package com.open.spring.mvc.generic;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserCollectionItemRepository extends JpaRepository<UserCollectionItem, Long> {
    List<UserCollectionItem> findByCategory(String category);

    List<UserCollectionItem> findByOwnerIdAndCategory(Long ownerId, String category);
}
