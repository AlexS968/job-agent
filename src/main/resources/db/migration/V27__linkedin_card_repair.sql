-- Keep the first observation and its vacancy revision intact.
ALTER TABLE gmail_alert_cards
    ADD COLUMN advertised_title varchar(255),
    ADD COLUMN advertised_employer varchar(255),
    ADD COLUMN advertised_location varchar(120),
    ADD COLUMN parsed_direction varchar(20) CHECK (parsed_direction IN
        ('GERMANY','AUSTRIA','REMOTE_EUROPE','REMOTE_WORLDWIDE','UNKNOWN'));

ALTER TABLE gmail_alert_messages
    ADD COLUMN pre_repair_unsupported_links integer;
