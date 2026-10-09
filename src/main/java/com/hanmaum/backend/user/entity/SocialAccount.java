package com.hanmaum.backend.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(
    name = "social_account",
    uniqueConstraints =
        @UniqueConstraint(
            name = "social_account_provider_user_id_uq",
            columnNames = {"provider", "provider_user_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SocialAccount {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false, updatable = false)
  private AppUser user;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false, columnDefinition = "text")
  private SocialProvider provider;

  @Column(name = "provider_user_id", nullable = false, updatable = false, columnDefinition = "text")
  private String providerUserId;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public SocialAccount(AppUser user, SocialProvider provider, String providerUserId) {
    this.user = Objects.requireNonNull(user, "user");
    this.provider = Objects.requireNonNull(provider, "provider");
    this.providerUserId = Objects.requireNonNull(providerUserId, "providerUserId");
  }
}
