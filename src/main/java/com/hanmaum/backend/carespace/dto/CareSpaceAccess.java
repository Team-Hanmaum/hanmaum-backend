package com.hanmaum.backend.carespace.dto;

import java.util.UUID;

/** Internal authorization result, valid within the caller's current transaction only. */
public record CareSpaceAccess(UUID spaceId, UUID membershipId, CareSpaceRole role, long version) {}
