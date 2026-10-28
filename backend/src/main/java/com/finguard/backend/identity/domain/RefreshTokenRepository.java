package com.finguard.backend.identity.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, String> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Locks the row for the rest of the transaction. Refresh rotation uses this so that concurrent requests
     * presenting the same token are serialised: exactly one rotates it, the others see it as already revoked.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefreshToken r where r.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    List<RefreshToken> findByUser(User user);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken r set r.revoked = true where r.user.id = :userId and r.revoked = false")
    int revokeAllForUserId(@Param("userId") String userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from RefreshToken r where r.expiresAt < :cutoff or (r.revoked = true and r.createdAt < :revokedCutoff)")
    int deleteExpiredOrRevokedBefore(@Param("cutoff") java.time.Instant cutoff, @Param("revokedCutoff") java.time.Instant revokedCutoff);
}
