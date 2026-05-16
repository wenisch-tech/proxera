CREATE TABLE settings
(
    id           BIGINT  NOT NULL PRIMARY KEY,
    rewrite_urls BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO settings (id, rewrite_urls)
VALUES (1, TRUE);
