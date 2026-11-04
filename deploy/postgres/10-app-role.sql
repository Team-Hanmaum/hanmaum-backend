-- Only runs when postgres-data is empty. Never reset the volume to change a password.
-- psql quotes the value as a literal; no password is passed through a shell command.
\getenv app_password HANMAUM_APP_PASSWORD
CREATE ROLE hanmaum_app WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'app_password';
ALTER DATABASE hanmaum OWNER TO hanmaum_app;
-- PostgreSQL 17's pg_database_owner owns public; the new database owner inherits it.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
