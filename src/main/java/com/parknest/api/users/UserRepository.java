package com.parknest.api.users;

import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByPhone(String phone);

    Optional<User> findByEmailIgnoreCase(String email);

    /** Takes an email off accounts that only typed it in (not verified), when someone proves they own it. */
    @Modifying
    @Query("update User u set u.email = null where lower(u.email) = lower(:email) and u.emailVerifiedAt is null")
    int clearUnverifiedEmail(@Param("email") String email);

    /** Admin search: q matches name, phone, email or id (pass "" for everyone). Newest first. */
    @Query("""
            select u from User u where :q = '' or lower(u.name) like concat('%', :q, '%') or u.phone like concat('%', :q, '%')
              or lower(u.email) like concat('%', :q, '%') or lower(u.id) = :q
            order by u.createdAt desc
            """)
    List<User> search(@Param("q") String q, Pageable page);
}
