-- The production database and its login role. Run by 50-database.sh as the admin user; safe to run again
-- (it then only resets the role's password to the one in secret youstream-db).
-- psql variables: db_name, db_user, db_password.
\set ON_ERROR_STOP on

SELECT format('CREATE ROLE %I LOGIN', :'db_user')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'db_user') \gexec
-- only LOGIN and the password: a non-superuser admin (doadmin) may not name SUPERUSER and similar attributes
ALTER ROLE :"db_user" WITH LOGIN PASSWORD :'db_password';

SELECT format('CREATE DATABASE %I', :'db_name')
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = :'db_name') \gexec
REVOKE ALL ON DATABASE :"db_name" FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE :"db_name" TO :"db_user";

\connect :db_name
-- Flyway creates the tables as db_user, which then owns them
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO :"db_user";

SELECT current_database() AS database, :'db_user' AS role, version();
