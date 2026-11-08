-- UUID member principals are the service user ID; anonymous/legacy non-member principals remain NULL.
ALTER TABLE spring_session ADD COLUMN principal_user_id UUID GENERATED ALWAYS AS (
    CASE WHEN principal_name ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
        THEN principal_name::uuid END
) STORED;

-- Existing orphan member sessions are already unauthenticated by MemberIdentityService.
DELETE FROM spring_session s WHERE principal_user_id IS NOT NULL
    AND NOT EXISTS (SELECT 1 FROM app_user u WHERE u.id=s.principal_user_id);

ALTER TABLE spring_session ADD CONSTRAINT spring_session_member_fk
    FOREIGN KEY (principal_user_id) REFERENCES app_user(id) ON DELETE CASCADE;
CREATE INDEX spring_session_member_ix ON spring_session(principal_user_id)
    WHERE principal_user_id IS NOT NULL;
COMMENT ON COLUMN spring_session.principal_user_id IS
    'Derived service identity only. Cascade removes all member sessions and prevents delayed saves from recreating withdrawn authentication.';
