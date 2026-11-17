package com.parknest.api.admin;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminActionRepository extends JpaRepository<AdminAction, Long> {

    List<AdminAction> findAllByOrderByCreatedAtDescIdDesc(Pageable page);

    Optional<AdminAction> findFirstByTargetTypeAndTargetIdAndActionInOrderByCreatedAtDescIdDesc(String targetType, String targetId,
                                                                                                   List<String> actions);
}
