package com.hanmaum.backend.user.repository;

import com.hanmaum.backend.user.entity.AppUser;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {}
