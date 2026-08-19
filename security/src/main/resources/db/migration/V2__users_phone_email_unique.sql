ALTER TABLE users
    ADD UNIQUE KEY uk_users_phone_number (phone_number),
    ADD UNIQUE KEY uk_users_email (email);
