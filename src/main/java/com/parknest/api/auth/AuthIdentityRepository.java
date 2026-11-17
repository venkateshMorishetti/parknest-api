package com.parknest.api.auth;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthIdentityRepository extends JpaRepository<AuthIdentity, String> {

    Optional<AuthIdentity> findByProviderAndProviderSubject(String provider, String providerSubject);

    Optional<AuthIdentity> findByUserIdAndProvider(String userId, String provider);

    List<AuthIdentity> findByUserIdOrderByProviderDesc(String userId);
}
